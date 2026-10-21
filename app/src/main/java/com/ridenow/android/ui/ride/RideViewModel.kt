package com.ridenow.android.ui.ride

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ridenow.android.RideNowApp
import com.ridenow.android.data.RideRepository
import com.ridenow.android.data.toUserMessage
import com.ridenow.proto.v1.DriverLocation
import com.ridenow.proto.v1.FareEstimateResponse
import com.ridenow.proto.v1.LatLng
import com.ridenow.proto.v1.RideResponse
import com.ridenow.proto.v1.RideType
import com.ridenow.proto.v1.latLng
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Tracking {
    data object Idle : Tracking
    data object Connecting : Tracking
    data object Live : Tracking
    data class Reconnecting(val attempt: Long) : Tracking
    data object Stopped : Tracking
    data object Completed : Tracking
}

data class RideUiState(
    val pickup: LatLng = latLng { lat = 12.9352; lng = 77.6245 },   // Koramangala, Bengaluru
    val dropoff: LatLng = latLng { lat = 12.9784; lng = 77.6408 },  // Indiranagar, Bengaluru
    val rideType: RideType = RideType.RIDE_TYPE_ECONOMY,
    val loading: Boolean = false,
    val estimate: FareEstimateResponse? = null,
    val idempotencyKey: String? = null,
    val ride: RideResponse? = null,
    val driverLocation: DriverLocation? = null,
    val driverTrail: List<LatLng> = emptyList(),
    val tracking: Tracking = Tracking.Idle,
    val error: String? = null,
)

class RideViewModel(private val repository: RideRepository) : ViewModel() {

    private val _state = MutableStateFlow(RideUiState())
    val state: StateFlow<RideUiState> = _state.asStateFlow()

    private var trackingJob: Job? = null

    fun selectRideType(type: RideType) = _state.update { it.copy(rideType = type, estimate = null) }

    // ---- 1. Unary ----------------------------------------------------------
    fun getEstimate() {
        val s = _state.value
        _state.update { it.copy(loading = true, error = null) }
        // viewModelScope: if the user leaves the screen for good, the coroutine
        // is cancelled, which cancels the in-flight RPC (sends RST_STREAM).
        viewModelScope.launch {
            runCatching { repository.getFareEstimate(s.pickup, s.dropoff, s.rideType) }
                .onSuccess { est ->
                    // New key per booking *intent*. Re-tapping "Request" after a
                    // timeout reuses it, so the server won't book two cars.
                    _state.update { it.copy(loading = false, estimate = est, idempotencyKey = UUID.randomUUID().toString()) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toUserMessage()) } }
        }
    }

    fun requestRide() {
        val s = _state.value
        val estimate = s.estimate ?: return
        val key = s.idempotencyKey ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.requestRide(estimate.estimateId, key) }
                .onSuccess { ride ->
                    _state.update { it.copy(loading = false, ride = ride) }
                    startTracking()
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toUserMessage()) } }
        }
    }

    // ---- 2. Server streaming -----------------------------------------------
    fun startTracking() {
        val rideId = _state.value.ride?.rideId ?: return
        trackingJob?.cancel()
        _state.update { it.copy(tracking = Tracking.Connecting, error = null) }

        trackingJob = viewModelScope.launch {
            repository.trackDriver(rideId) { attempt, _ ->
                _state.update { it.copy(tracking = Tracking.Reconnecting(attempt)) }
            }
                .onCompletion { cause ->
                    // cause == null -> server completed the stream normally (status OK)
                    if (cause == null) _state.update { it.copy(tracking = Tracking.Completed) }
                }
                .catch { e -> _state.update { it.copy(tracking = Tracking.Stopped, error = e.toUserMessage()) } }
                .collect { location ->
                    _state.update {
                        it.copy(
                            tracking = Tracking.Live,
                            driverLocation = location,
                            driverTrail = (it.driverTrail + location.position).takeLast(120),
                        )
                    }
                }
        }
    }

    /** Cancelling the collecting coroutine cancels the RPC; the server sees CANCELLED. */
    fun stopTracking() {
        trackingJob?.cancel()
        _state.update { it.copy(tracking = Tracking.Stopped) }
    }

    fun reset() {
        trackingJob?.cancel()
        _state.value = RideUiState(rideType = _state.value.rideType)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    companion object {
        val Factory = viewModelFactory {
            initializer { RideViewModel((this[APPLICATION_KEY] as RideNowApp).container.rideRepository) }
        }
    }
}
