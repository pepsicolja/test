#!/usr/bin/env python3
"""Fix eglChooseConfig's empty-result handling in the pinned API22 ARM image.

Requires dissect.extfs. Stop every VM using the image before applying/restoring.
Only the exact inspected driver is accepted; no APK or CPU code is modified.
"""
import argparse
import hashlib
from pathlib import Path

from dissect.extfs.extfs import ExtFS

ORIGINAL = "e64527585cb9f77f41a5a583e96f3de0eafd94a53e7637eb12162b5d8ab8aa2f"
FIXED = "2cba7d8d8f24b5fa58a70e775777564f6fd20793054a859258c3755d8096d296"
OFFSET = 0x66C2
# Thumb ITE LE / MOVLE 0 / MOVGT 1 becomes ITE LT / MOVLT 0 / MOVGE 1.
BEFORE = bytes.fromhex("d4bf00200120")
AFTER = bytes.fromhex("b4bf00200120")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true")
    mode.add_argument("--restore", action="store_true")
    args = parser.parse_args()
    if not args.image.is_file():
        parser.error("Expected an offline regular ext4 system.img file")
    mutate = args.apply or args.restore
    expected, target = (FIXED, ORIGINAL) if args.restore else (ORIGINAL, FIXED)
    before, after = (AFTER, BEFORE) if args.restore else (BEFORE, AFTER)
    with args.image.open("r+b" if mutate else "rb") as stream:
        fs = ExtFS(stream)
        node = fs.get("/lib/egl/libEGL_emulation.so")
        original = node.open().read()
        digest = hashlib.sha256(original).hexdigest()
        if digest == target:
            print("Driver already in requested state:", target)
            return
        if digest != expected or original[OFFSET:OFFSET + len(before)] != before:
            raise RuntimeError("Unknown EGL driver; refusing to patch")
        patched = bytearray(original)
        patched[OFFSET:OFFSET + len(after)] = after
        if hashlib.sha256(patched).hexdigest() != target:
            raise RuntimeError("Result checksum mismatch")
        remaining = OFFSET
        for block, count in node.dataruns():
            length = count * fs.block_size
            if remaining < length:
                if block is None:
                    raise RuntimeError("Unexpected sparse driver extent")
                disk_offset = block * fs.block_size + remaining
                break
            remaining -= length
        else:
            raise RuntimeError("Driver instruction offset is outside the file")
        if mutate:
            stream.seek(disk_offset)
            if stream.read(1) != before[:1]:
                raise RuntimeError("Image changed during inspection")
            stream.seek(disk_offset)
            stream.write(after[:1])
            stream.flush()
            if hashlib.sha256(node.open().read()).hexdigest() != target:
                raise RuntimeError("Post-write driver verification failed")
        print("Applied" if mutate else "Dry run:", "EGL empty-result fix; driver SHA256", target)


if __name__ == "__main__":
    main()
