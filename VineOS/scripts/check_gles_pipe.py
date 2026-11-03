#!/usr/bin/env python3
"""Run the real GLES pipe adapter against loopback TCP with mocked QEMU wakeups."""
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1] / "third_party/arm32-engine"
with tempfile.TemporaryDirectory(prefix="vine-gles-test-") as directory:
    tmp = Path(directory)
    for name in ("qemu", "hw/misc"):
        (tmp / name).mkdir(parents=True, exist_ok=True)
    (tmp / "qemu/osdep.h").write_text("""
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <limits.h>
#include <sys/socket.h>
#define g_new0(type, count) ((type *)calloc((count), sizeof(type)))
#define g_free free
""")
    (tmp / "qemu/typedefs.h").write_text("typedef struct QEMUFile QEMUFile;\n")
    (tmp / "qemu/main-loop.h").write_text(
        "void qemu_set_fd_handler(int, void (*)(void *), void (*)(void *), void *);\n")
    shutil.copy2(root / "goldfish_pipe.h", tmp / "hw/misc/goldfish_pipe.h")
    subprocess.run(["gcc", "-std=c11", "-D_GNU_SOURCE", "-g", "-O1",
                    "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
                    "-I" + str(tmp), "-I" + str(root),
                    str(root / "tests/gles_pipe_test.c"), "-o", str(tmp / "check")], check=True)
    subprocess.run([str(tmp / "check")], check=True, timeout=20)
