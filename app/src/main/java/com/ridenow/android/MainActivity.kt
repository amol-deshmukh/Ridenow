package com.ridenow.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ridenow.android.ui.chat.ChatScreen
import com.ridenow.android.ui.chat.ChatViewModel
import com.ridenow.android.ui.driver.DriverScreen
import com.ridenow.android.ui.driver.DriverViewModel
import com.ridenow.android.ui.logs.LogsScreen
import com.ridenow.android.ui.ride.RideScreen
import com.ridenow.android.ui.ride.RideViewModel
import com.ridenow.android.ui.theme.RideNowTheme

private enum class Tab(val label: String, val icon: String) {
    Ride("Ride", "🚕"),       // unary + server streaming
    Driver("Driver", "📍"),   // client streaming
    Chat("Chat", "💬"),       // bidirectional streaming
    Logs("Logs", "📜"),       // interceptor output
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RideNowApp).container

        setContent {
            RideNowTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                // Activity-scoped ViewModels: switching tabs doesn't kill streams.
                val rideVm: RideViewModel = viewModel(factory = RideViewModel.Factory)
                val driverVm: DriverViewModel = viewModel(factory = DriverViewModel.Factory)
                val chatVm: ChatViewModel = viewModel(factory = ChatViewModel.Factory)
                val rideState by rideVm.state.collectAsStateWithLifecycle()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEachIndexed { index, t ->
                                NavigationBarItem(
                                    selected = tab == index,
                                    onClick = { tab = index },
                                    icon = { Text(t.icon) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val m = Modifier.padding(padding)
                    when (Tab.entries[tab]) {
                        Tab.Ride -> RideScreen(rideVm, m)
                        Tab.Driver -> DriverScreen(driverVm, m)
                        Tab.Chat -> ChatScreen(chatVm, activeRideId = rideState.ride?.rideId, modifier = m)
                        Tab.Logs -> LogsScreen(container.callLog, container.serverAddress, m)
                    }
                }
            }
        }
    }
}
