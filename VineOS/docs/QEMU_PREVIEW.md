# Full-system QEMU preview

## Phone test

Install VineOS-qemu-preview.apk over the previous diagnostic debug APK. Open Settings → Экспериментальный движок → QEMU без root → Пробный запуск.

Expected result: a guest BIOS screen, followed by `VINEOS QEMU TEST OK`. This is a real-mode x86 boot sector executed by a full-system QEMU process. It is not an Android boot. If it exits or the display does not connect, use Поделиться отчётом and capture the display; no root or Shizuku permission is requested by this backend.

After that test succeeds, stop it and use Скачать Android 5.1 (358 МБ), then Запустить ISO. The official Android-x86 5.1-rc1 image is streamed into private storage and checked against a pinned SHA-256. A custom ISO can instead be imported through the Android document picker. On the ISO boot menu, select **Live CD - VESA mode** using ↓ and Enter. A working Android desktop is not claimed until tested on the phone.

## Implemented

- Existing VineOS navigation/theme and container backend retained; experimental backend has a separate entry in Settings.
- Packaged ARM64 QEMU full-system i386 executable and 69 dependent ELF libraries, with PC BIOS/VGA firmware.
- Android-managed executable extraction; no execution of binaries downloaded into writable app storage.
- Explicit TCG software emulation, 2 guest CPUs, 768 MiB RAM; no KVM, mount, namespace or Shizuku calls.
- Local VNC/WebSocket display through bundled noVNC source; both endpoints bind only to 127.0.0.1. No remote webpage is loaded.
- Touch/pointer display, text input and boot-menu keys; foreground service and stop control.
- Built-in boot floppy, ISO import and cancellable official Android-x86 image download with SHA-256 verification.
- Bounded process error log and manual share-sheet export.

## Limits

Only arm64-v8a hosts have this new payload. The guest is x86, not ARM. ARM-only APKs will not work without a separate translation layer. Live-ISO data is not persisted. Guest audio/networking are disabled for this preview. Speed, Android graphics, long-running stability and installation/boot on POCO F6 have not been verified in the build workspace. The previous container runtime remains unusable without sufficient privileges; this backend bypasses that architecture rather than granting those privileges.

## Build

See ../third_party/qemu/README.md for dependency pins, corresponding sources, notices and ELF preparation. Before Gradle:

```sh
python3 -m pip install patchelf==0.19.1
python3 scripts/prepare_qemu.py
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The noVNC JavaScript source is already vendored. The executable and firmware/demo assets are generated from the pinned Termux packages and build script, rather than checked into this source snapshot.

## Validation

- Final APK build successful; 82 unit tests, zero failures/errors.
- Two new tests check software-only launch, loopback binding, no writable guest disk and literal ISO arguments without a shell.
- All 70 packaged QEMU ELF hashes match bundle.json; their DT_NEEDED closure resolves to packaged libraries or Android platform libraries.
- Android manifest has extractNativeLibs=true; APK signature verification passed. Version code is 2 and the debug signing certificate matches the previous local debug build.
- 84 noVNC relative module imports resolve; custom display JavaScript syntax check passed.
- The custom boot sector was executed under host Unicorn in x86-16 mode with a BIOS teletype hook; it printed the expected marker. This verifies boot-sector code only, not the Android QEMU executable or WebView integration.
- Official ISO downloaded and inspected as a bootable Android-x86 5.1 LiveCD; ISO SHA-256 and its VESA boot entry independently checked.
- Physical Android-device execution remains unverified.

APK SHA-256: `325aca7a8b342a1e12da566fbb1ba0be1a9944ff921259fbde67f720ba2b67b8`.
