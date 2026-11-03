#!/usr/bin/env python3
"""Exercise the real diagnostic SIGILL handler in a disposable Linux subprocess."""
from pathlib import Path
import subprocess,tempfile
repo=Path(__file__).resolve().parents[1]
source=r'''
#define _GNU_SOURCE
#define VINE_CRASH_TEST
#include <stddef.h>
typedef struct { void *code_gen_buffer; size_t code_gen_buffer_size; } TCGContext;
static TCGContext state;
static TCGContext *tcg_ctx=&state;
#include "vine_crash.h"
__attribute__((naked,aligned(16))) static void fault(void) {
    __asm__("ud2; nop; nop; nop; nop; nop; nop; nop; nop; nop; nop; nop; nop; nop; nop; nop");
}
int main(void) {
    state.code_gen_buffer=(void*)fault;state.code_gen_buffer_size=16;
    vine_install_crash_handler();fault();return 0;
}
'''
with tempfile.TemporaryDirectory(prefix='vine-sigill-') as folder:
 root=Path(folder);(root/'probe.c').write_text(source)
 subprocess.run(['cc','-O2','-I'+str(repo/'third_party/arm32-engine'),str(root/'probe.c'),'-ldl','-o',str(root/'probe')],check=True)
 result=subprocess.run([str(root/'probe')],capture_output=True,text=True,timeout=5)
 assert result.returncode==132,(result.returncode,result.stderr)
 for marker in ['VINE_SIGILL code=',' PC=',' LR=',' SP=',' CPU_ELF_BASE=',' JIT_BASE=',' JIT_SIZE=',' JIT_OFFSET=',' OP=']:
  assert marker in result.stderr,(marker,result.stderr)
 print('Real SIGILL handler: bounded crash report, instruction capture and exit 132: PASS')
