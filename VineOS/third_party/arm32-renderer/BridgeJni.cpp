// SPDX-License-Identifier: Apache-2.0
#include "render_api.h"
#include "FrameBuffer.h"
#include "EGLDispatch.h"
#include <android/native_window_jni.h>
#include <android/log.h>
#include <jni.h>
#include <cstdlib>
#include <cstdio>
#include <string>

// Kotlin serializes attach/detach/stop with the engine lifecycle mutex.
static bool libraries_loaded = false;
static bool initialized = false;
static char address[256] = {0};

static void failure(JNIEnv* env, const char* stage) {
    char message[256];
    unsigned error = s_egl.eglGetError ? s_egl.eglGetError() : 0;
    snprintf(message, sizeof(message), "GLES renderer: %s (EGL 0x%x)", stage, error);
    __android_log_print(ANDROID_LOG_ERROR, "VineRenderer", "%s", message);
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hexadecinull_vineos_qemu_arm_ArmRenderer_start(
        JNIEnv* env, jobject, jobject surface, jint width, jint height) {
    if (!surface || width <= 0 || height <= 0 || width > 4096 || height > 4096) {
        failure(env, "invalid surface dimensions"); return 0;
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) { failure(env, "surface is unavailable"); return 0; }
    if (!libraries_loaded) {
        if (!initLibrary()) {
            ANativeWindow_release(window); failure(env, "load public EGL/GLES libraries"); return 0;
        }
        libraries_loaded = true;
    }
    if (!initialized) {
        if (!setStreamMode(STREAM_MODE_TCP) ||
            !initOpenGLRenderer(width, height, address, sizeof(address))) {
            FrameBuffer::finalize();
            ANativeWindow_release(window); failure(env, "initialize framebuffer"); return 0;
        }
        initialized = true;
    }
    bool attached = createOpenGLSubwindow(window, 0, 0, width, height, 0);
    ANativeWindow_release(window);
    if (!attached) { failure(env, "attach Android Surface"); return 0; }
    return static_cast<jint>(strtol(address, nullptr, 10));
}

extern "C" JNIEXPORT void JNICALL
Java_com_hexadecinull_vineos_qemu_arm_ArmRenderer_detach(JNIEnv*, jobject) {
    if (initialized) destroyOpenGLSubwindow();
}

extern "C" JNIEXPORT void JNICALL
Java_com_hexadecinull_vineos_qemu_arm_ArmRenderer_stop(JNIEnv*, jobject) {
    if (!initialized) return;
    destroyOpenGLSubwindow();
    stopOpenGLRenderer();
    FrameBuffer::finalize();
    initialized = false;
    address[0] = 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hexadecinull_vineos_qemu_arm_ArmRenderer_info(JNIEnv* env, jobject) {
    const char *vendor = nullptr, *renderer = nullptr, *version = nullptr;
    if (initialized) getHardwareStrings(&vendor, &renderer, &version);
    std::string info = vendor ? vendor : "";
    info += "\n"; info += renderer ? renderer : "";
    info += "\n"; info += version ? version : "";
    return env->NewStringUTF(info.c_str());
}
