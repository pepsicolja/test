# Flappy Bird compatibility fixture

User-supplied APK, inspected 2026-11-02. The APK is not redistributed or embedded in VineOS.

- Package: `com.dotgears.flappybird`
- Version: 1.3, versionCode 4
- Size: 915558 bytes
- SHA-256: `a3e6958ce2100966f4e207778e4cdbe72788214148c7f4bfd042ba365498deb3`
- minSdk: 8 (Android 2.2); targetSdk: 15
- Launcher: `com.dotgears.flappy.SplashScreen`
- Declared OpenGL ES requirement: 2.0
- Native libraries: `libandengine.so` in `armeabi`, `armeabi-v7a`, and `x86`. ELF headers confirm ARM32 for the ARM variants, i386 for x86. No arm64-v8a variant.
- ARM libraries include DT_TEXTREL. Retain an old Android userspace/linker appropriate for this binary. Raising targetSdk does not provide missing ARM32 support.
- Native dependencies: libGLESv2.so, libstdc++.so, libm.so, libc.so, libdl.so.
- Assets include a sprite atlas and five Ogg sound effects.

Archive CRC validation and static manifest/ELF inspection succeeded. Installation of the unchanged APK in a host ARM32 Android 5.1.1 guest is now verified; package manager selects armeabi-v7a. The game crashes during EGL configuration selection, so gameplay is not demonstrated. See arm32-host-result.md. A valid signature does not establish provenance.

## Implications

This specific APK can use its bundled x86 library on an x86 guest, so it is not an ARM-only fixture. Keep ARM32 compatibility as the actual project goal; successful x86 gameplay would not prove it.

The Android-x86 host debug run previously reached Android PixelFlinger OpenGL ES-CM 1.0. That renderer does not satisfy this APK's declared GLES 2.0 requirement. The new host Goldfish test provides GLES 2.0 and an ARM32 userspace, but the game still crashes while selecting a multisampling configuration. A working ES version alone does not prove that all requested EGL configurations are supported. Changing CPU architecture alone is insufficient.

## Acceptance checks

1. Boot Android guest to a usable desktop.
2. Confirm guest ABI is armeabi-v7a/armeabi and the ARM library is selected.
3. Confirm working GLES 2.0 context, including shaders and textures.
4. Install the unmodified supplied APK and launch its activity.
5. Verify title screen, repeated touch input, animated gameplay and score.
6. Verify sound effects once guest audio is implemented.
7. Verify process restart and saved high score once persistent guest storage is implemented.
