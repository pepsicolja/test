package com.hexadecinull.vineos.qemu

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Minimal RFB 3.8 client for our loopback QEMU: true-colour raw frames and resize. */
internal class RfbClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 5901,
    private val onFrame: (Int, Int, IntArray) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var socket: Socket? = null
    private var output: DataOutputStream? = null

    private fun send(block: DataOutputStream.() -> Unit) = synchronized(writeLock) {
        output?.apply { block(); flush() }
    }

    fun run() {
        val connected = connect()
        connected.use { connection ->
            connection.soTimeout = 10000
            val input = DataInputStream(connection.getInputStream().buffered(65536))
            output = DataOutputStream(connection.getOutputStream().buffered())
            val greeting = ByteArray(12).also { input.readFully(it) }.toString(Charsets.US_ASCII)
            if (!greeting.startsWith("RFB 003.")) throw IOException("Неверный протокол экрана")
            send { write("RFB 003.008\n".toByteArray(Charsets.US_ASCII)) }
            val types = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            if (!types.contains(1.toByte())) throw IOException("Локальный VNC не разрешил подключение")
            send { writeByte(1) }
            if (input.readInt() != 0) throw IOException("VNC отклонил подключение")
            send { writeByte(1) }
            var width = input.readUnsignedShort()
            var height = input.readUnsignedShort()
            validateSize(width, height)
            input.readFully(ByteArray(16)) // Server pixel format; request ours below.
            val nameLength = input.readInt()
            if (nameLength !in 0..65536) throw IOException("Некорректное имя экрана")
            input.readFully(ByteArray(nameLength))
            send {
                writeByte(0); write(ByteArray(3))
                writeByte(32); writeByte(24); writeByte(0); writeByte(1)
                repeat(3) { writeShort(255) }
                writeByte(16); writeByte(8); writeByte(0); write(ByteArray(3))
                writeByte(2); writeByte(0); writeShort(2)
                writeInt(0) // Raw, always supported; no browser/image codecs.
                writeInt(-223) // DesktopSize.
            }
            ready.set(true)
            connection.soTimeout = 0 // An idle guest legitimately has no updates.
            var pixels = IntArray(width * height)
            var incremental = false
            fun request() = send {
                writeByte(3); writeByte(if (incremental) 1 else 0)
                writeShort(0); writeShort(0); writeShort(width); writeShort(height)
            }
            request()
            while (!closed.get()) {
                when (input.readUnsignedByte()) {
                    0 -> {
                        input.readUnsignedByte()
                        val rectangles = input.readUnsignedShort()
                        if (rectangles > 4096) throw IOException("Слишком много областей экрана")
                        var changed = false
                        incremental = true
                        repeat(rectangles) {
                            val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
                            val w = input.readUnsignedShort(); val h = input.readUnsignedShort()
                            when (val encoding = input.readInt()) {
                                -223 -> {
                                    validateSize(w, h)
                                    width = w; height = h
                                    pixels = IntArray(w * h)
                                    incremental = false
                                }
                                0 -> {
                                    if (x + w > width || y + h > height) throw IOException("Область вне экрана")
                                    val row = ByteArray(w * 4)
                                    repeat(h) { dy ->
                                        input.readFully(row)
                                        repeat(w) { dx ->
                                            val p = dx * 4
                                            pixels[(y + dy) * width + x + dx] = (0xff shl 24) or
                                                ((row[p + 2].toInt() and 255) shl 16) or
                                                ((row[p + 1].toInt() and 255) shl 8) or (row[p].toInt() and 255)
                                        }
                                    }
                                    changed = true
                                }
                                else -> throw IOException("Неподдерживаемый формат VNC: $encoding")
                            }
                        }
                        if (changed) onFrame(width, height, pixels.copyOf())
                        if (!closed.get()) {
                            Thread.sleep(80)
                            request()
                        }
                    }
                    1 -> {
                        input.readUnsignedByte(); input.readUnsignedShort()
                        input.readFully(ByteArray(input.readUnsignedShort() * 6))
                    }
                    2 -> Unit // Bell.
                    3 -> {
                        input.readFully(ByteArray(3))
                        val length = input.readInt()
                        if (length !in 0..1048576) throw IOException("Некорректный размер буфера обмена")
                        input.readFully(ByteArray(length))
                    }
                    else -> throw IOException("Неизвестное сообщение VNC")
                }
            }
        }
    }

    private fun connect(): Socket {
        val deadline = System.nanoTime() + 10000000000L
        var last: IOException? = null
        while (!closed.get() && System.nanoTime() < deadline) {
            val attempt = Socket()
            socket = attempt
            try {
                attempt.tcpNoDelay = true
                attempt.connect(InetSocketAddress(host, port), 500)
                if (closed.get()) { attempt.close(); throw IOException("Экран закрыт") }
                return attempt
            } catch (error: IOException) {
                last = error
                attempt.close()
                if (!closed.get()) Thread.sleep(150)
            }
        }
        throw IOException("Нет подключения к экрану QEMU", last)
    }

    fun key(keysym: Int) {
        if (!ready.get() || closed.get()) return
        send {
            for (down in listOf(1, 0)) {
                writeByte(4); writeByte(down); writeShort(0); writeInt(keysym)
            }
        }
    }

    fun pointer(x: Int, y: Int, down: Boolean) {
        if (!ready.get() || closed.get()) return
        send { writeByte(5); writeByte(if (down) 1 else 0); writeShort(x); writeShort(y) }
    }

    override fun close() {
        closed.set(true)
        ready.set(false)
        socket?.close()
    }

    private fun validateSize(width: Int, height: Int) {
        if (width !in 1..4096 || height !in 1..4096 || width.toLong() * height > 8388608) {
            throw IOException("Некорректный размер экрана: $width × $height")
        }
    }
}
