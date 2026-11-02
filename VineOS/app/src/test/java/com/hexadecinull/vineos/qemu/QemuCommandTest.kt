package com.hexadecinull.vineos.qemu

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QemuCommandTest {
    @Test fun `rootless launch uses software emulation and loopback display`() {
        val args = QemuCommand.build("/native/qemu.so", "/files/bios", "/files/demo.img", true)
        assertThat(args[args.indexOf("-accel") + 1]).isEqualTo("tcg,thread=multi")
        assertThat(args[args.indexOf("-cpu") + 1]).isEqualTo("max")
        assertThat(args[args.indexOf("-vnc") + 1]).isEqualTo("127.0.0.1:1,websocket=5701")
        assertThat(args).doesNotContain("-enable-kvm")
        assertThat(args).doesNotContain("su")
        assertThat(args[args.indexOf("-nic") + 1]).isEqualTo("none")
        assertThat(args[args.indexOf("-k") + 1]).isEqualTo("en-us")
    }

    @Test fun `ISO path is a literal argument and live boot has no writable disk`() {
        val image = "/private/guest with spaces;echo secret.iso"
        val args = QemuCommand.build("/native/qemu.so", "/files/bios", image, false)
        assertThat(args[args.indexOf("-cdrom") + 1]).isEqualTo(image)
        assertThat(args).doesNotContain("-drive")
        assertThat(args).doesNotContain("sh")
        assertThat(args[args.indexOf("-boot") + 1]).isEqualTo("d")
    }
}
