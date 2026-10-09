package com.sdrplanes.service

import com.sdrplanes.decode.LatLon
import com.sdrplanes.model.Aircraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ReceiverStatus {
    data object Idle : ReceiverStatus
    data class Connecting(val target: String) : ReceiverStatus
    data class Running(
        val target: String, val tuner: String, val gain: String,
        val sampleRate: Int, val biasTee: Boolean,
    ) : ReceiverStatus
    /** The source dropped; retrying in [inSeconds]. */
    data class Reconnecting(val target: String, val message: String, val inSeconds: Int) : ReceiverStatus
    data class Error(val message: String) : ReceiverStatus
}

data class ReceiverStats(
    val messagesPerSec: Float = 0f,
    val totalMessages: Long = 0,
    val correctedMessages: Long = 0,
    val withPosition: Int = 0,
)

/** Process-wide state shared between [ReceiverService] (writer) and the UI (reader). */
object ReceiverState {
    private val _status = MutableStateFlow<ReceiverStatus>(ReceiverStatus.Idle)
    val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    private val _aircraft = MutableStateFlow<List<Aircraft>>(emptyList())
    val aircraft: StateFlow<List<Aircraft>> = _aircraft.asStateFlow()

    private val _stats = MutableStateFlow(ReceiverStats())
    val stats: StateFlow<ReceiverStats> = _stats.asStateFlow()

    private val _recording = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recording: StateFlow<RecordingState> = _recording.asStateFlow()

    private val _receiverPosition = MutableStateFlow<LatLon?>(null)
    val receiverPosition: StateFlow<LatLon?> = _receiverPosition.asStateFlow()

    internal fun setStatus(s: ReceiverStatus) { _status.value = s }
    internal fun publish(aircraft: List<Aircraft>, stats: ReceiverStats) {
        _aircraft.value = aircraft
        _stats.value = stats
    }
    fun setReceiverPosition(p: LatLon?) { _receiverPosition.value = p }
    fun setRecording(r: RecordingState) { _recording.value = r }
}
