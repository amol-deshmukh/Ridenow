package com.ridenow.android

import android.app.Application
import com.ridenow.android.data.GrpcCallLog
import com.ridenow.android.data.GrpcClient
import com.ridenow.android.data.RideRepository
import com.ridenow.proto.v1.RideServiceGrpcKt.RideServiceCoroutineStub

class RideNowApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Manual DI (in a real app: Hilt). The channel is an app-scoped singleton:
 * creating one per screen would mean a new TCP/TLS handshake per screen.
 */
class AppContainer(app: Application) {
    val callLog = GrpcCallLog()

    // The demo server accepts any "demo-<user>" token. In a real app this
    // comes from your auth/session layer and is refreshed when it expires.
    val riderId = "rider42"

    private val grpcClient by lazy {
        GrpcClient(
            context = app,
            host = BuildConfig.GRPC_HOST,
            port = BuildConfig.GRPC_PORT,
            tokenProvider = { "demo-$riderId" },
            callLog = callLog,
        )
    }

    val rideRepository by lazy { RideRepository(RideServiceCoroutineStub(grpcClient.channel)) }

    val serverAddress = "${BuildConfig.GRPC_HOST}:${BuildConfig.GRPC_PORT}"
}
