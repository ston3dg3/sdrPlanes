package com.sdrplanes.dsp

import com.sdrplanes.decode.ModeSDecoder
import com.sdrplanes.decode.hexToBytes
import com.sdrplanes.decode.toHex
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class ModeSDemodulatorTest {

    private val frames = listOf(
        "8D4840D6202CC371C32CE0576098",
        "8D485020994409940838175B284F",
        "8D40621D58C382D690C8AC2863A7",
        "8D40621D58C386435CC412692AD6",
        "8DA05F219B06B6AF189400CBC33F",
    )

    private fun decodeAll(iq: ByteArray, chunk: Int): List<String> {
        val decoder = ModeSDecoder()
        val out = mutableListOf<String>()
        val demod = ModeSDemodulator { frame, _ ->
            val msg = decoder.decode(frame, 0)
            if (msg != null) out += frame.toHex(if (decoder.frameBits(msg.df) == 56) 7 else 14)
            msg != null
        }
        var i = 0
        while (i < iq.size) {
            val n = minOf(chunk, iq.size - i)
            demod.process(iq.copyOfRange(i, i + n), n)
            i += n
        }
        return out
    }

    @Test
    fun decodesSyntheticFramesInOneChunk() {
        val iq = SyntheticIq.modulate(frames, Random(1))
        assertEquals(frames, decodeAll(iq, iq.size))
    }

    @Test
    fun decodesFramesSplitAcrossSmallChunks() {
        val iq = SyntheticIq.modulate(frames, Random(2))
        // 2 * 137 bytes: forces most frames to straddle chunk boundaries.
        assertEquals(frames, decodeAll(iq, 274))
        assertEquals(frames, decodeAll(iq, 16384))
    }

    @Test
    fun pureNoiseYieldsNoFrames() {
        val rnd = Random(3)
        val iq = ByteArray(4_000_000) { (127 + rnd.nextInt(-20, 21)).toByte() } // 1 s at 2 MS/s
        assertEquals(emptyList<String>(), decodeAll(iq, 262144))
    }
}

/** Generates 2 MS/s unsigned 8-bit IQ containing Mode S frames over light noise. */
object SyntheticIq {
    fun modulate(hexFrames: List<String>, rnd: Random, amplitude: Int = 80, noise: Int = 4): ByteArray {
        val out = ByteArrayOutputStream()
        fun sample(high: Boolean) {
            val a = if (high) amplitude else 0
            out.write(127 + a + rnd.nextInt(-noise, noise + 1))
            out.write(127 + rnd.nextInt(-noise, noise + 1))
        }
        fun gap(n: Int) = repeat(n) { sample(false) }

        gap(500)
        for (hex in hexFrames) {
            val bytes = hex.hexToBytes()
            for (s in 0 until 16) sample(s == 0 || s == 2 || s == 7 || s == 9)
            for (b in 0 until bytes.size * 8) {
                val one = (bytes[b / 8].toInt() ushr (7 - b % 8)) and 1 == 1
                sample(one); sample(!one)
            }
            gap(300 + rnd.nextInt(200))
        }
        return out.toByteArray()
    }
}
