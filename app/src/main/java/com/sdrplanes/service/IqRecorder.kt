package com.sdrplanes.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Recording(val seconds: Int, val total: Int) : RecordingState
    data class Done(val uri: Uri, val name: String, val bytes: Long, val dropped: Long) : RecordingState
    data class Failed(val message: String) : RecordingState
}

/**
 * Records raw 8-bit IQ (rtl_sdr format, readable by `IqFileBenchmarkTest`, dump1090
 * `--ifile`, GNU Radio, ...) to Downloads/SdrPlanes via MediaStore, so no storage permission
 * is needed. [write] is called on the DSP thread and only copies; a writer thread does the
 * I/O, so a slow disk drops buffers (counted) instead of stalling reception.
 */
class IqRecorder(context: Context, private val sampleRate: Int, seconds: Int) {
    private val resolver = context.applicationContext.contentResolver
    private val targetBytes = sampleRate.toLong() * 2 * seconds
    val totalSeconds = seconds
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE)
    private val name = "sdrplanes_%s_%dHz_1090MHz.bin".format(
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()), sampleRate,
    )
    private val uri: Uri
    private val out: OutputStream
    private var queued = 0L
    private var written = 0L
    private var dropped = 0L
    @Volatile private var finished = false
    private val writer: Thread

    /** Called on the writer thread when done (or failed). */
    var onFinished: ((RecordingState) -> Unit)? = null

    init {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/SdrPlanes")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create $name in Downloads")
        out = resolver.openOutputStream(uri) ?: throw IOException("Cannot open $name")
        writer = thread(name = "iq-recorder", isDaemon = true) { drain() }
    }

    val progressSeconds: Int get() = (queued / (sampleRate * 2L)).toInt()
    val isDone: Boolean get() = finished || queued >= targetBytes

    /** Queues a copy of [len] bytes; returns false once the recording is complete. */
    fun write(buf: ByteArray, len: Int): Boolean {
        if (isDone) return false
        val n = minOf(len.toLong(), targetBytes - queued).toInt() and 1.inv()
        if (n <= 0) return false
        if (queue.offer(buf.copyOf(n))) queued += n else { dropped += n; queued += n }
        if (queued >= targetBytes) queue.offer(END)
        return !isDone
    }

    /** Ends early (e.g. reception stopped); keeps what was written. */
    fun stop() {
        if (!finished) queue.offer(END)
    }

    private fun drain() {
        val result = try {
            while (true) {
                val chunk = queue.poll(10, TimeUnit.SECONDS) ?: break // source stalled
                if (chunk === END) break
                out.write(chunk)
                written += chunk.size
            }
            out.close()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            RecordingState.Done(uri, name, written, dropped)
        } catch (e: IOException) {
            runCatching { out.close() }
            runCatching { resolver.delete(uri, null, null) }
            RecordingState.Failed(e.message ?: "Write failed")
        }
        finished = true
        onFinished?.invoke(result)
    }

    companion object {
        /** ~64 transfers of buffering (several seconds at 2.4 MS/s with 256 KB USB transfers). */
        private const val QUEUE = 64
        private val END = ByteArray(0)
    }
}
