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

## Remaining phone implementation

VineOS currently packages an i386 system emulator only. A phone-compatible ARM64-host engine with the required Goldfish devices and GLES 2.0 bridge must be built and integrated before this image can run inside VineOS. The image, kernel, emulated board and graphics transport form one compatible set. Renaming an executable, replacing the CPU flag, or swapping the ISO is insufficient. Phone rendering, input, guest APK import, writable storage and sound must then be verified separately.
