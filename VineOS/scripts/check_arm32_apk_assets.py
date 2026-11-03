#!/usr/bin/env python3
"""Verify the actual packaged guest cache asset, including AAPT name handling."""
import argparse,gzip,hashlib,json,zipfile
p=argparse.ArgumentParser(description=__doc__);p.add_argument('apk');args=p.parse_args()
with zipfile.ZipFile(args.apk) as apk:
 info=json.loads(apk.read('assets/arm32/manifest.json'))['cache.img']
 # This must match AssetManager.open in ArmFiles. AAPT removes .gz suffixes.
 data=apk.read('assets/arm32/cache.img.bin')
 digest=hashlib.sha256();size=0
 import io
 with gzip.GzipFile(fileobj=io.BytesIO(data)) as source:
  while True:
   chunk=source.read(65536)
   if not chunk:break
   size+=len(chunk);digest.update(chunk)
 assert size==info['size'] and digest.hexdigest()==info['sha256'], 'Packaged cache image checksum mismatch'
 assert 'assets/arm32/licenses/NOTICE.txt' in apk.namelist()
 assert 'assets/qemu/firmware/keymaps/en-us' in apk.namelist(), 'Missing QEMU VNC keymap'
 assert len([n for n in apk.namelist() if n.startswith('assets/qemu/firmware/keymaps/')]) >= 40
 print('Packaged ARM32 asset path, gzip stream, image size and SHA256: PASS')
