#!/usr/bin/env python3
"""Prepare the official API22 ARM32 Goldfish image for host compatibility tests.

This is not a bootable image for upstream QEMU's virt/versatile boards and is
not a phone runtime. Images remain separate from the VineOS APK and repository.
"""
import argparse
import hashlib
import io
import os
from pathlib import Path
import urllib.request
import zipfile

URL = "https://dl.google.com/android/repository/sys-img/android/armeabi-v7a-22_r02.zip"
SIZE = 194596267
# Published in Google's sys-img2-1.xml repository metadata.
SHA1 = "2114ec015dbf3a16cbcb4f63e8a84a1b206a07a1"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    names = ("system.img", "userdata.img", "kernel-qemu", "ramdisk.img",
             "source.properties", "build.prop", "hardware.ini", "NOTICE.txt")
    if any((args.out / name).exists() for name in names):
        parser.error("Use a fresh directory; existing images/user data are never overwritten")
    print("Downloading official Android 5.1.1 ARM32 image", flush=True)
    with urllib.request.urlopen(URL, timeout=60) as response:
        data = response.read(SIZE + 1)
    if len(data) != SIZE or hashlib.sha1(data).hexdigest() != SHA1:
        raise RuntimeError("Official archive size/checksum mismatch")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in names:
            entry = archive.getinfo("armeabi-v7a/" + name)
            target = args.out / name
            temporary = args.out / (name + ".part")
            checksum = hashlib.sha256()
            try:
                with archive.open(entry) as source, temporary.open("xb") as dest:
                    while chunk := source.read(65536):
                        checksum.update(chunk)
                        # Preserve sparse ext4 images instead of allocating zero blocks.
                        if chunk.strip(b"\0"):
                            dest.write(chunk)
                        else:
                            dest.seek(len(chunk), os.SEEK_CUR)
                    dest.truncate(entry.file_size)
                temporary.rename(target)
            finally:
                temporary.unlink(missing_ok=True)
            print(name, entry.file_size, checksum.hexdigest(), flush=True)


if __name__ == "__main__":
    main()
