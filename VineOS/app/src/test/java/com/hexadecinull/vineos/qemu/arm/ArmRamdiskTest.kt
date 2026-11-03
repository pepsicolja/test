package com.hexadecinull.vineos.qemu.arm

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class ArmRamdiskTest {
    private fun archive(entries: List<Pair<String, ByteArray>>): ByteArray {
        val raw = ByteArrayOutputStream()
        fun pad() { while (raw.size() % 4 != 0) raw.write(0) }
        for ((name, content) in entries + ("TRAILER!!!" to byteArrayOf())) {
            val fields = longArrayOf(1, 0x81a4, 0, 0, 1, 123456, content.size.toLong(), 0, 0, 0, 0, name.length + 1L, 0)
            raw.write(("070701" + fields.joinToString("") { "%08x".format(it) }).toByteArray())
            raw.write(name.toByteArray()); raw.write(0); pad(); raw.write(content); pad()
        }
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw.toByteArray()) } }.toByteArray()
    }

    @Test fun patchesOnlyRanchuScriptAndPreservesOtherPayloads() {
        val original = "on init\n    write /dev/example 1\n".toByteArray()
        val payload = ByteArray(257) { it.toByte() }
        val result = ArmRamdisk.patch(archive(listOf("init.ranchu.rc" to original, "binary-file" to payload)))
        val unpacked = GZIPInputStream(ByteArrayInputStream(result)).use { it.readBytes() }
        val text = unpacked.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains(original.toString(Charsets.ISO_8859_1)))
        assertTrue(text.contains("setprop service.adb.tcp.port 5555"))
        assertTrue(text.contains(payload.toString(Charsets.ISO_8859_1)))
        assertEquals(1, Regex("# VineOS local guest import transport").findAll(text).count())
        assertTrue(text.contains("TRAILER!!!"))
    }

    @Test(expected = IllegalStateException::class)
    fun refusesAnUnrelatedRamdisk() {
        ArmRamdisk.patch(archive(listOf("init.rc" to "on boot\n".toByteArray())))
    }

    @Test(expected = IllegalStateException::class)
    fun refusesDuplicateInitScripts() {
        ArmRamdisk.patch(archive(listOf("init.ranchu.rc" to byteArrayOf(), "init.ranchu.rc" to byteArrayOf())))
    }
}
