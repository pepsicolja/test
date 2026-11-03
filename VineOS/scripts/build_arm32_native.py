#!/usr/bin/env python3
"""Build the pinned ARM32 guest engine and public-NDK GLES bridge on Linux.

Requires git, patch, make, gcc/g++, pkg-config, Python 2.7, patchelf,
NDK 28.2 and the extracted Termux dependencies in third_party/qemu/packages.json.
See third_party/arm32-engine/README.md. No ROM or user APK is bundled.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import urllib.request

REPO = Path(__file__).resolve().parents[1]
REVISION = '0582a5f95da065a431f83ab6a46ecde11410a3f1'


def run(*args, cwd=None, env=None):
    subprocess.run([str(a) for a in args], cwd=cwd, env=env, check=True)


def needed(path):
    return subprocess.check_output(['patchelf', '--print-needed', str(path)], text=True).split()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--work', type=Path, required=True)
    ap.add_argument('--ndk', type=Path, required=True)
    ap.add_argument('--python2', type=Path, required=True)
    ap.add_argument('--deps-root', type=Path, required=True,
                    help='root containing data/data/com.termux/files/usr')
    ap.add_argument('--jobs', type=int, default=2)
    a = ap.parse_args()
    work, ndk, deps = a.work.resolve(), a.ndk.resolve(), a.deps_root.resolve()
    work.mkdir(parents=True, exist_ok=True)
    src, build, render = work/'qemu', work/'build', work/'renderer'
    if not src.exists():
        run('git', 'init', src)
        run('git', 'remote', 'add', 'origin', 'https://github.com/aarch64-android-emulator/aarch64-qemu.git', cwd=src)
        run('git', 'fetch', '--depth=1', 'origin', REVISION, cwd=src)
        run('git', 'checkout', '--detach', 'FETCH_HEAD', cwd=src)
        # The fork committed an unrelated generated automake Makefile.
        makefile = urllib.request.urlopen('https://raw.githubusercontent.com/qemu/qemu/v2.12.0/Makefile', timeout=60).read()
        assert hashlib.sha256(makefile).hexdigest() == '520515a9a0ce3c7e23bc6bb8d7d7cad0f80e1972e4780419a066b65a25771481'
        (src/'Makefile').write_bytes(makefile)
        run('patch', '-p1', '-i', REPO/'third_party/arm32-engine/engine.patch', cwd=src)
        for name, dest in [('vine_gles_pipe.c','hw/misc'), ('goldfish_pipe.h','include/hw/misc'), ('vine_aaudio.c','audio'), ('vine_crash.h','tcg')]:
            shutil.copy2(REPO/'third_party/arm32-engine'/name, src/dest/name)
        run('git', 'config', 'submodule.dtc.url', 'https://github.com/dgibson/dtc.git', cwd=src)
        run('git', 'config', 'submodule.ui/keycodemapdb.url', 'https://gitlab.com/qemu-project/keycodemapdb.git', cwd=src)
        run('git', 'submodule', 'update', '--init', 'dtc', 'ui/keycodemapdb', cwd=src)
    assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=src,text=True).strip() == REVISION
    build.mkdir(exist_ok=True)
    tools = ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
    env = os.environ.copy()
    env.update(PKG_CONFIG='/usr/bin/pkg-config', PKG_CONFIG_SYSROOT_DIR=str(deps),
               PKG_CONFIG_LIBDIR=str(deps/'data/data/com.termux/files/usr/lib/pkgconfig'))
    run('bash', src/'configure', '--python='+str(a.python2.resolve()), '--target-list=arm-softmmu',
        '--cpu=aarch64', '--cross-prefix='+str(tools/'llvm-'), '--cc='+str(tools/'aarch64-linux-android28-clang'),
        '--cxx='+str(tools/'aarch64-linux-android28-clang++'), '--host-cc=gcc', '--disable-werror',
        '--disable-debug-info', '--disable-docs', '--disable-tools', '--audio-drv-list=', '--disable-user',
        '--disable-gtk', '--disable-sdl', '--disable-capstone', '--disable-linux-aio', '--disable-libiscsi',
        '--disable-rbd', '--disable-spice', '--disable-vnc-sasl', '--disable-vnc-jpeg', '--disable-vnc-png',
        '--disable-curl', '--with-coroutine=sigaltstack', '--extra-ldflags=-Wl,--allow-shlib-undefined', cwd=build, env=env)
    config = build/'config-host.mak'
    text = config.read_text().replace('-lutil', '')
    text += '\nCONFIG_VINE_ANDROID_AUDIO=y\nlibs_softmmu += -laaudio\n'
    config.write_text(text)
    run('make', '-j'+str(a.jobs), 'subdir-arm-softmmu', cwd=build, env=env)

    if not render.exists():
        info = json.loads((REPO/'third_party/arm32-renderer/source.json').read_text())
        archive = work/'renderer-source.tar.gz'
        with urllib.request.urlopen(info['url'],timeout=60) as response, archive.open('wb') as out:
            shutil.copyfileobj(response, out)
        assert hashlib.sha256(archive.read_bytes()).hexdigest() == info['sha256']
        render.mkdir()
        with tarfile.open(archive) as tar:
            for entry in tar:
                parts = Path(entry.name).parts
                if len(parts)>2 and parts[1]=='host-opengl-render' and entry.isfile():
                    rel=Path(*parts[2:]); assert '..' not in rel.parts
                    target=render/rel; target.parent.mkdir(parents=True,exist_ok=True)
                    target.write_bytes(tar.extractfile(entry).read())
        run('patch','-p1','-i',REPO/'third_party/arm32-renderer/renderer.patch',cwd=render)
    for name in ['NativeAndroidWindow.cpp','BridgeJni.cpp']:
        shutil.copy2(REPO/'third_party/arm32-renderer'/name,render/name)
    excluded={'main.cpp','FramebufferNativeWindow.cpp','AndroidWindowSurface.cpp','NativeLinuxSubWindow.cpp','HostProbe.cpp','NativeHostWindow.cpp'}
    sources=[p for p in sorted(render.glob('*.cpp')) if p.name not in excluded]
    bridge=work/'libvine_gles_bridge.so'
    run(tools/'aarch64-linux-android28-clang++','-shared','-fPIC','-std=c++11','-O2','-DWITH_GLES2',
        '-I'+str(render),*sources,'-ldl','-llog','-landroid','-o',bridge)
    out=REPO/'app/src/main/jniLibs/arm64-v8a';out.mkdir(parents=True,exist_ok=True)
    # Reuse the existing pinned Termux closure; preserve the NDK STL used by JNI.
    old=json.loads((REPO/'third_party/qemu/bundle.json').read_text())
    root=deps/'data/data/com.termux/files/usr'
    names={}
    for name,record in old['files'].items():
        file=root/record['original']
        if not file.exists(): continue
        names[file.name]=name
        info=subprocess.check_output(['readelf','-d',str(file)],text=True)
        match=re.search(r'\(SONAME\).*\[(.*?)\]',info)
        if match:names[match[1]]=name
    names.pop('libc++_shared.so',None)
    extra=root/'lib/libgthread-2.0.so.0.9000.1'
    names['libgthread-2.0.so.0']='libva_gthread.so'
    for name,original in [('libvine_qemu_arm.so',build/'arm-softmmu/qemu-system-arm'),
                          ('libvine_gles_bridge.so',bridge),('libva_gthread.so',extra)]:
        target=out/name;shutil.copy2(original,target);target.chmod(0o755)
        run('patchelf','--remove-rpath',target)
        if name=='libva_gthread.so':run('patchelf','--set-soname',name,target)
        for dependency in needed(target):
            if dependency in names:run('patchelf','--replace-needed',dependency,names[dependency],target)
    platform={'libc.so','libm.so','libdl.so','liblog.so','libandroid.so','libaaudio.so','libc++_shared.so'}
    for name in ['libvine_qemu_arm.so','libvine_gles_bridge.so','libva_gthread.so']:
        for dependency in needed(out/name):
            assert dependency in platform or (out/dependency).is_file(), f'Unresolved {dependency}'
    print('Built ARM64-host/ARM32-guest CPU, GLES bridge and audio backend:',out)


if __name__=='__main__':main()
