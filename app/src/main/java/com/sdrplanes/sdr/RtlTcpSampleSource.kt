package com.sdrplanes.sdr

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * rtl_tcp client. Protocol: the server sends a 12-byte header ("RTL0", tuner type,
 * gain count; big-endian), then streams raw IQ. The client sends 5-byte commands
 * (1-byte opcode + big-endian uint32 parameter).
 */
class RtlTcpSampleSource(
    private val host: String,
    private val port: Int,
    private val connectAttempts: Int = 10,
) : SampleSource {

    override val description get() = "rtl_tcp $host:$port"

    override suspend fun stream(
        config: TunerConfig,
        onInfo: (TunerInfo) -> Unit,
        onSamples: (ByteArray, Int) -> Unit,
    ) {
        val socket = connectWithRetry()
        // Closing the socket is the only way to unblock a pending read on cancellation.
        val handle = currentCoroutineContext().job.invokeOnCompletion { runCatching { socket.close() } }
        try {
            // A live rtl_tcp server sends ~4 MB/s; silence means the server or dongle hung.
            socket.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val header = ByteArray(12)
            input.readFully(header)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RTL0") {
                throw IOException("Not an rtl_tcp server (bad header)")
            }
            val tunerType = header.beInt(4)
            val gainCount = header.beInt(8)
            onInfo(TunerInfo(TUNER_NAMES.getOrElse(tunerType) { "Unknown ($tunerType)" }, gainCount))

            val out = socket.getOutputStream()
            out.command(CMD_SAMPLE_RATE, config.sampleRate)
            out.command(CMD_FREQUENCY, config.frequencyHz.toInt())
            out.command(CMD_PPM, config.ppm)
            out.command(CMD_AGC_MODE, 0)
            if (config.gainTenthsDb == null) {
                out.command(CMD_GAIN_MODE, 0)
            } else {
                out.command(CMD_GAIN_MODE, 1)
                out.command(CMD_GAIN, config.gainTenthsDb)
            }
            out.flush()

            val buf = ByteArray(BUFFER_SIZE)
            var carry = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf, carry, buf.size - carry)
                if (n < 0) throw EOFException("rtl_tcp server closed the connection")
                val total = carry + n
                val even = total and 1.inv()
                if (even > 0) onSamples(buf, even)
                // Keep IQ pairs aligned when TCP splits a pair.
                carry = total - even
                if (carry == 1) buf[0] = buf[total - 1]
            }
        } catch (e: SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            throw IOException("No data from SDR for ${READ_TIMEOUT_MS / 1000} s (dongle unplugged or server hung?)", e)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // cancellation closed the socket: not an error
            throw e
        } finally {
            handle.dispose()
            runCatching { socket.close() }
        }
    }

    private suspend fun connectWithRetry(): Socket {
        var lastError: IOException? = null
        repeat(connectAttempts) { attempt ->
            currentCoroutineContext().ensureActive()
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.receiveBufferSize = 1 shl 20
                s.connect(InetSocketAddress(host, port), 3000)
                return s
            } catch (e: IOException) {
                runCatching { s.close() }
                lastError = e
                // The driver app may need a moment after launch before it listens.
                delay(300L + attempt * 200L)
            }
        }
        throw IOException("Cannot connect to $host:$port (${lastError?.message})", lastError)
    }

    private fun OutputStream.command(op: Int, value: Int) {
        write(byteArrayOf(op.toByte(), (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()))
    }

    private fun ByteArray.beInt(off: Int): Int =
        ((this[off].toInt() and 0xFF) shl 24) or ((this[off + 1].toInt() and 0xFF) shl 16) or
            ((this[off + 2].toInt() and 0xFF) shl 8) or (this[off + 3].toInt() and 0xFF)

    companion object {
        private const val BUFFER_SIZE = 1 shl 16
        private const val READ_TIMEOUT_MS = 5_000
        private const val CMD_FREQUENCY = 0x01
        private const val CMD_SAMPLE_RATE = 0x02
        private const val CMD_GAIN_MODE = 0x03
        private const val CMD_GAIN = 0x04
        private const val CMD_PPM = 0x05
        private const val CMD_AGC_MODE = 0x08

        private val TUNER_NAMES = listOf("Unknown", "E4000", "FC0012", "FC0013", "FC2580", "R820T", "R828D")
    }
}
