package com.ridenow.android.ui.ride

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridenow.proto.v1.LatLng
import com.ridenow.proto.v1.RideStatus
import com.ridenow.proto.v1.RideType
import com.ridenow.android.ui.common.ErrorCard
import com.ridenow.android.ui.common.SectionTitle
import java.util.Locale

@Composable
fun RideScreen(viewModel: RideViewModel, modifier: Modifier = Modifier) {
    // collectAsStateWithLifecycle stops collecting UI state in the background.
    // The gRPC stream itself lives in viewModelScope, so it survives rotation.
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("1 · Unary", "GetFareEstimate → RequestRide")

        Text("Koramangala → Indiranagar", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                RideType.RIDE_TYPE_ECONOMY to "Economy",
                RideType.RIDE_TYPE_PREMIUM to "Premium",
                RideType.RIDE_TYPE_XL to "XL",
            ).forEach { (type, label) ->
                FilterChip(
                    selected = state.rideType == type,
                    onClick = { viewModel.selectRideType(type) },
                    label = { Text(label) },
                    enabled = state.ride == null,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::getEstimate, enabled = !state.loading && state.ride == null) {
                Text("Get fare")
            }
            Button(onClick = viewModel::requestRide, enabled = !state.loading && state.estimate != null && state.ride == null) {
                Text("Request ride")
            }
            if (state.loading) CircularProgressIndicator(Modifier.padding(start = 8.dp))
        }

        state.estimate?.let { est ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "₹%.2f  ·  %.1f km".format(Locale.US, est.fareMinorUnits / 100.0, est.distanceKm),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text("Surge ×${est.surgeMultiplier} · pickup in ~${est.pickupEtaSeconds}s")
                    Text("estimate_id = ${est.estimateId}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        state.error?.let { ErrorCard(it, onDismiss = viewModel::dismissError) }

        state.ride?.let { ride ->
            SectionTitle("2 · Server streaming", "TrackDriver → stream DriverLocation")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${ride.driver.name} ★${ride.driver.rating}", style = MaterialTheme.typography.titleMedium)
                    Text("${ride.driver.vehicle} · ${ride.driver.plate}")
                    Text("ride_id = ${ride.rideId}", style = MaterialTheme.typography.labelSmall)
                }
            }

            Text("Stream: ${state.tracking.label()}", style = MaterialTheme.typography.titleSmall)
            state.driverLocation?.let { loc ->
                Text("${loc.status.label()} · ETA ${loc.etaSeconds}s")
                Text(
                    "(%.5f, %.5f) heading %.0f°".format(Locale.US, loc.position.lat, loc.position.lng, loc.bearingDegrees),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            MiniMap(
                pickup = state.pickup,
                dropoff = state.dropoff,
                trail = state.driverTrail,
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val streaming = state.tracking is Tracking.Live ||
                    state.tracking is Tracking.Connecting || state.tracking is Tracking.Reconnecting
                if (streaming) {
                    OutlinedButton(onClick = viewModel::stopTracking) { Text("Stop stream (cancel)") }
                } else if (state.tracking != Tracking.Completed) {
                    OutlinedButton(onClick = viewModel::startTracking) { Text("Resume stream") }
                }
                OutlinedButton(onClick = viewModel::reset) { Text("New ride") }
            }
        }
    }
}

/** A tiny "map": projects lat/lng into the canvas bounds. Enough to see the stream move. */
@Composable
private fun MiniMap(pickup: LatLng, dropoff: LatLng, trail: List<LatLng>, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val driverColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val all = trail + pickup + dropoff
        val minLat = all.minOf { it.lat } - 0.002
        val maxLat = all.maxOf { it.lat } + 0.002
        val minLng = all.minOf { it.lng } - 0.002
        val maxLng = all.maxOf { it.lng } + 0.002
        fun LatLng.toOffset() = Offset(
            x = ((lng - minLng) / (maxLng - minLng) * size.width).toFloat(),
            y = ((maxLat - lat) / (maxLat - minLat) * size.height).toFloat(),
        )

        drawRect(outline, style = Stroke(1.dp.toPx()))
        drawLine(outline, pickup.toOffset(), dropoff.toOffset(), strokeWidth = 2.dp.toPx())
        trail.zipWithNext().forEach { (a, b) ->
            drawLine(driverColor.copy(alpha = 0.5f), a.toOffset(), b.toOffset(), strokeWidth = 3.dp.toPx())
        }
        drawCircle(Color(0xFF2E7D32), 8.dp.toPx(), pickup.toOffset())   // pickup = green
        drawCircle(Color(0xFFC62828), 8.dp.toPx(), dropoff.toOffset())  // dropoff = red
        trail.lastOrNull()?.let { drawCircle(driverColor, 10.dp.toPx(), it.toOffset()) }
    }
}

private fun Tracking.label() = when (this) {
    Tracking.Idle -> "idle"
    Tracking.Connecting -> "connecting…"
    Tracking.Live -> "LIVE 🟢"
    is Tracking.Reconnecting -> "reconnecting (attempt $attempt)…"
    Tracking.Stopped -> "stopped"
    Tracking.Completed -> "completed (server closed stream with OK)"
}

private fun RideStatus.label() = when (this) {
    RideStatus.RIDE_STATUS_DRIVER_ASSIGNED -> "Driver assigned"
    RideStatus.RIDE_STATUS_DRIVER_ARRIVING -> "Driver arriving"
    RideStatus.RIDE_STATUS_DRIVER_ARRIVED -> "Driver arrived"
    RideStatus.RIDE_STATUS_IN_PROGRESS -> "Trip in progress"
    RideStatus.RIDE_STATUS_COMPLETED -> "Trip completed 🎉"
    RideStatus.RIDE_STATUS_CANCELLED -> "Cancelled"
    else -> "Unknown"
}
