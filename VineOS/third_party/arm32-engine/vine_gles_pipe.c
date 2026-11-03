/* SPDX-License-Identifier: GPL-2.0-or-later
 * VineOS: connect the Android guest's GLES pipe to a local renderer.
 * Only the opengles service is accepted; the guest cannot select a host/port.
 */
#include "qemu/osdep.h"
#include "qemu/main-loop.h"
#include "hw/misc/goldfish_pipe.h"
#include <arpa/inet.h>
#include <poll.h>

struct GoldfishHostPipe {
    GoldfishHwPipe *hw;
    int fd;
    unsigned wanted;
    bool connecting;
    bool closed;
    char service[64];
    size_t service_len;
};

void vine_gles_pipe_register(void);
static void update_watch(GoldfishHostPipe *p);

static void fail_pipe(GoldfishHostPipe *p)
{
    if (p->fd >= 0) {
        qemu_set_fd_handler(p->fd, NULL, NULL, NULL);
        close(p->fd);
        p->fd = -1;
    }
    if (!p->closed) {
        p->closed = true;
        goldfish_pipe_close_from_host(p->hw);
    }
}

/* Do not block the VM while the app's local renderer accepts a connection. */
static bool connected(GoldfishHostPipe *p)
{
    if (p->closed || p->fd < 0) return false;
    if (!p->connecting) return true;
    struct pollfd pollfd = { .fd = p->fd, .events = POLLOUT };
    if (poll(&pollfd, 1, 0) <= 0) return false;
    int error = 0;
    socklen_t size = sizeof(error);
    if (getsockopt(p->fd, SOL_SOCKET, SO_ERROR, &error, &size) < 0 || error ||
        (pollfd.revents & (POLLERR | POLLHUP | POLLNVAL))) {
        fail_pipe(p);
        return false;
    }
    p->connecting = false;
    return true;
}

static void readable(void *opaque)
{
    GoldfishHostPipe *p = opaque;
    p->wanted &= ~GOLDFISH_PIPE_WAKE_READ;
    update_watch(p);
    goldfish_pipe_signal_wake(p->hw, GOLDFISH_PIPE_WAKE_READ);
}

static void writable(void *opaque)
{
    GoldfishHostPipe *p = opaque;
    if (!connected(p)) return;
    unsigned wake = p->wanted & GOLDFISH_PIPE_WAKE_WRITE;
    p->wanted &= ~GOLDFISH_PIPE_WAKE_WRITE;
    update_watch(p);
    if (wake) goldfish_pipe_signal_wake(p->hw, wake);
}

static void update_watch(GoldfishHostPipe *p)
{
    if (p->fd < 0) return;
    qemu_set_fd_handler(p->fd,
        (p->wanted & GOLDFISH_PIPE_WAKE_READ) ? readable : NULL,
        (p->connecting || (p->wanted & GOLDFISH_PIPE_WAKE_WRITE)) ? writable : NULL, p);
}

static bool connect_renderer(GoldfishHostPipe *p)
{
    if (strcmp(p->service, "pipe:opengles") && strcmp(p->service, "opengles")) {
        fail_pipe(p);
        return false;
    }
    const char *value = getenv("VINE_GLES_PORT");
    char *end = NULL;
    unsigned long port = value ? strtoul(value, &end, 10) : 0;
    if (!value || !*value || !end || *end || !port || port > 65535) {
        fail_pipe(p);
        return false;
    }
    p->fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (p->fd < 0) {
        fail_pipe(p);
        return false;
    }
    struct sockaddr_in address = {
        .sin_family = AF_INET, .sin_port = htons(port),
        .sin_addr.s_addr = htonl(INADDR_LOOPBACK),
    };
    if (connect(p->fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        if (errno != EINPROGRESS) {
            fail_pipe(p);
            return false;
        }
        p->connecting = true;
    }
    update_watch(p);
    return true;
}

static GoldfishHostPipe *open_pipe(GoldfishHwPipe *hw)
{
    GoldfishHostPipe *p = g_new0(GoldfishHostPipe, 1);
    p->hw = hw;
    p->fd = -1;
    return p;
}

static void close_pipe(GoldfishHostPipe *p, GoldfishPipeCloseReason reason)
{
    (void)reason;
    if (p->fd >= 0) {
        qemu_set_fd_handler(p->fd, NULL, NULL, NULL);
        close(p->fd);
    }
    g_free(p);
}

static bool valid_buffers(const GoldfishPipeBuffer *buffers, int count)
{
    if (count < 0 || count > 1024) return false;
    size_t total = 0;
    for (int i = 0; i < count; ++i) {
        if (buffers[i].size > INT_MAX - total) return false;
        total += buffers[i].size;
    }
    return true;
}

static int send_pipe(GoldfishHostPipe *p, const GoldfishPipeBuffer *buffers, int count)
{
    if (p->closed) return GOLDFISH_PIPE_ERROR_IO;
    if (!valid_buffers(buffers, count)) return GOLDFISH_PIPE_ERROR_INVAL;
    int done = 0;
    for (int i = 0; i < count; ++i) {
        const unsigned char *data = buffers[i].data;
        size_t at = 0;
        while (p->fd < 0 && at < buffers[i].size) {
            unsigned char c = data[at++];
            ++done;
            if (!c) {
                p->service[p->service_len] = 0;
                if (!connect_renderer(p)) return GOLDFISH_PIPE_ERROR_IO;
            } else if (p->service_len + 1 < sizeof(p->service)) {
                p->service[p->service_len++] = c;
            } else {
                fail_pipe(p);
                return GOLDFISH_PIPE_ERROR_INVAL;
            }
        }
        if (at == buffers[i].size) continue;
        if (!connected(p)) return done ? done : GOLDFISH_PIPE_ERROR_AGAIN;
        ssize_t n;
        do { n = send(p->fd, data + at, buffers[i].size - at, MSG_NOSIGNAL); }
        while (n < 0 && errno == EINTR);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK)
                return done ? done : GOLDFISH_PIPE_ERROR_AGAIN;
            fail_pipe(p);
            return done ? done : GOLDFISH_PIPE_ERROR_IO;
        }
        done += n;
        if ((size_t)n < buffers[i].size - at) return done;
    }
    return done;
}

