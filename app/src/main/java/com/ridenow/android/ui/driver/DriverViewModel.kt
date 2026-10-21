package com.ridenow.android.ui.driver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ridenow.android.RideNowApp
import com.ridenow.android.data.RideRepository
import com.ridenow.android.data.toUserMessage
import com.ridenow.proto.v1.LocationPoint
import com.ridenow.proto.v1.UploadSummary
import com.ridenow.proto.v1.latLng
import com.ridenow.proto.v1.locationPoint
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DriverUiState(
    val totalPoints: Int = 60,
    val sentPoints: Int = 0,
    val uploading: Boolean = false,
    val summary: UploadSummary? = null,
    val error: String? = null,
)

/**
 * Simulates the DRIVER app: GPS points were buffered locally (e.g. in Room)
 * while offline, and are now flushed to the server in ONE client-streaming call
 * instead of 60 separate HTTP requests.
 */
class DriverViewModel(private val repository: RideRepository) : ViewModel() {

    private val _state = MutableStateFlow(DriverUiState())
    val state: StateFlow<DriverUiState> = _state.asStateFlow()

    private var uploadJob: Job? = null

    // ---- 3. Client streaming -------------------------------------------------
    fun uploadTrip() {
        val total = _state.value.totalPoints
        _state.value = DriverUiState(totalPoints = total, uploading = true)
        uploadJob = viewModelScope.launch {
            try {
                val summary = repository.uploadLocations(bufferedGpsPoints(total))
                _state.update { it.copy(uploading = false, summary = summary) }
            } catch (e: CancellationException) {
                throw e // cancelled by us — never swallow cancellation
            } catch (e: Exception) {
                _state.update { it.copy(uploading = false, error = e.toUserMessage()) }
            }
        }
    }

    fun cancelUpload() {
        uploadJob?.cancel() // cancels the RPC; server's collect() fails with CANCELLED
        _state.update { it.copy(uploading = false, error = "Upload cancelled by user") }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * A cold Flow of requests. grpc-kotlin collects it and writes each element to
     * the HTTP/2 stream; if the server/network is slow, HTTP/2 flow control makes
     * `emit` SUSPEND — that's backpressure for free. When the Flow completes,
     * the client half-closes and the server sends its single response.
     */
    private fun bufferedGpsPoints(count: Int): Flow<LocationPoint> = flow {
        val start = latLng { lat = 12.9352; lng = 77.6245 }
        val startTime = System.currentTimeMillis() - count * 5_000L
        repeat(count) { i ->
            emit(
                locationPoint {
                    driverId = "d-101"
                    position = latLng {
                        lat = start.lat + i * 0.0007 + Random.nextDouble(-0.0001, 0.0001)
                        lng = start.lng + i * 0.0003 + Random.nextDouble(-0.0001, 0.0001)
                    }
                    speedMps = Random.nextDouble(4.0, 16.0).toFloat()
                    timestampEpochMs = startTime + i * 5_000L // one GPS fix every 5s
                }
            )
            _state.update { it.copy(sentPoints = i + 1) }
            delay(50) // slow it down so you can watch the progress bar
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { DriverViewModel((this[APPLICATION_KEY] as RideNowApp).container.rideRepository) }
        }
    }
}
