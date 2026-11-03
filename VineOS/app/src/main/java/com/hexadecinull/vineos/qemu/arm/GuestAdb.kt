package com.hexadecinull.vineos.qemu.arm

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/** Single persistent, serialized adbd transport inside the guest. Does not contact host Android's adb/Shizuku. */
internal class GuestAdb(private val port: Int, private val timeoutMs: Int = 15_000) {
    fun shell(command: String): String = withChannel("shell:$command") { channel ->
        val result = ByteArrayOutputStream()
        while (true) {
            val bytes = channel.next() ?: break
            check(result.size() + bytes.size <= LIMIT) { "Guest shell output exceeds limit" }
            result.write(bytes)
        }
        result.toString("UTF-8")
    }

    fun push(source: InputStream, path: String) = withChannel("sync:") { channel ->
        require(path.startsWith("/data/local/tmp/") && ',' !in path && '\u0000' !in path)
        val name = "$path,33188".toByteArray(Charsets.UTF_8)
        channel.write(syncHeader("SEND", name.size) + name)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            channel.write(syncHeader("DATA", count))
            channel.write(buffer.copyOf(count))
        }
        channel.write(syncHeader("DONE", (System.currentTimeMillis() / 1000).toInt()))
        val response = channel.exact(8)
        val code = String(response, 0, 4, Charsets.US_ASCII)
        val length = ByteBuffer.wrap(response, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        check(length in 0..65536) { "Invalid guest sync response" }
        check(code == "OKAY") {
            if (code == "FAIL") String(channel.exact(length), Charsets.UTF_8) else "Unknown guest sync response"
        }
    }

    private fun <T> withChannel(service: String, action: (Channel) -> T): T = synchronized(lock) {
        try {
            if (transport == null || transport!!.port != port || transport!!.socket.isClosed) {
                transport?.socket?.close()
                transport = Transport(port, timeoutMs)
            }
            val link = transport!!
            link.socket.soTimeout = timeoutMs
            Channel(link, service).use(action)
        } catch (error: Exception) {
            transport?.socket?.close()
            transport = null
            throw error
        }
    }

    private class Transport(val port: Int, timeoutMs: Int) {
        val socket = Socket()
        val input: DataInputStream
        val output: DataOutputStream
        var nextId = 1
        var connected = false
        init {
            try {
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.tcpNoDelay = true
                input = DataInputStream(socket.getInputStream())
                output = DataOutputStream(socket.getOutputStream())
            } catch (error: Exception) { socket.close(); throw error }
        }
    }

    private inner class Channel(private val link: Transport, service: String) : AutoCloseable {
        private val socket get() = link.socket
        private val input get() = link.input
        private val output get() = link.output
        private val local = link.nextId++
        private var remote = 0
        private var ended = false
        private val pending = ArrayDeque<ByteArray>()
        private var pendingBytes = 0

        init {
            if (!link.connected) {
                send("CNXN", 0x01000000, 4096, "host::\u0000".toByteArray())
                val hello = packet()
                check(hello.command == "CNXN") {
                    if (hello.command == "AUTH") "Guest ADB requires authorization" else "Guest ADB handshake failed"
                }
                link.connected = true
            }
            send("OPEN", local, 0, (service + '\u0000').toByteArray(Charsets.UTF_8))
            val opened = packet()
            check(opened.command == "OKAY" && opened.arg1 == local && opened.arg0 != 0) {
                "Guest service did not open"
            }
            remote = opened.arg0
        }

        private fun send(command: String, a: Int, b: Int, bytes: ByteArray = byteArrayOf()) {
            val id = command.toByteArray(Charsets.US_ASCII).let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int }
            val packet = ByteBuffer.allocate(24 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
            for (value in intArrayOf(id, a, b, bytes.size, bytes.sumOf { it.toInt() and 255 }, id.inv())) packet.putInt(value)
            packet.put(bytes)
            output.write(packet.array())
            output.flush()
        }

        private fun packet(): Packet {
            val h = IntArray(6) { Integer.reverseBytes(input.readInt()) }
            check(h[0].inv() == h[5] && h[3] in 0..LIMIT) { "Invalid guest ADB header" }
            val bytes = ByteArray(h[3]).also(input::readFully)
            check(bytes.sumOf { it.toInt() and 255 } == h[4]) { "Invalid guest ADB checksum" }
            val command = String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(h[0]).array(), Charsets.US_ASCII)
            return Packet(command, h[1], h[2], bytes)
        }

        private fun receive(): Boolean {
            val p = packet()
            check(p.arg0 == remote && p.arg1 == local) { "Guest ADB channel mismatch" }
            when (p.command) {
                "OKAY" -> return true
                "WRTE" -> {
                    check(pendingBytes + p.bytes.size <= LIMIT) { "Guest ADB queue exceeds limit" }
                    pending.addLast(p.bytes)
                    pendingBytes += p.bytes.size
                    send("OKAY", local, remote)
                }
                "CLSE" -> {
                    ended = true
                    send("CLSE", local, remote)
                }
                else -> error("Unexpected guest ADB command")
            }
            return false
        }

        fun write(bytes: ByteArray) {
            var at = 0
            while (at < bytes.size) {
                check(!ended) { "Guest ADB channel is closed" }
                val count = minOf(4096, bytes.size - at)
                send("WRTE", local, remote, bytes.copyOfRange(at, at + count))
                while (!receive()) check(!ended) { "Guest closed ADB during write" }
                at += count
            }
        }

        fun next(): ByteArray? {
            while (pending.isEmpty() && !ended) receive()
            if (pending.isEmpty()) return null
            return pending.removeFirst().also { pendingBytes -= it.size }
        }

        fun exact(count: Int): ByteArray {
            val result = ByteArray(count)
            var at = 0
            while (at < count) {
                val bytes = next() ?: error("Guest closed an incomplete sync response")
                val take = minOf(bytes.size, count - at)
                bytes.copyInto(result, at, 0, take)
                at += take
                if (take < bytes.size) {
                    val rest = bytes.copyOfRange(take, bytes.size)
                    pending.addFirst(rest)
                    pendingBytes += rest.size
                }
            }
            return result
        }

        override fun close() {
            if (!ended) {
                socket.soTimeout = minOf(timeoutMs, 3000)
                send("CLSE", local, remote)
                while (!ended) receive()
            }
        }
    }

    private data class Packet(val command: String, val arg0: Int, val arg1: Int, val bytes: ByteArray)
    private fun syncHeader(command: String, value: Int): ByteArray =
        command.toByteArray(Charsets.US_ASCII) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    companion object {
        private const val LIMIT = 1024 * 1024
        private val lock = Any()
        @Volatile private var transport: Transport? = null
        // Closing the socket interrupts an in-flight blocking import immediately.
        fun disconnect() { transport?.socket?.close() }
    }
}