static int recv_pipe(GoldfishHostPipe *p, GoldfishPipeBuffer *buffers, int count)
{
    if (p->closed) return GOLDFISH_PIPE_ERROR_IO;
    if (!valid_buffers(buffers, count)) return GOLDFISH_PIPE_ERROR_INVAL;
    if (!connected(p)) return GOLDFISH_PIPE_ERROR_AGAIN;
    int done = 0;
    for (int i = 0; i < count; ++i) {
        if (!buffers[i].size) continue;
        ssize_t n;
        do { n = recv(p->fd, buffers[i].data, buffers[i].size, 0); }
        while (n < 0 && errno == EINTR);
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
            return done ? done : GOLDFISH_PIPE_ERROR_AGAIN;
        if (n <= 0) {
            fail_pipe(p);
            return done ? done : GOLDFISH_PIPE_ERROR_IO;
        }
        done += n;
        if ((size_t)n < buffers[i].size) return done;
    }
    return done;
}

static GoldfishPipePollFlags poll_pipe(GoldfishHostPipe *p)
{
    if (p->closed) return GOLDFISH_PIPE_POLL_HUP;
    if (p->fd < 0) return GOLDFISH_PIPE_POLL_OUT;
    if (!connected(p)) return p->closed ? GOLDFISH_PIPE_POLL_HUP : 0;
    struct pollfd fd = { .fd = p->fd, .events = POLLIN | POLLOUT };
    if (poll(&fd, 1, 0) <= 0) return 0;
    unsigned result = 0;
    if (fd.revents & POLLIN) result |= GOLDFISH_PIPE_POLL_IN;
    if (fd.revents & POLLOUT) result |= GOLDFISH_PIPE_POLL_OUT;
    if (fd.revents & (POLLHUP | POLLERR | POLLNVAL)) result |= GOLDFISH_PIPE_POLL_HUP;
    return result;
}

static void wake_pipe(GoldfishHostPipe *p, GoldfishPipeWakeFlags flags)
{
    if (p->closed) return;
    p->wanted |= flags;
    if (p->fd < 0 && (flags & GOLDFISH_PIPE_WAKE_WRITE)) {
        p->wanted &= ~GOLDFISH_PIPE_WAKE_WRITE;
        goldfish_pipe_signal_wake(p->hw, GOLDFISH_PIPE_WAKE_WRITE);
    }
    update_watch(p);
}

/* This transport does not support snapshots or guest DMA extensions. */
static void file_noop(QEMUFile *f) { (void)f; }
static void save_pipe(GoldfishHostPipe *p, QEMUFile *f) { (void)p; (void)f; }
static GoldfishHostPipe *load_pipe(QEMUFile *f, GoldfishHwPipe *hw, char *close)
{ (void)f; *close = 1; return open_pipe(hw); }
static void dma_add(void *p, uint64_t a, uint64_t n) { (void)p; (void)a; (void)n; }
static void dma_remove(uint64_t a) { (void)a; }
static void noop(void) {}

void vine_gles_pipe_register(void)
{
    static const GoldfishPipeServiceOps ops = {
        .guest_open = open_pipe, .guest_close = close_pipe,
        .guest_poll = poll_pipe, .guest_send = send_pipe, .guest_recv = recv_pipe,
        .guest_wake_on = wake_pipe, .guest_save = save_pipe, .guest_load = load_pipe,
        .guest_pre_load = file_noop, .guest_post_load = file_noop,
        .guest_pre_save = file_noop, .guest_post_save = file_noop,
        .dma_add_buffer = dma_add, .dma_remove_buffer = dma_remove,
        .dma_invalidate_host_mappings = noop, .dma_reset_host_mappings = noop,
        .dma_save_mappings = file_noop, .dma_load_mappings = file_noop,
    };
    goldfish_pipe_set_service_ops(&ops);
}
