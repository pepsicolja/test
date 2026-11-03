# Public Android GLES bridge

Pinned source and archive checksum: `source.json`. It selects the AOSP-derived `host-opengl-render` directory from jianglin-code/qemu-of-arm-android. The original AOSP Apache 2.0 headers and Khronos notices remain in the downloaded source. See the individual files for their notices. Our `BridgeJni.cpp` and `NativeAndroidWindow.cpp` are Apache-2.0.

`renderer.patch` replaces private MediaTek GPU paths with the public Android EGL/GLES libraries, repairs the allocated socket port, initial buffer allocation, header casing and RGBA callback sizing. Private SurfaceComposer and framebuffer-window sources are excluded. The local replacements accept an Android Surface through ANativeWindow. No vendor binaries are shipped.

Build through `scripts/build_arm32_native.py`. The GLES stream is bound to loopback only and fed by the emulated goldfish pipe. This is the historical GLES 1/2 protocol needed by API22, not a claim of modern Vulkan/GLES3 support. Android-device GPU rendering and audible output still require physical-device verification.
