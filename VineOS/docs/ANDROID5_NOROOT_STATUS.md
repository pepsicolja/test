# Android 5 without root: development status

Target: POCO F6, Android 16, no host root. Guest: Android 5.x. Base: VineOS main, f72a8cc.

## Current evidence

The current runtime mounts rootfs.img through loop devices, sets up mount/PID namespaces, shares host Binder, renders a Linux framebuffer and writes to uinput. This is not a working no-root runtime for the target device. PRoot alone does not replace these interfaces. QEMU user-mode does not emulate the guest kernel.

Instance creation currently ignores the ROM and requested storage size in JNI. It only creates rootfs/data directories. Start expects rootfs.img, which this path never installs. A successful UI launch or APK build must not be counted as guest boot.

The create-instance ViewModel passed an empty ID to JNI, causing instance paths to be shared. The local change allocates a UUID before creating storage. This fixes identity only, not image installation or guest boot.

## Required milestones

1. Reproducible build and meaningful unit/native checks; retain debug APK and build logs.
2. Select and implement a rootless backend. A compatibility runtime needs syscall/filesystem, Binder, display/input and ABI support. Full-system QEMU is a different option with different image/hardware and performance requirements. No selection has been validated yet.
3. Validate a guest kernel/rootfs or adapted userspace appropriate to that backend. A generic AOSP system.img or Virtual Master ROM is not automatically compatible.
4. Boot guest to launcher, keep writable /data, send touch/back, install an APK, reboot and verify persistence.
5. Verify the arm64-v8a APK on the user's POCO F6 with host root absent, record logs, boot time and responsiveness. No physical device is attached to this workspace.

## Existing analysis

Virtual Master static analysis is retained in virtual-master-static-analysis.md in this directory. It contains custom JNI/HAL/Binder interfaces and ELF loaders, but the protected native runtime has not been reconstructed. No proprietary runtime has been copied into VineOS.

## Build environment

Gradle 9.7.1 and Android SDK platform 37.0 installed locally. Local SDK path is in ignored local.properties. Proxy settings are passed per build, not committed. NDK 28.2.13676358, CMake 3.22.1, Build Tools 36.0.0, Platform Tools 37.0.1 and JDK 17 are installed. Final APK build and all 80 existing unit tests passed. Logs: /workspace/scratch/vine-build.log.

This is an unfinished development checkpoint, not an Android 5 no-root release.

## First phone test

Settings -> Device diagnostics -> Проверить телефон -> Поделиться отчётом. This runs the existing mount/namespace probe from the unprivileged app process, without invoking Shizuku. It shares only a text report through the Android chooser; nothing is sent automatically. The probe does not test user namespaces, Binder isolation, guest image installation or QEMU.

All 80 existing unit tests passed on the first full build. All seven C++ translation units passed an ARM64 API 26 syntax check using NDK Clang. The final rebuild passed; the diagnostic JNI entry point is present in all four packaged ABI libraries. APK Signature Scheme v2 verification passed. Device UI and guest boot remain untested.

APK SHA-256: `8e7ea1c137c20bbea4416daaf95c8db6e5ed2c49d0e9ade46151136a262ba549`.
