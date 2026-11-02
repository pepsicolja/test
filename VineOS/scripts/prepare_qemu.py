import urllib.request,hashlib,tarfile,io,pathlib,json,re,concurrent.futures,subprocess
repo = pathlib.Path(__file__).resolve().parents[1]
root = repo / '.qemu-cache'
root.mkdir(parents=True, exist_ok=True)
marker = root / '.generated-qemu-cache'
if (root / 'usr').exists():
    if not marker.exists():
        raise RuntimeError('Refusing to replace an unrecognized cache directory')
    __import__('shutil').rmtree(root / 'usr')
marker.touch()
records = json.loads((repo / 'third_party/qemu/packages.json').read_text())
blocks = {entry['Package']: entry for entry in records}
seen = set(blocks)
print('Downloading checksum-pinned Termux packages:', len(seen), flush=True)
def get(n):
 d=blocks[n];p=root/(n+'.deb')
 if p.exists() and hashlib.sha256(p.read_bytes()).hexdigest() != d['SHA256']:
  p.unlink()
 if not p.exists():urllib.request.urlretrieve('https://packages.termux.dev/apt/termux-main/'+d['Filename'],p)
 b=p.read_bytes();assert hashlib.sha256(b).hexdigest()==d['SHA256'],n
 pos=8
 while pos+60<len(b):
  h=b[pos:pos+60];size=int(h[48:58]);name=h[:16].decode().strip().rstrip('/');data=b[pos+60:pos+60+size];pos+=60+size+(size%2)
  if name.startswith('data.tar'):
   with tarfile.open(fileobj=io.BytesIO(data),mode='r:*') as t:
    for m in t:
     if not m.isfile():continue
     s=m.name.lstrip('./');prefix='data/data/com.termux/files/usr/'
     if not s.startswith(prefix):continue
     rel=s[len(prefix):]
     if '..' in pathlib.PurePosixPath(rel).parts:
      raise RuntimeError('Unsafe package path: '+rel)
     if rel.startswith(('lib/','share/qemu/','share/doc/','share/licenses/')) or rel=='bin/qemu-system-i386':
      dest=root/'usr'/rel;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(t.extractfile(m).read())
 return n
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
 for n in pool.map(get,sorted(seen)):print(n,flush=True)




idx = {}
for p in sorted((root / 'usr/lib').rglob('*')):
    if not p.is_file() or p.open('rb').read(4) != b'\x7fELF':
        continue
    info = subprocess.check_output(['readelf', '-d', str(p)], text=True)
    match = re.search(r'\(SONAME\).*\[(.*?)\]', info)
    if match:
        idx[match[1]] = str(p)
    idx[p.name] = str(p)
seen_libs = {}
todo = [root / 'usr/bin/qemu-system-i386']
system = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libz.so', 'libEGL.so', 'libGLESv2.so', 'libOpenSLES.so'}
while todo:
    p = todo.pop()
    if str(p) in seen_libs:
        continue
    info = subprocess.check_output(['readelf', '-d', str(p)], text=True)
    dependencies = re.findall(r'\(NEEDED\).*\[(.*?)\]', info)
    seen_libs[str(p)] = dependencies
    for dependency in dependencies:
        if dependency in system:
            continue
        resolved = idx.get(dependency) or idx.get(pathlib.Path(dependency).name)
        if not resolved:
            raise RuntimeError('Missing dependency: ' + dependency)
        todo.append(pathlib.Path(resolved))
(root / 'closure.json').write_text(json.dumps(seen_libs, indent=2))

from pathlib import Path
import json,subprocess,re,shutil,struct,hashlib
closure=json.loads((root/'closure.json').read_text());out=repo/'app/src/main/jniLibs/arm64-v8a';out.mkdir(parents=True,exist_ok=True)
entry=str(root/'usr/bin/qemu-system-i386');mapping={};names={}
for file in closure:
 p=Path(file);name='libvine_qemu_i386.so' if file==entry else 'libvq_'+re.sub('[^a-zA-Z0-9_]','_',p.name)+'.so'
 mapping[file]=name;names[p.name]=name
 s=subprocess.check_output(['readelf','-d',file],text=True)
 m=re.search(r'\(SONAME\).*\[(.*?)\]',s)
 if m:names[m[1]]=name
for file,deps in closure.items():
 dest=out/mapping[file];shutil.copy2(file,dest)
 args=['patchelf','--remove-rpath']
 if file!=entry:args+=['--set-soname',mapping[file]]
 for dep in deps:
  target=names.get(dep) or names.get(Path(dep).name)
  if target:args+=['--replace-needed',dep,target]
 subprocess.run(args+[str(dest)],check=True);dest.chmod(0o755)
assets=repo/'app/src/main/assets/qemu';firmware=assets/'firmware';firmware.mkdir(parents=True,exist_ok=True)
for name in ['bios-256k.bin','bios.bin','vgabios-stdvga.bin','kvmvapic.bin']:
 shutil.copy2(root/'usr/share/qemu'/name,firmware/name)
code=bytes.fromhex('fa31c08ed88ed0bc007cfbb80300cd10be')+struct.pack('<H',0x7c00+37)+bytes.fromhex('ac84c07409b40ebb0700cd10ebf2fbf4ebfc')
assert len(code)==37
message=b'VINEOS QEMU TEST OK\r\n\r\nGuest x86 code is running inside QEMU.\r\nNo root. No Shizuku.\r\n\r\nThis is a boot test, not Android yet.\r\n\x00'
image=bytearray(1474560);image[:len(code+message)]=code+message;image[510:512]=b'\x55\xaa';(assets/'demo.img').write_bytes(image)
manifest={'executable':mapping[entry],'libraries':sorted(mapping.values()),'firmware':['bios-256k.bin','bios.bin','vgabios-stdvga.bin','kvmvapic.bin'],'demo_sha256':hashlib.sha256(image).hexdigest(),'files':{mapping[k]:{'original':str(Path(k).relative_to(root/'usr')),'sha256':hashlib.file_digest(open(out/mapping[k],'rb'),'sha256').hexdigest()} for k in mapping}}
(repo/'third_party/qemu/bundle.json').write_text(json.dumps(manifest,indent=2))
print('Bundled',len(mapping),'ELFs, boot firmware and boot test')
# Check that all static dependencies resolve to packaged or Android NDK libraries.
system={'libc.so','libm.so','libdl.so','liblog.so','libandroid.so','libz.so','libEGL.so','libGLESv2.so','libOpenSLES.so'}
for name in mapping.values():
 s=subprocess.check_output(['readelf','-d',str(out/name)],text=True)
 for dep in re.findall(r'\(NEEDED\).*\[(.*?)\]',s):assert dep in system or (out/dep).is_file(),(name,dep)
print('All NEEDED libraries resolve')
