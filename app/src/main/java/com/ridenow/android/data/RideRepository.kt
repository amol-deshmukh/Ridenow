package com.ridenow.android.data

import com.ridenow.proto.v1.ChatClientEvent
import com.ridenow.proto.v1.ChatMessage
import com.ridenow.proto.v1.DriverLocation
import com.ridenow.proto.v1.FareEstimateResponse
import com.ridenow.proto.v1.LatLng
import com.ridenow.proto.v1.LocationPoint
import com.ridenow.proto.v1.RideResponse
import com.ridenow.proto.v1.RideServiceGrpcKt.RideServiceCoroutineStub
import com.ridenow.proto.v1.RideType
import com.ridenow.proto.v1.UploadSummary
import com.ridenow.proto.v1.fareEstimateRequest
import com.ridenow.proto.v1.rideRequest
import com.ridenow.proto.v1.trackDriverRequest
import io.grpc.Status
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/**
 * The only class that knows about gRPC stubs. ViewModels see suspend funs and
 * Flows — swapping gRPC for REST/GraphQL would not touch the UI layer.
 *
 * Coroutine stub cheat sheet:
 *   unary            suspend fun          -> cancel the coroutine = cancel the RPC
 *   server streaming Flow<Resp>           -> cold: the RPC starts on collect()
 *   client streaming suspend fun(Flow)    -> we provide a Flow of requests
 *   bidi             fun(Flow): Flow      -> both directions are Flows
 */
class RideRepository(private val stub: RideServiceCoroutineStub) {

    // ---- Unary (deadline + retries come from the channel's service config) ---

    suspend fun getFareEstimate(pickup: LatLng, dropoff: LatLng, type: RideType): FareEstimateResponse =
        stub.getFareEstimate(
            fareEstimateRequest {
                this.pickup = pickup
                this.dropoff = dropoff
                rideType = type
            }
        )

    suspend fun requestRide(estimateId: String, idempotencyKey: String): RideResponse =
        stub.requestRide(
            rideRequest {
                this.estimateId = estimateId
                this.idempotencyKey = idempotencyKey
            }
        )

    // ---- Server streaming ----------------------------------------------------

    /**
     * Live driver location. No deadline: this stream is meant to be long-lived.
     *
     * Built-in gRPC retries do NOT cover a stream that already received data,
     * so we reconnect ourselves with exponential backoff. Because the server
     * derives location from its own state, re-subscribing simply resumes.
     */
    fun trackDriver(rideId: String, onReconnect: (attempt: Long, error: Throwable) -> Unit): Flow<DriverLocation> =
        stub.trackDriver(trackDriverRequest { this.rideId = rideId })
            .retryWhen { cause, attempt ->
                val retryable = cause.grpcCode() == Status.Code.UNAVAILABLE && attempt < MAX_STREAM_RECONNECTS
                if (retryable) {
                    onReconnect(attempt + 1, cause)
                    delay(min(1_000L shl attempt.toInt(), 16_000L)) // 1s, 2s, 4s, 8s, 16s
                }
                retryable
            }

    // ---- Client streaming ----------------------------------------------------

    /**
     * NOTE: `withDeadlineAfter` computes an ABSOLUTE deadline at the moment it is
     * called. Always derive a fresh stub per call — never store one in a field.
     */
    suspend fun uploadLocations(points: Flow<LocationPoint>): UploadSummary =
        stub.withDeadlineAfter(60, TimeUnit.SECONDS).uploadLocations(points)

    // ---- Bidirectional streaming ---------------------------------------------

    fun tripChat(outgoing: Flow<ChatClientEvent>): Flow<ChatMessage> = stub.tripChat(outgoing)

    private companion object {
        const val MAX_STREAM_RECONNECTS = 5L
    }
}
