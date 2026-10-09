package com.sdrplanes.sdr

/** Gain in tenths of a dB, or null for tuner AGC. */
data class TunerConfig(
    val frequencyHz: Long = 1_090_000_000L,
    val sampleRate: Int = 2_400_000,
    val gainTenthsDb: Int? = 496,
    val ppm: Int = 0,
    /** 4.5 V on the antenna port for an LNA/filter (RTL-SDR Blog dongles). */
    val biasTee: Boolean = false,
)

data class TunerInfo(val tunerName: String, val gainCount: Int)

/**
 * A source of 8-bit unsigned interleaved IQ samples. Implementations: [UsbSampleSource]
 * (built-in USB driver) and [RtlTcpSampleSource] (driver app or any rtl_tcp server).
 */
interface SampleSource {
    val description: String

    /**
     * Blocks (on the caller's IO dispatcher) streaming samples until the coroutine is
     * cancelled or the source fails with an exception. [onSamples] gets an even byte
     * count and must consume the buffer before returning (it is reused).
     */
    suspend fun stream(
        config: TunerConfig,
        onInfo: (TunerInfo) -> Unit,
        onSamples: (buffer: ByteArray, length: Int) -> Unit,
    )
}
