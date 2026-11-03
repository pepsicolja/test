package com.hexadecinull.vineos.qemu.arm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Repack a verified SDK ramdisk; changes only the guest's local import transport. */
internal object ArmRamdisk {
    fun patch(compressed: ByteArray): ByteArray {
        val raw = GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                check(out.size() + n <= 16 * 1024 * 1024) { "Ramdisk exceeds limit" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        val output = ByteArrayOutputStream()
        var at = 0
        var patched = false
        var trailer = false
        fun align(value: Int) = (value + 3) and -4
        fun pad() { while (output.size() % 4 != 0) output.write(0) }
        while (at + 110 <= raw.size) {
            val header = raw.copyOfRange(at, at + 110)
            check(String(header, 0, 6, Charsets.US_ASCII) == "070701") { "Unsupported ramdisk format" }
            val fields = LongArray(13) { String(header, 6 + it * 8, 8, Charsets.US_ASCII).toLong(16) }
            at += 110
            val nameLength = fields[11].toInt()
            val dataLength = fields[6].toInt()
            check(nameLength in 1..4096 && dataLength in 0..raw.size && at.toLong() + nameLength <= raw.size)
            val nameBytes = raw.copyOfRange(at, at + nameLength)
            check(nameBytes.last() == 0.toByte())
            val name = String(nameBytes, 0, nameLength - 1, Charsets.UTF_8)
            at = align(at + nameLength)
            check(at.toLong() + dataLength <= raw.size)
            var data = raw.copyOfRange(at, at + dataLength)
            at = align(at + dataLength)
            if (name == "init.ranchu.rc") {
                check(!patched) { "Duplicate Ranchu init script" }
                data += "\n# VineOS local guest import transport\non boot\n    setprop service.adb.tcp.port 5555\n    stop adbd\n    start adbd\n".toByteArray()
                patched = true
            }
            fields[6] = data.size.toLong()
            output.write(("070701" + fields.joinToString("") { "%08x".format(it) }).toByteArray(Charsets.US_ASCII))
            output.write(nameBytes); pad(); output.write(data); pad()
            if (name == "TRAILER!!!") { trailer = true; break }
        }
        check(patched && trailer) { "Incomplete Ranchu ramdisk" }
        val result = ByteArrayOutputStream()
        GZIPOutputStream(result).use { it.write(output.toByteArray()) }
        return result.toByteArray()
    }
}
