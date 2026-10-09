package com.sdrplanes.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sdrplanes.MainActivity
import com.sdrplanes.R
import com.sdrplanes.db.AircraftDb
import com.sdrplanes.decode.ModeSDecoder
import com.sdrplanes.dsp.ModeSDemodulator
import com.sdrplanes.model.AircraftTracker
import com.sdrplanes.sdr.RtlSdrDriver
import com.sdrplanes.sdr.RtlTcpSampleSource
import com.sdrplanes.sdr.SampleSource
import com.sdrplanes.sdr.TunerConfig
import com.sdrplanes.sdr.UsbSampleSource
import com.sdrplanes.sdr.usb.UnsupportedHardwareException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the SDR connection and the
 * samples -> demodulator -> decoder -> tracker pipeline, so reception continues with the
 * screen off. Publishes results to [ReceiverState]. Reconnects with backoff when the
 * source drops (USB and network modes; the driver app exits with its connection).
 */
class ReceiverService : LifecycleService() {
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /** Seconds of IQ to record, requested from the UI; picked up by the pipeline thread. */
    @Volatile private var recordRequest = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "onStartCommand action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_RECORD -> {
                if (job?.isActive != true) {
                    // Not receiving: this start only created the service; don't leave it running.
                    stopSelfResult(startId)
                } else if (ReceiverState.recording.value !is RecordingState.Recording) {
                    recordRequest = intent.getIntExtra(EXTRA_SECONDS, 30)
                    ReceiverState.setRecording(RecordingState.Recording(0, recordRequest))
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopReceiver()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification("Connecting…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                acquireWakeLock()
                val settings = Settings.load(this)
                val source: SampleSource = when (settings.mode) {
                    SourceMode.USB -> UsbSampleSource(applicationContext)
                    SourceMode.DRIVER_APP -> RtlTcpSampleSource(RtlSdrDriver.HOST, settings.port)
                    SourceMode.NETWORK -> RtlTcpSampleSource(settings.host, settings.port)
                }
                ReceiverState.setStatus(ReceiverStatus.Connecting(source.description))
                val previous = job
                job = lifecycleScope.launch(Dispatchers.IO) {
                    previous?.cancelAndJoin() // release the dongle before reopening it
                    runPipeline(source, settings.tunerConfig, reconnect = settings.mode != SourceMode.DRIVER_APP, startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runPipeline(source: SampleSource, config: TunerConfig, reconnect: Boolean, startId: Int) {
        val target = source.description
        // Shared across reconnects, so aircraft survive a short dropout.
        val decoder = ModeSDecoder()
        val db = runCatching { AircraftDb.open(this) }
            .onFailure { Log.w(TAG, "aircraft database unavailable", it) }.getOrNull()
        val tracker = AircraftTracker(lookup = { db?.lookup(it) })
        var messagesInWindow = 0
        var rate = 0f
        var lastPublish = System.currentTimeMillis()
        var lastNotify = 0L
        var attempt = 0
        var recorder: IqRecorder? = null
        var bytesInWindow = 0L
        var lastLog = System.currentTimeMillis()

        val demod = ModeSDemodulator(config.sampleRate) { frame, signal ->
            val now = System.currentTimeMillis()
            val msg = decoder.decode(frame, now) ?: return@ModeSDemodulator false
            tracker.update(msg, signal, now)
            messagesInWindow++
            true
        }

        while (true) {
            try {
                demod.reset()
                source.stream(
                    config,
                    onInfo = { info ->
                        attempt = 0
                        val gain = config.gainTenthsDb?.let { "%.1f dB".format(it / 10.0) } ?: "auto"
                        ReceiverState.setStatus(
                            ReceiverStatus.Running(target, info.tunerName, gain, config.sampleRate, config.biasTee)
                        )
                    },
                ) { buf, len ->
                    demod.process(buf, len)
                    bytesInWindow += len
                    val request = recordRequest
                    if (request > 0 && recorder == null) {
                        recordRequest = 0
                        recorder = try {
                            IqRecorder(this, config.sampleRate, request).also { r ->
                                r.onFinished = { ReceiverState.setRecording(it) }
                            }
                        } catch (e: Exception) {
                            ReceiverState.setRecording(RecordingState.Failed(e.message ?: "Cannot record"))
                            null
                        }
                    }
                    recorder?.let { r -> if (!r.write(buf, len)) recorder = null }
                    val now = System.currentTimeMillis()
                    if (now - lastPublish >= PUBLISH_INTERVAL_MS) {
                        tracker.receiverPosition = ReceiverState.receiverPosition.value
                        tracker.expire(now)
                        val snapshot = tracker.snapshot()
                        val instant = messagesInWindow * 1000f / (now - lastPublish)
                        // Smoothed over a few seconds: per-window counts are tiny and jumpy.
                        rate = if (rate == 0f) instant else rate * 0.8f + instant * 0.2f
                        ReceiverState.publish(
                            snapshot,
                            ReceiverStats(
                                messagesPerSec = rate,
                                totalMessages = decoder.acceptedFrames,
                                correctedMessages = decoder.correctedFrames,
                                withPosition = snapshot.count { it.position != null },
                            ),
                        )
                        messagesInWindow = 0
                        lastPublish = now
                        recorder?.let { r ->
                            ReceiverState.setRecording(RecordingState.Recording(r.progressSeconds, r.totalSeconds))
                        }
                        if (now - lastNotify >= NOTIFY_INTERVAL_MS) {
                            lastNotify = now
                            // Input rate should match the sample rate; less means samples are being lost.
                            val inputMsps = bytesInWindow / 2.0 / (now - lastLog) / 1000.0
                            bytesInWindow = 0
                            lastLog = now
                            Log.i(
                                TAG, "stats: aircraft=%d pos=%d msgs=%d rate=%.1f/s fixed=%d preambles=%d noise=%.2f input=%.2f MS/s"
                                    .format(
                                        snapshot.size, snapshot.count { it.position != null }, decoder.acceptedFrames,
                                        rate, decoder.correctedFrames, demod.preamblesDetected, demod.noiseFloor, inputMsps,
                                    ),
                            )
                            updateNotification("${snapshot.size} aircraft · ${"%.0f".format(rate)} msg/s")
                        }
                    }
                }
            } catch (e: CancellationException) {
                recorder?.stop()
                throw e
            } catch (e: Exception) {
                recorder?.stop()
                recorder = null
                Log.w(TAG, "pipeline failed (startId=$startId, attempt=$attempt)", e)
                val message = e.message ?: e.javaClass.simpleName
                if (!reconnect || e is UnsupportedHardwareException) {
                    ReceiverState.setStatus(ReceiverStatus.Error(message))
                    // Only stops if no newer start arrived meanwhile (e.g. the user pressed Start again);
                    // a plain stopSelf() here would kill that new run.
                    stopSelfResult(startId)
                    return
                }
                val waitS = RETRY_DELAYS_S[minOf(attempt, RETRY_DELAYS_S.lastIndex)]
                attempt++
                ReceiverState.setStatus(ReceiverStatus.Reconnecting(target, message, waitS))
                updateNotification("Reconnecting in $waitS s – $message")
                delay(waitS * 1000L)
            }
        }
    }

    private fun stopReceiver() {
        job?.cancel()
        job = null
        ReceiverState.setStatus(ReceiverStatus.Idle)
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Keeps the CPU running with the screen off; the foreground service alone doesn't. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SdrPlanes:receiver")
            .apply { setReferenceCounted(false); acquire() }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        job?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        // Destroyed while active (e.g. by the system): don't leave a stale "Receiving".
        val s = ReceiverState.status.value
        if (s is ReceiverStatus.Running || s is ReceiverStatus.Connecting || s is ReceiverStatus.Reconnecting) {
            ReceiverState.setStatus(ReceiverStatus.Idle)
        }
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Receiver", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_plane)
            .setContentTitle("SdrPlanes receiving 1090 MHz")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setColor(getColor(R.color.sunset))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val TAG = "ReceiverService"
        private const val ACTION_START = "com.sdrplanes.START"
        private const val ACTION_STOP = "com.sdrplanes.STOP"
        private const val ACTION_RECORD = "com.sdrplanes.RECORD"
        private const val EXTRA_SECONDS = "seconds"
        private const val CHANNEL_ID = "receiver"
        private const val NOTIFICATION_ID = 1
        private const val PUBLISH_INTERVAL_MS = 500L
        private const val NOTIFY_INTERVAL_MS = 5_000L
        private val RETRY_DELAYS_S = intArrayOf(1, 2, 5, 10, 15)

        /** Starts (or restarts) reception with the saved [Settings]. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ReceiverService::class.java).setAction(ACTION_START),
            )
        }

        /** Records [seconds] of raw IQ to Downloads/SdrPlanes while receiving. */
        fun record(context: Context, seconds: Int = 30) {
            context.startService(
                Intent(context, ReceiverService::class.java).setAction(ACTION_RECORD).putExtra(EXTRA_SECONDS, seconds)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ReceiverService::class.java).setAction(ACTION_STOP))
        }
    }
}
