# Standalone ARM32 Android engine

This backend runs on an ARM64 Android host (API 28+) using software CPU translation. It needs no KVM, root, Shizuku, namespace or mount permissions. It is separate from the older PC/ISO experiment.

* CPU/board: https://github.com/aarch64-android-emulator/aarch64-qemu, revision `0582a5f95da065a431f83ab6a46ecde11410a3f1` (QEMU 2.12 fork).
* Build Makefile: QEMU tag `v2.12.0`, replacing the unrelated generated Makefile committed in that fork.
* All tracked CPU changes are in `engine.patch`. Additional source files here implement the local GLES pipe and public Android AAudio output. `goldfish_pipe.h` retains its AOSP copyright/license. Engine and adapter: GPLv2 or later; see COPYING and individual file headers.
* Guest: official API22 ARM32 default SDK image, plus the Ranchu kernel extracted directly from the official API22 r26 archive at runtime. No Android ROM/kernel or user application is distributed in the APK.
* GLES: public NDK EGL/GLES bridge; see `../arm32-renderer`. The guest EGL fix is in `../android5-arm32/egl-empty-config.patch` and applied only to the checksum-verified image.

## Build

Host requirements: Linux x86-64, GCC/G++, make, patch, git, flex, bison, pkg-config, patchelf, Python 3 and a working Python 2.7 command (required by the historical QEMU configure script). NDK `28.2.13676358`. The existing `scripts/prepare_qemu.py` documents/downloads the checksum-pinned Termux packages used for the dependency closure (`../qemu/packages.json`, `../qemu/bundle.json`). Extract glib, libpixman, libffi, zlib, libandroid-support, libiconv and pcre2 packages with `dpkg-deb -x package.deb DEPS_ROOT`; the resulting prefix is `DEPS_ROOT/data/data/com.termux/files/usr`. Run the existing preparation script first to install the shared library closure in the app's jniLibs directory.

```
python3 scripts/build_arm32_native.py \
  --work /path/to/fresh-build-directory \
  --ndk /path/to/android-sdk/ndk/28.2.13676358 \
  --python2 /path/to/python2.7 \
  --deps-root /path/to/extracted-termux-root
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The builder checks out the exact CPU revision, applies the provided patch, compiles the NDK CPU executable and GLES JNI library, and resolves the pinned shared libraries. It uses the same NDK STL as the app. It does not download proprietary GPU drivers. Keep the dependencies' license notices when distributing a build.

Important fixes: null Android proxy hooks in standalone SLIRP teardown; absent async-sync service/device aliasing ARM exception vectors; Android openpty portability; GLES connector/socket wakeups; configurable framebuffer size. Disk insertion is deliberately reversed to match this fork's FDT enumeration. See `docs/ARM32_GUEST.md` for validation scope.
