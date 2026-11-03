/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "vine_gles_pipe.c"
#include <assert.h>

struct GoldfishHwPipe { bool closed; unsigned wakes; };
static const GoldfishPipeServiceOps *ops;
struct Watch { void (*read)(void *); void (*write)(void *); void *opaque; };
static struct Watch watches[4096];

void qemu_set_fd_handler(int fd, void (*read)(void *), void (*write)(void *), void *opaque)
{ assert(fd >= 0 && fd < 4096); watches[fd] = (struct Watch){read, write, opaque}; }
void goldfish_pipe_set_service_ops(const GoldfishPipeServiceOps *value) { ops = value; }
void goldfish_pipe_close_from_host(GoldfishHwPipe *p) { p->closed = true; }
void goldfish_pipe_signal_wake(GoldfishHwPipe *p, GoldfishPipeWakeFlags flags) { p->wakes |= flags; }

static void pump(int timeout)
{
    struct pollfd fds[4096]; int n = 0;
    for (int i = 0; i < 4096; ++i) if (watches[i].read || watches[i].write)
        fds[n++] = (struct pollfd){ .fd = i,
            .events = (watches[i].read ? POLLIN : 0) | (watches[i].write ? POLLOUT : 0) };
    assert(poll(fds, n, timeout) >= 0);
    for (int i = 0; i < n; ++i) {
        struct Watch w = watches[fds[i].fd];
        if ((fds[i].revents & (POLLIN | POLLHUP | POLLERR)) && w.read) w.read(w.opaque);
        w = watches[fds[i].fd];
        if ((fds[i].revents & (POLLOUT | POLLHUP | POLLERR)) && w.write) w.write(w.opaque);
    }
}

static int send_bytes(GoldfishHostPipe *p, const void *bytes, size_t size)
{ GoldfishPipeBuffer b = {(void *)bytes, size}; return ops->guest_send(p, &b, 1); }

int main(void)
{
    vine_gles_pipe_register();
    int server = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0); assert(server >= 0);
    struct sockaddr_in address = { .sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK) };
    assert(!bind(server, (struct sockaddr *)&address, sizeof(address)));
    assert(!listen(server, 4));
    socklen_t size = sizeof(address); assert(!getsockname(server, (struct sockaddr *)&address, &size));
    char port[16]; snprintf(port, sizeof(port), "%u", ntohs(address.sin_port));
    assert(!setenv("VINE_GLES_PORT", port, 1));

    GoldfishHwPipe hw = {0}; GoldfishHostPipe *p = ops->guest_open(&hw);
    assert(ops->guest_poll(p) == GOLDFISH_PIPE_POLL_OUT);
    assert(send_bytes(p, "pipe:open", 9) == 9);
    assert(send_bytes(p, "gles", 5) == 5); /* includes connector terminator */
    int peer = accept(server, NULL, NULL); assert(peer >= 0);
    ops->guest_wake_on(p, GOLDFISH_PIPE_WAKE_WRITE); pump(100);
    assert(hw.wakes & GOLDFISH_PIPE_WAKE_WRITE);
    assert(ops->guest_poll(p) & GOLDFISH_PIPE_POLL_OUT);

    const unsigned char flags[] = {0, 0, 0, 0, 0x13, 0x00, 0xff};
    GoldfishPipeBuffer parts[] = {{(void *)flags, 2}, {(void *)(flags + 2), sizeof(flags) - 2}};
    assert(ops->guest_send(p, parts, 2) == sizeof(flags));
    unsigned char received[7]; assert(recv(peer, received, sizeof(received), MSG_WAITALL) == sizeof(received));
    assert(!memcmp(flags, received, sizeof(flags))); /* binary zero is not another connector */

    hw.wakes = 0; ops->guest_wake_on(p, GOLDFISH_PIPE_WAKE_READ); pump(0);
    assert(!(hw.wakes & GOLDFISH_PIPE_WAKE_READ));
    assert(send(peer, "reply!", 6, 0) == 6); pump(100);
    assert(hw.wakes & GOLDFISH_PIPE_WAKE_READ);
    char reply[7] = {0}; GoldfishPipeBuffer replies[] = {{reply, 2}, {reply + 2, 4}};
    assert(ops->guest_recv(p, replies, 2) == 6); assert(!strcmp(reply, "reply!"));
    assert(ops->guest_recv(p, replies, 2) == GOLDFISH_PIPE_ERROR_AGAIN);

    int small = 4096; assert(!setsockopt(p->fd, SOL_SOCKET, SO_SNDBUF, &small, sizeof(small)));
    size_t big_size = 1024 * 1024; unsigned char *big = malloc(big_size); assert(big);
    memset(big, 0xa5, big_size);
    int n = send_bytes(p, big, big_size); assert(n > 0 && n < big_size);
    bool again = false;
    for (int i = 0; i < 64; ++i) {
        n = send_bytes(p, big, big_size);
        if (n == GOLDFISH_PIPE_ERROR_AGAIN) { again = true; break; }
        assert(n > 0 && n <= big_size);
    }
    assert(again); free(big);
    GoldfishPipeBuffer invalid = {reply, (size_t)INT_MAX + 1};
    assert(ops->guest_send(p, &invalid, 1) == GOLDFISH_PIPE_ERROR_INVAL);
    close(peer); ops->guest_wake_on(p, GOLDFISH_PIPE_WAKE_READ); pump(100);
    n = ops->guest_recv(p, replies, 2);
    assert(n == GOLDFISH_PIPE_ERROR_IO); assert(hw.closed);
    int fd = p->fd; ops->guest_close(p, GOLDFISH_PIPE_CLOSE_GRACEFUL);
    if (fd >= 0) assert(!watches[fd].read && !watches[fd].write);

    const char *bad_services[] = {"pipe:tcp:example.com:80", "pipe:qemud:adb", "", "openglesx"};
    for (size_t i = 0; i < sizeof(bad_services)/sizeof(*bad_services); ++i) {
        GoldfishHwPipe denied = {0}; p = ops->guest_open(&denied);
        assert(send_bytes(p, bad_services[i], strlen(bad_services[i]) + 1) < 0);
        assert(denied.closed); ops->guest_close(p, GOLDFISH_PIPE_CLOSE_ERROR);
    }
    const char *bad_ports[] = {"0", "65536", "42suffix", ""};
    for (size_t i = 0; i < sizeof(bad_ports)/sizeof(*bad_ports); ++i) {
        setenv("VINE_GLES_PORT", bad_ports[i], 1);
        GoldfishHwPipe denied = {0}; p = ops->guest_open(&denied);
        assert(send_bytes(p, "pipe:opengles", 14) < 0);
        assert(denied.closed); ops->guest_close(p, GOLDFISH_PIPE_CLOSE_ERROR);
    }
    GoldfishHwPipe denied = {0}; p = ops->guest_open(&denied);
    char long_name[64]; memset(long_name, 'x', sizeof(long_name));
    assert(send_bytes(p, long_name, sizeof(long_name)) == GOLDFISH_PIPE_ERROR_INVAL);
    assert(denied.closed); ops->guest_close(p, GOLDFISH_PIPE_CLOSE_ERROR);
    close(server);
    puts("GLES pipe: fragmented connector, binary IO, wakeups, backpressure, EOF and rejection checks passed");
    return 0;
}
