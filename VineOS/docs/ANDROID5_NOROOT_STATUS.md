# Android 5 without root: current status

Target: POCO F6, Android 16, no root. Base: Hexadecinull/VineOS f72a8cc.

The device tests rejected the original namespace-container path both as an ordinary app and through Shizuku shell privileges (unshare returned EPERM; mount was not attempted). The new experimental path uses full-system QEMU with software CPU emulation and its own guest hardware, instead of those host-container operations.

An ARM64-host QEMU preview is now packaged: BIOS boot test, local display/input, ISO import and SHA-256-verified Android-x86 5.1 download. See QEMU_PREVIEW.md for precise implemented behavior, tests and known limits. The phone has not yet run this new engine, and successful Android boot is not claimed. Guest architecture is x86; ARM APK support, persistent guest data and performance work remain outstanding.

Earlier diagnostic improvements (direct app-context probe, report sharing and unique instance IDs) are retained. Virtual Master native code has not been copied; its static analysis is in virtual-master-static-analysis.md.
