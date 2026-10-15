package ir.raha.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Discovering : ConnectionState
    data class Testing(val candidates: Int) : ConnectionState
    data class Connected(
        val relayName: String,
        val countryCode: String?,
        val city: String?,
        val latencyMs: Long,
        /** SystemClock.elapsedRealtime() when the tunnel came up. */
        val sinceElapsed: Long
    ) : ConnectionState
    /** The tunnel stays up while Raha moves to another relay. */
    data class Reconnecting(val reason: String, val sinceElapsed: Long) : ConnectionState
    data object Stopping : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

/** Process-wide connection state; the service writes, the UI observes. */
object RahaState {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    internal fun set(value: ConnectionState) {
        _state.value = value
    }
}
