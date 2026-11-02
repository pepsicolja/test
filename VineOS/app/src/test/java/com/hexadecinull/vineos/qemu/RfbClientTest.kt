package com.hexadecinull.vineos.qemu

import com.google.common.truth.Truth.assertThat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test

class RfbClientTest {
    @Test fun `decodes raw frame pixels after real RFB handshake`() {
        ServerSocket(0).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val peer = pool.submit {
                    server.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val out = DataOutputStream(socket.getOutputStream())
                        out.writeBytes("RFB 003.008\n"); out.flush()
                        input.readFully(ByteArray(12))
                        out.write(byteArrayOf(1, 1)); out.flush()
                        assertThat(input.readUnsignedByte()).isEqualTo(1)
                        out.writeInt(0); out.flush()
                        input.readUnsignedByte()
                        out.writeShort(2); out.writeShort(1); out.write(ByteArray(16)); out.writeInt(0); out.flush()
                        input.readFully(ByteArray(20)) // SetPixelFormat.
                        input.readFully(ByteArray(12)) // SetEncodings: raw and DesktopSize.
                        input.readFully(ByteArray(10)) // First framebuffer request.
                        out.writeByte(0); out.writeByte(0); out.writeShort(1)
                        out.writeShort(0); out.writeShort(0); out.writeShort(2); out.writeShort(1); out.writeInt(0)
                        out.write(byteArrayOf(0x33, 0x22, 0x11, 0, 0x66, 0x55, 0x44, 0)); out.flush()
                    }
                }
                var pixels = intArrayOf()
                lateinit var client: RfbClient
                client = RfbClient(port = server.localPort, onFrame = { width, height, data ->
                    assertThat(width).isEqualTo(2); assertThat(height).isEqualTo(1)
                    pixels = data; client.close()
                })
                client.use { it.run() }
                peer.get(5, TimeUnit.SECONDS)
                assertThat(pixels.toList()).containsExactly(0xff112233.toInt(), 0xff445566.toInt()).inOrder()
            } finally { pool.shutdownNow() }
        }
    }
}
