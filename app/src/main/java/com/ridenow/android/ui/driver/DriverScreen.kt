package com.ridenow.android.ui.driver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridenow.android.ui.common.ErrorCard
import com.ridenow.android.ui.common.SectionTitle
import java.util.Locale

@Composable
fun DriverScreen(viewModel: DriverViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("3 · Client streaming", "UploadLocations(stream LocationPoint) → UploadSummary")
        Text(
            "Driver app was offline and buffered ${state.totalPoints} GPS fixes. " +
                "Flush them in one stream; the server answers once, after we half-close.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::uploadTrip, enabled = !state.uploading) { Text("Upload trip") }
            if (state.uploading) OutlinedButton(onClick = viewModel::cancelUpload) { Text("Cancel") }
        }

        if (state.uploading || state.sentPoints > 0) {
            LinearProgressIndicator(
                progress = { state.sentPoints / state.totalPoints.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Streamed ${state.sentPoints}/${state.totalPoints} points")
        }

        state.summary?.let { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Server summary (single response)", style = MaterialTheme.typography.titleMedium)
                    Text("Points received: ${s.pointsReceived}")
                    Text("Distance: %.2f km".format(Locale.US, s.distanceKm))
                    Text("Duration: ${s.durationMs / 1000}s")
                    Text("Max speed: %.1f m/s".format(Locale.US, s.maxSpeedMps))
                }
            }
        }

        state.error?.let { ErrorCard(it, onDismiss = viewModel::dismissError) }
    }
}
