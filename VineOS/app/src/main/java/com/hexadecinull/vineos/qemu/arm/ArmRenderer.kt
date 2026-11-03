package com.hexadecinull.vineos.qemu.arm

import android.view.Surface

internal object ArmRenderer {
    init { System.loadLibrary("vine_gles_bridge") }
    external fun start(surface: Surface, width: Int, height: Int): Int
    external fun detach()
    external fun stop()
    external fun info(): String
}
