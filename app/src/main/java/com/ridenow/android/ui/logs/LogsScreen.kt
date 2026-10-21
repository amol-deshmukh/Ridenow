package com.ridenow.android.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridenow.android.data.GrpcCallLog
import com.ridenow.android.ui.common.SectionTitle

/** Everything the client-side LoggingClientInterceptor saw, newest first. */
@Composable
fun LogsScreen(callLog: GrpcCallLog, serverAddress: String, modifier: Modifier = Modifier) {
    val entries by callLog.entries.collectAsStateWithLifecycle()

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SectionTitle("Client interceptor log", "server: $serverAddress") }
            OutlinedButton(onClick = callLog::clear) { Text("Clear") }
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(entries) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 4.dp))
                HorizontalDivider()
            }
        }
    }
}
