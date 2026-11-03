package com.hexadecinull.vineos.qemu.arm

/** ARM32 Ranchu configuration, separate from the earlier PC/ISO prototype. */
internal object ArmCommand {
    const val WIDTH = 320
    const val HEIGHT = 480
    const val RFB_PORT = 5901

    fun build(binary: String, files: ArmFiles, adbPort: Int): List<String> = listOf(
        binary, "-machine", "ranchu", "-cpu", "cortex-a15",
        "-accel", "tcg,thread=multi", "-smp", "2", "-m", "768",
        "-nodefaults", "-no-user-config", "-display", "none",
        "-global", "goldfish_fb.width=$WIDTH", "-global", "goldfish_fb.height=$HEIGHT",
        "-vnc", "127.0.0.1:1", "-monitor", "none", "-serial", "stdio",
        "-kernel", files.kernel.absolutePath, "-initrd", files.ramdisk.absolutePath,
        "-append", "qemu=1 androidboot.hardware=ranchu console=ttyAMA0 androidboot.console=ttyAMA0 qemu.gles=1 androidboot.selinux=permissive",
        "-netdev", "user,id=net0,hostfwd=tcp:127.0.0.1:$adbPort-:5555",
        "-device", "virtio-net-device,netdev=net0",
        // This pinned Ranchu/FDT implementation enumerates virtio disks in the
        // reverse order of insertion: vda=system, vdb=cache, vdc=userdata.
        "-drive", "if=none,id=data,file=${files.data.absolutePath},format=raw",
        "-device", "virtio-blk-device,drive=data",
        "-drive", "if=none,id=cache,file=${files.cache.absolutePath},format=raw",
        "-device", "virtio-blk-device,drive=cache",
        "-drive", "if=none,id=system,file=${files.system.absolutePath},format=raw,readonly=on",
        "-device", "virtio-blk-device,drive=system",
        "-object", "rng-random,id=rng0",
        "-device", "virtio-rng-device,rng=rng0",
    )
}
