package com.sdrplanes.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sdrplanes.MainActivity
import com.sdrplanes.R
import com.sdrplanes.decode.ModeSDecoder
import com.sdrplanes.dsp.ModeSDemodulator
import com.sdrplanes.model.AircraftTracker
import com.sdrplanes.sdr.RtlTcpSampleSource
import com.sdrplanes.sdr.SampleSource
import com.sdrplanes.sdr.TunerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the SDR connection and the
 * samples -> demodulator -> decoder -> tracker pipeline, so reception continues with the
 * screen off. Publishes results to [ReceiverState].
 */
class ReceiverService : LifecycleService() {
    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "onStartCommand action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_STOP -> {
                stopReceiver()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 1234)
                val gain = intent.getIntExtra(EXTRA_GAIN, -1).takeIf { it >= 0 }
                val ppm = intent.getIntExtra(EXTRA_PPM, 0)
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification("Connecting…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                val source = RtlTcpSampleSource(host, port)
                ReceiverState.setStatus(ReceiverStatus.Connecting(source.description))
                job?.cancel()
                job = lifecycleScope.launch(Dispatchers.IO) {
                    runPipeline(source, TunerConfig(gainTenthsDb = gain, ppm = ppm), startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runPipeline(source: SampleSource, config: TunerConfig, startId: Int) {
        val target = source.description

        val decoder = ModeSDecoder()
        val tracker = AircraftTracker()
        var messagesInWindow = 0
        var lastPublish = System.currentTimeMillis()
        var lastNotify = 0L

        val demod = ModeSDemodulator { frame, signal ->
            val now = System.currentTimeMillis()
            val msg = decoder.decode(frame, now) ?: return@ModeSDemodulator false
            tracker.update(msg, signal, now)
            messagesInWindow++
            true
        }

        try {
            source.stream(
                config,
                onInfo = { info ->
                    val gain = config.gainTenthsDb?.let { "%.1f dB".format(it / 10.0) } ?: "auto"
                    ReceiverState.setStatus(ReceiverStatus.Running(target, info.tunerName, gain))
                },
            ) { buf, len ->
                demod.process(buf, len)
                val now = System.currentTimeMillis()
                if (now - lastPublish >= PUBLISH_INTERVAL_MS) {
                    tracker.receiverPosition = ReceiverState.receiverPosition.value
                    tracker.expire(now)
                    val snapshot = tracker.snapshot()
                    val rate = (messagesInWindow * 1000L / (now - lastPublish)).toInt()
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
                    if (now - lastNotify >= NOTIFY_INTERVAL_MS) {
                        lastNotify = now
                        updateNotification("${snapshot.size} aircraft · $rate msg/s")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "pipeline failed (startId=$startId)", e)
            ReceiverState.setStatus(ReceiverStatus.Error(e.message ?: e.javaClass.simpleName))
            // Only stops if no newer start arrived meanwhile (e.g. the user pressed Start again);
            // a plain stopSelf() here would kill that new run.
            stopSelfResult(startId)
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

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        job?.cancel()
        // Destroyed while active (e.g. by the system): don't leave a stale "Receiving".
        val s = ReceiverState.status.value
        if (s is ReceiverStatus.Running || s is ReceiverStatus.Connecting) ReceiverState.setStatus(ReceiverStatus.Idle)
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
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_GAIN = "gain"
        private const val EXTRA_PPM = "ppm"
        private const val CHANNEL_ID = "receiver"
        private const val NOTIFICATION_ID = 1
        private const val PUBLISH_INTERVAL_MS = 500L
        private const val NOTIFY_INTERVAL_MS = 5_000L

        fun start(context: Context, host: String, port: Int, config: TunerConfig) {
            val i = Intent(context, ReceiverService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_GAIN, config.gainTenthsDb ?: -1)
                .putExtra(EXTRA_PPM, config.ppm)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ReceiverService::class.java).setAction(ACTION_STOP))
        }
    }
}
