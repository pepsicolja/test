/* SPDX-License-Identifier: GPL-2.0-or-later
 * Minimal SIGILL diagnostic for the Android AArch64 host, not a recovery path.
 * Only bounded memory access and async-signal-safe write/_exit in the handler.
 */
#if (defined(__ANDROID__) && defined(__aarch64__)) || defined(VINE_CRASH_TEST)
#include <signal.h>
#include <stdint.h>
#include <unistd.h>
#include <ucontext.h>
#include <dlfcn.h>
static uintptr_t vine_elf_base;
static char *vine_hex(char *out, uintptr_t value) {
    const char digits[] = "0123456789abcdef";
    *out++='0'; *out++='x';
    for (int bit=(int)(sizeof(value)*8)-4;bit>=0;bit-=4) *out++=digits[(value>>bit)&15];
    return out;
}
static char *vine_label(char *out, const char *label, uintptr_t value) {
    while (*label) *out++=*label++;
    return vine_hex(out,value);
}
static void vine_sigill(int sig, siginfo_t *info, void *context) {
    ucontext_t *uc=(ucontext_t *)context;
#if defined(VINE_CRASH_TEST)
    uintptr_t pc=uc->uc_mcontext.gregs[REG_RIP];
    uintptr_t lr=0,sp=uc->uc_mcontext.gregs[REG_RSP];
#else
    uintptr_t pc=uc->uc_mcontext.pc,lr=uc->uc_mcontext.regs[30],sp=uc->uc_mcontext.sp;
#endif
    char line[768],*p=line;
    p=vine_label(p,"VINE_SIGILL code=",(uintptr_t)info->si_code);
    p=vine_label(p," PC=",pc);p=vine_label(p," LR=",lr);p=vine_label(p," SP=",sp);
    p=vine_label(p," CPU_ELF_BASE=",vine_elf_base);
    if (tcg_ctx) {
        uintptr_t begin=(uintptr_t)tcg_ctx->code_gen_buffer;
        size_t size=tcg_ctx->code_gen_buffer_size;
        p=vine_label(p," JIT_BASE=",begin);p=vine_label(p," JIT_SIZE=",size);
        /* Read instructions only inside QEMU's known readable JIT mapping. */
        if (!(pc&3) && size>=16 && pc>=begin && pc-begin<=size-16) {
            p=vine_label(p," JIT_OFFSET=",pc-begin);
            const volatile uint32_t *code=(const volatile uint32_t *)pc;
            for (int i=0;i<4;i++) p=vine_label(p," OP=",code[i]);
        }
    }
    *p++='\n';
    ssize_t ignored=write(STDERR_FILENO,line,(size_t)(p-line));(void)ignored;
    _exit(128+sig);
}
static void vine_install_crash_handler(void) {
    Dl_info origin;
    if (dladdr((void *)&vine_install_crash_handler,&origin)) vine_elf_base=(uintptr_t)origin.dli_fbase;
    struct sigaction action={0};
    action.sa_sigaction=vine_sigill;action.sa_flags=SA_SIGINFO|SA_RESETHAND;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGILL,&action,NULL)<0) {
        const char error[]="VINE_SIGILL diagnostic installation failed\n";
        ssize_t ignored=write(STDERR_FILENO,error,sizeof(error)-1);(void)ignored;
    }
}
#else
static inline void vine_install_crash_handler(void) {}
#endif
