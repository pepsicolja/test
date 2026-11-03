// SPDX-License-Identifier: Apache-2.0
#include "NativeSubWindow.h"
#include <android/native_window.h>

extern "C" EGLNativeWindowType createSubWindow(FBNativeWindowType window,
        EGLNativeDisplayType* display, int, int, int width, int height) {
    ANativeWindow* native = static_cast<ANativeWindow*>(window);
    if (!native || width <= 0 || height <= 0) return nullptr;
    ANativeWindow_acquire(native);
    if (ANativeWindow_setBuffersGeometry(native, width, height, 0) != 0) {
        ANativeWindow_release(native);
        return nullptr;
    }
    if (display) *display = EGL_DEFAULT_DISPLAY;
    return native;
}

extern "C" void destroySubWindow(EGLNativeDisplayType, EGLNativeWindowType window) {
    if (window) ANativeWindow_release(window);
}
