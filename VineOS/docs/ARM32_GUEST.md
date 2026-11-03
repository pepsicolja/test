# Android 5 ARM32 direction

The goal is old ARM32 applications on POCO F6 / Android 16 without root, keeping the VineOS interface. The previous x86 ISO prototype is not the target runtime.

## Selected compatibility fixture

Google's default API22 revision 2 image is Android 5.1.1, build LMY48X, with `armeabi-v7a,armeabi` and no 64-bit guest ABI. Its kernel is for the Android Emulator's **Goldfish** board. It cannot simply replace a PC ISO, or boot on upstream QEMU's `virt`/`versatilepb` boards. Limbo's normal upstream ARM board support does not establish support for Goldfish or its GLES transport.

The image URL, published archive checksum and independently computed component hashes are pinned in `third_party/android5-arm32/image.json`. Image binaries and the user-supplied APK are not included in the repository or distributed in VineOS.

```sh
python3 scripts/prepare_android5_arm32.py --out /path/to/fresh-image-directory
```

The preparation command validates the official archive and writes sparse images. It refuses to overwrite existing images, including writable guest data. Image use is subject to the Android SDK image license; this host investigation does not grant redistribution rights.

## Host launch investigation

The matching historical host engine is Google's Linux SDK Tools 25.2.5 (`https://dl.google.com/android/repository/tools_r25.2.5-linux.zip`). The relevant executable is `tools/emulator64-arm`, together with its lib64 renderer libraries, bin64 filesystem utilities and tools/lib/ca-bundle.pem. **This is a Linux x86-64 executable, not a binary that can be installed on an ARM64 Android phone.**

On this host the CPU must explicitly be `cortex-a8`; the default model repeatedly failed during kernel decompression. Goldfish's legacy engine uses one guest core. A headless X server was needed for graphics initialization. Bundled Mesa/SwiftShader configurations failed here; host Mesa llvmpipe with `-gpu host` and `LIBGL_ALWAYS_SOFTWARE=1` initialized GLES 2.0 successfully.

With Xvfb already running on :99 and the engine installed at `$ENGINE`:

```sh
DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 \
LD_LIBRARY_PATH="$ENGINE/lib64/qt/lib:$ENGINE/lib64" \
"$ENGINE/emulator64-arm" \
  -sysdir "$IMAGE" -data "$IMAGE/userdata.img" \
  -no-window -no-audio -no-snapshot -no-cache \
  -gpu host -memory 512 -skin 320x480 -partition-size 800 \
  -port 5580 -no-boot-anim -show-kernel -qemu -cpu cortex-a8
```

These commands operate on the host. They are not a launch command for the current phone APK. `userdata.img` becomes writable state; preserve it for later launches rather than extracting the template over it.

## App check

```sh
python3 scripts/check_flappy_arm32.py \
  --adb /path/to/adb --serial emulator-5580 \
  --apk /path/to/user-supplied-flappy.apk --out /path/to/test-results
```

The check waits for `sys.boot_completed=1`, verifies the guest and installed package use ARM32, installs the exact inspected APK, launches its activity, records graphics/logcat output and captures a PNG. It does not automatically claim playable speed, audio or a working phone backend. See `docs/validation/flappybird-apk.md` for the APK requirements.

## Integrated Android backend (0.2.0 preview)

Settings → Экспериментальный движок → Android 5.1 ARM32 opens the new backend. It uses an ARM64-host, ARM32-guest QEMU Ranchu build with software TCG, a public Android Surface/EGL/GLES bridge, loopback input, a foreground service and private persistent guest disks. It needs neither root nor Shizuku. The old PC ISO backend is retained in source for reference.

Tap «Подготовить Android» once (186 MiB archive plus a 3 MB kernel archive prefix), then «Запустить». After Android reports ready, choose «Установить APK» and select an original ARM32 APK. Installation happens inside the Android 5 guest; Android 16's host installer is not used for that APK. Back/Home buttons control the guest. Data is retained; do not clear the app's storage if you want to preserve it.

The default API22 archive contains Ranchu userspace HALs but only a Goldfish kernel. The app separately downloads the verified Ranchu kernel from the official API22 r26 archive. It patches the guest's init script to expose its local ADB import service, and applies the guarded EGL compatibility fix to the verified system image. Nothing is patched in selected application APKs. ROM and kernel are not embedded in the distributable app.

Native build instructions and pinned source: `third_party/arm32-engine/README.md`, `third_party/arm32-renderer/source.json`, `scripts/build_arm32_native.py`. Current validation and its physical-device limits: [arm32-host-result.md](validation/arm32-host-result.md). The original game now installs and reaches its resumed game activity through the actual app transport in the new server-host engine test.
