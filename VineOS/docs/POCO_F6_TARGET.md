# POCO F6 target: fixed requirements

Sources checked on 2026-11-02: https://www.po.co/global/product/poco-f6/specs/ and the user's existing VineOS diagnostic screenshots. This replaces guessing the target architecture from generic Android emulator examples.

## Known hardware and actual OS

- Device reports Xiaomi 24069PC21G (POCO F6).
- Official processor: Snapdragon 8s Gen 3, 1x Cortex-X4 at 3.0 GHz, 4x Cortex-A720 at 2.8 GHz, 3x Cortex-A520 at 2.0 GHz. Adreno GPU.
- Official global variants: 8 GB RAM / 256 GB storage or 12 GB / 512 GB; LPDDR5X and UFS 4.0. Actual RAM was not measured by the shared probe; do not assume 16 GB from the phrase Android 16.
- Installed OS: Android 16/API36, kernel 6.1.138-android14-11, as shown by the user's diagnostic output.
- Actual app ABI list in that output: arm64-v8a only. An ordinary app cannot directly load the supplied armeabi/armeabi-v7a libandengine.so on this installation.
- Ordinary app namespace probe: unshare EPERM. Shizuku is connected as shell UID2000 and its namespace test is also blocked. Do not repeat these probes as a route to a container backend.
- The packaged ARM64-host QEMU executable and native RFB display already ran the custom PC boot sector on this phone. This establishes basic executable launch/display, not Android ARM guest support.

## Fixed implementation direction

1. Keep the VineOS shell/UI; build an ARM64 Android host executable. Host ABI and guest ABI are separate choices.
2. Provide a 32-bit ARM Android 5.1.1 guest exposing armeabi-v7a and armeabi, including an old linker compatible with the supplied native library's text relocations.
3. Use software CPU translation from ARM32 to ARM64. Do not assume native execution or hardware acceleration merely because both ISAs are called ARM. Do not depend on KVM: usable KVM access has not been established for the app.
4. Select the kernel, board devices, Android HALs and graphics transport as a compatible set. The confirmed SDK reference is API22/Goldfish; a generic phone firmware or upstream QEMU virt image is not a substitute for that set.
5. Start small: one virtual CPU, 512–768 MiB guest RAM and a low-resolution portrait display. These are initial engineering settings, not a performance guarantee. Increasing emulated cores or matching the phone's full display resolution does not establish compatibility.
6. Require working GLES 2.0, textures/shaders, and correct EGL configuration queries/fallback behavior. The game's multisampling failure occurred in the Linux-host emulator; it is not evidence that the POCO's GPU is too weak. The host GPU is accessible to a guest only through an implemented graphics bridge.
7. Add guest APK import, writable data and audio to the resulting phone engine; then test the original supplied APK. Do not publish another ARM-labelled APK that still embeds only the i386 engine.

## Limits of specification-based selection

Public specifications cannot establish an app's SELinux permissions, access to /dev/kvm, the running system's page size, exact EGL configuration availability, or guest driver compatibility. Use known device probe results for permissions. Future diagnostics should collect remaining properties once in a structured report rather than repeatedly asking the user to try ROMs. Android 16 does not by itself establish whether the installed system uses 4 KiB or 16 KiB memory pages.

## Current gate

ARM32 Android 5.1.1 boots to a desktop in the host reference emulator; the unchanged APK installs with primaryCpuAbi=armeabi-v7a. Gameplay fails at EGLCONFIG_FALLBACK. No compatible ARM64 Android-host Goldfish engine has yet been integrated into VineOS. These are the remaining software/graphics implementation issues; nominal phone RAM or CPU core count does not resolve them.
