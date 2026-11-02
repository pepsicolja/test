# Android 5 without root: current status

Target: POCO F6, Android 16, no root. Base: Hexadecinull/VineOS f72a8cc. The user requires old ARM32 apps, with the supplied Flappy Bird 1.3 APK as the first fixture.

The original namespace-container path was rejected both as an ordinary app and through Shizuku shell privileges (unshare EPERM). Full-system software emulation avoids these host namespace operations.

## Existing phone prototype

The user confirmed the custom BIOS boot-sector text running on POCO F6 through the native RFB/Canvas viewer. Version 0.1.4 fixes viewport clipping and exposes Android-compatible x86 CPU features. The Android-x86 5.1 Live ISO did not reach a visible desktop in the tested configuration. This PC guest is not the requested ARM32 runtime.

## ARM32 investigation

The official API22 ARM image (Android 5.1.1, armeabi-v7a/armeabi) has now booted to a visible desktop on the **Linux host**, using the historical Google Emulator Goldfish engine. ADB reports sys.boot_completed=1. Host Mesa llvmpipe through the emulator's GLES translator provides OpenGL ES 2.0.

The supplied APK installs and the package manager selects primaryCpuAbi=armeabi-v7a. Its native library loads, but the game then crashes with EGLCONFIG_FALLBACK failed while AndEngine selects a multisampling configuration. Gameplay is not verified.

See ARM32_GUEST.md, validation/flappybird-apk.md and validation/arm32-host-result.md for reproducible commands and evidence. The checked-in host smoke test catches an activity that starts and subsequently leaves the foreground. No APK or proprietary image is redistributed.

## Remaining work

The phone APK still contains only the i386 system emulator. Porting/building an ARM64-host Android engine with matching Goldfish devices and the required GLES bridge is necessary; a generic upstream ARM QEMU binary cannot directly use this SDK image. The graphics configuration incompatibility must also be resolved. Existing phone UI, foreground service, display and input code can be reused where the new engine permits. Writable storage, guest APK import, sound and performance need implementation and verification on the resulting phone backend.

No ARM32 Android guest or Flappy Bird gameplay has been demonstrated inside VineOS on POCO F6.
