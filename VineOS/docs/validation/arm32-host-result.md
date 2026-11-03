# ARM32 engine validation, 2026-11-03

## Confirmed on this server

The original supplied Flappy Bird 1.3 APK (SHA256 `a3e6958ce2100966f4e207778e4cdbe72788214148c7f4bfd042ba365498deb3`) is unchanged. No game code, resources, ABI libraries or APK signature was modified.

1. Google's historical Goldfish Linux engine boots the official Android 5.1.1 API22 ARM32 image. The original game initially failed in its AndEngine EGL configuration fallback.
2. The guest EGL driver incorrectly returned EGL_FALSE for an empty successful config query. The source patch changes `<= 0` to `< 0`; a guarded one-byte Thumb patch applies that exact behavior to the verified image. Twelve Unicorn instruction tests preserve negative and positive behavior and fix zero. After this change, the original game reached its menu, Get Ready and Game Over screens, and responded to taps.
3. The new standalone Ranchu engine, built from the same source as the Android CPU payload, also boots this userspace with the pinned Ranchu kernel and our GLES pipe bridge. A Linux-host build was used here; graphics use the same stream decoder and a host-only Mesa/SDK translator adapter.
4. The actual Kotlin GuestAdb class used in the app queried `armeabi-v7a`, pushed a 28 KB file, read it back byte-for-byte and deleted it. A persistent serialized transport is necessary for the old daemon.
5. That same Kotlin transport pushed the original 915558-byte APK and ran `pm install -r`: Success. The game's activity started with Status: ok; after 15 seconds the resumed foreground activity was `com.dotgears.GameActivity`. AndEngine created its GLES2 scene using RGB565/depth24/stencil8 without the old fallback exception.
6. Native socket tests exercise the real GLES pipe with ASAN/UBSAN: fragmented connector, binary reads/writes, wakeups, backpressure, EOF and invalid service/port rejection pass.

The new engine's null standalone SLIRP proxy callback crash was reproduced under GDB and fixed. Abrupt closing of the ADB handshake no longer terminated the engine. Standalone async sync-device creation was disabled: this fork has no MMIO map for it and its Android fence callbacks are not installed. Two guest Cortex-A15 CPUs/TCG and 768 MiB were used in the final transport/game launch check. Cold app startup/installation took several minutes on the shared server.

## What this does not establish

This is not a test of the final APK running on the Poco F6. The Android ARM64 CPU executable and public-NDK Surface/GLES JNI module are cross-compiled; actual Adreno rendering, app lifecycle/Surface reattachment, sound and speed still need a physical-device check. The new Ranchu check establishes installation and a foreground rendered game scene, not long-play FPS or audible audio. Earlier Goldfish gameplay evidence does not substitute for phone verification.

The Android ROM/kernel and user APK are downloaded or selected by the user at runtime, not bundled. Writable userdata is retained between launches. The app refuses to prepare a new guest over an existing data directory.

## First physical-device startup report

On the Poco F6/Android 16, the public Surface renderer initialized with Qualcomm Adreno 735 (OpenGL ES-CM 1.1 reported for the compositor context). The guest CPU then exited before boot because its VNC input initialization could not locate `en-us`. Version 0.2.2 copies the packaged QEMU keymaps into a private runtime directory on each start and explicitly passes `-L` to the ARM engine. A real host-engine VNC initialization check using those exact packaged keymaps passes. This report does not establish Android guest boot or gameplay on the phone.

The output-only AAudio backend also incorrectly declared a nonzero capture voice size while supporting zero capture voices. The declaration is corrected to zero; the backend does not request microphone input.

## Android ARM64 SIGILL investigation (0.2.3 diagnostics)

The 0.2.2 phone report progresses into Linux 3.10 boot and then the host engine terminates with exit 132 (normally SIGILL). It also records CPU1 boot failure -19; this is separate evidence that the two-vCPU profile is not supported by the current guest/kernel combination. It is not a measurement of slow gameplay or a verified cause of SIGILL.

The diagnostic profile uses one vCPU and single-threaded TCG. The Android AArch64 engine installs a bounded SIGILL handler that writes PC/LR/SP, CPU ELF base, TCG region bounds and four instruction words when PC is inside its known readable JIT region. It then exits immediately; it does not resume execution or claim to fix the illegal instruction. The real handler is exercised with a deliberate SIGILL in a disposable Linux subprocess, validating its report fields and exit status. Physical-device confirmation and the actual fault address are still required.
