package com.hexadecinull.vineos.qemu

/** Full-system software emulation. Never invokes a shell, KVM, root or Shizuku. */
internal object QemuCommand {
    const val WEBSOCKET_PORT = 5701

    fun build(binary: String, firmware: String, image: String, demo: Boolean): List<String> =
        listOf(
            binary,
            "-L", firmware,
            "-machine", "pc",
            "-accel", "tcg,thread=multi",
            "-cpu", "qemu32",
            "-smp", "2",
            "-m", "768",
            "-no-user-config",
            "-nodefaults",
            "-vga", "std",
            "-nic", "none",
            "-serial", "none",
            "-parallel", "none",
            "-monitor", "none",
            "-audiodev", "none,id=silent",
            "-display", "none",
            "-vnc", "127.0.0.1:1,websocket=$WEBSOCKET_PORT",
        ) + if (demo) {
            listOf("-drive", "file=$image,format=raw,if=floppy,readonly=on", "-boot", "a")
        } else {
            // Live ISO only in this preview: no writable guest disk is implied.
            listOf("-cdrom", image, "-boot", "d")
        }
}
