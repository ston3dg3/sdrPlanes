package com.sdrplanes

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Minimal on-device rtl_tcp server: sends the "RTL0" header (R820T), records the 5-byte
 * commands it receives and loops [iq] until closed.
 */
class FakeRtlTcpServer(private val iq: ByteArray) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val commands: MutableList<Pair<Int, Int>> = Collections.synchronizedList(mutableListOf())

    init {
        thread(isDaemon = true, name = "fake-rtl_tcp") {
            runCatching {
                server.accept().use { s ->
                    thread(isDaemon = true) {
                        runCatching {
                            val input = DataInputStream(s.getInputStream())
                            while (true) commands += input.readUnsignedByte() to input.readInt()
                        }
                    }
                    val out = s.getOutputStream()
                    out.write("RTL0".toByteArray())
                    out.write(byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29))
                    while (true) {
                        out.write(iq)
                        Thread.sleep(100)
                    }
                }
            }
        }
    }

    override fun close() = server.close()
}

/** 2 MS/s unsigned 8-bit IQ with Mode S frames over light noise. */
object SyntheticIq {
    fun modulate(hexFrames: List<String>, rnd: Random = Random(1)): ByteArray {
        val out = ByteArrayOutputStream()
        fun sample(high: Boolean) {
            out.write(127 + (if (high) 80 else 0) + rnd.nextInt(-4, 5))
            out.write(127 + rnd.nextInt(-4, 5))
        }
        repeat(500) { sample(false) }
        for (hex in hexFrames) {
            val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            for (s in 0 until 16) sample(s == 0 || s == 2 || s == 7 || s == 9)
            for (b in 0 until bytes.size * 8) {
                val one = (bytes[b / 8].toInt() ushr (7 - b % 8)) and 1 == 1
                sample(one); sample(!one)
            }
            repeat(400) { sample(false) }
        }
        return out.toByteArray()
    }
}
