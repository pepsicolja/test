# ARM32 host result, 2026-11-02

## Confirmed

- Official API22 revision 2 ARM image reaches a visible Android 5.1.1 desktop.
- ADB sys.boot_completed=1; primary guest ABI armeabi-v7a.
- SurfaceFlinger reports: Google (Mesa), Android Emulator OpenGL ES Translator (llvmpipe (LLVM 19.1.7, 256 bits)), OpenGL ES 2.0 (4.5 (Compatibility Profile) Mesa 25.0.7-2+deb13u1).
- The supplied Flappy Bird 1.3 APK installs unchanged; package manager selects primaryCpuAbi=armeabi-v7a.
- libandengine.so loads, with the expected legacy text-relocation warning.

## Failed app check

The splash activity reports Status: ok, then com.dotgears.GameActivity crashes on its GL thread:

```text
java.lang.IllegalArgumentException: EGLCONFIG_FALLBACK failed!
 at org.andengine.opengl.view.a.chooseConfig
 at android.opengl.GLSurfaceView$EglHelper.start
```

Static inspection shows that this game enables AndEngine multisampling. Its chooser first tries 2-sample MSAA, then NVIDIA coverage-sampling attributes (0x30e0/0x30e1), then a plain config. The fallback path throws when eglChooseConfig returns false. This identifies a graphics-configuration compatibility issue; it does not by itself establish which queried attribute the driver rejected. No patched APK has been used to claim success.

The original test checked only the launch command and captured a screenshot, which is insufficient: after the crash the image shows Launcher. The smoke test now also checks the resumed foreground activity and fails if the game has returned to Launcher.

## Limits

This test uses Google's Linux x86-64 host emulator with an ARM32 guest. It validates the image/ABI and exposes an app compatibility failure. It is not a test of an ARM backend inside the phone APK. Guest sound output was disabled, performance was not benchmarked, and gameplay is not verified.

Desktop evidence: [arm32-host-desktop.png](arm32-host-desktop.png).
