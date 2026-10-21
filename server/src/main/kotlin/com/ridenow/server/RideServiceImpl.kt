package com.ridenow.server

import com.ridenow.proto.v1.ChatClientEvent
import com.ridenow.proto.v1.ChatMessage
import com.ridenow.proto.v1.ChatRole
import com.ridenow.proto.v1.DriverLocation
import com.ridenow.proto.v1.FareEstimateRequest
import com.ridenow.proto.v1.FareEstimateResponse
import com.ridenow.proto.v1.LocationPoint
import com.ridenow.proto.v1.RideRequest
import com.ridenow.proto.v1.RideResponse
import com.ridenow.proto.v1.RideServiceGrpcKt
import com.ridenow.proto.v1.RideStatus
import com.ridenow.proto.v1.RideType
import com.ridenow.proto.v1.TrackDriverRequest
import com.ridenow.proto.v1.UploadSummary
import com.ridenow.proto.v1.chatMessage
import com.ridenow.proto.v1.driverLocation
import com.ridenow.proto.v1.fareEstimateResponse
import com.ridenow.proto.v1.latLng
import com.ridenow.proto.v1.rideResponse
import com.ridenow.proto.v1.uploadSummary
import io.grpc.Status
import io.grpc.StatusException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/**
 * Implementation of the generated `RideServiceCoroutineImplBase`.
 *
 * grpc-kotlin maps each RPC type to an idiomatic Kotlin signature:
 *   unary            suspend fun x(req): Resp
 *   server streaming fun x(req): Flow<Resp>
 *   client streaming suspend fun x(reqs: Flow<Req>): Resp
 *   bidi streaming   fun x(reqs: Flow<Req>): Flow<Resp>
 *
 * Errors: throw [StatusException] with a proper [Status] code. The client
 * receives exactly that code + description. Any other exception becomes UNKNOWN.
 *
 * State is in-memory (ConcurrentHashMap) — a real system would use a DB / Redis.
 */
class RideServiceImpl(
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 1_000,
) : RideServiceGrpcKt.RideServiceCoroutineImplBase() {

    private val estimates = ConcurrentHashMap<String, Estimate>()
    private val rides = ConcurrentHashMap<String, Ride>()
    private val ridesByIdempotencyKey = ConcurrentHashMap<String, String>()
    private val chatRooms = ConcurrentHashMap<String, ChatRoom>()

    // =========================================================================
    // 1a. UNARY — GetFareEstimate
    // =========================================================================
    override suspend fun getFareEstimate(request: FareEstimateRequest): FareEstimateResponse {
        // Validate input -> INVALID_ARGUMENT (client bug, do NOT retry).
        if (!request.hasPickup() || !request.hasDropoff()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("pickup and dropoff are required"))
        }
        if (request.rideType == RideType.RIDE_TYPE_UNSPECIFIED) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("ride_type is required"))
        }
        val distanceKm = haversineKm(request.pickup, request.dropoff)
        if (distanceKm > 100) {
            throw StatusException(Status.FAILED_PRECONDITION.withDescription("Trip is outside the service area"))
        }

        delay(300) // pretend we're calling pricing + dispatch services

        val surge = listOf(1.0, 1.0, 1.0, 1.2, 1.5).random()
        val estimate = Estimate(
            id = "est-" + UUID.randomUUID().toString().take(8),
            pickup = request.pickup,
            dropoff = request.dropoff,
            rideType = request.rideType,
            fareMinorUnits = Pricing.fare(request.rideType, distanceKm, surge),
            expiresAtMs = clock() + 2 * 60_000,
        )
        estimates[estimate.id] = estimate

        return fareEstimateResponse {
            estimateId = estimate.id
            fareMinorUnits = estimate.fareMinorUnits
            currency = "INR"
            this.distanceKm = distanceKm
            pickupEtaSeconds = Simulation.ARRIVE_SECONDS
            surgeMultiplier = surge
            expiresAtEpochMs = estimate.expiresAtMs
        }
    }

    // =========================================================================
    // 1b. UNARY — RequestRide (idempotent via idempotency_key)
    // =========================================================================
    override suspend fun requestRide(request: RideRequest): RideResponse {
        // Mobile networks drop responses. If the client retries with the same
        // key, return the SAME ride instead of booking a second car.
        if (request.idempotencyKey.isNotBlank()) {
            ridesByIdempotencyKey[request.idempotencyKey]?.let { existingId ->
                log("RequestRide: duplicate idempotency key, returning existing ride $existingId")
                return rides.getValue(existingId).toResponse()
            }
        }

        val estimate = estimates[request.estimateId]
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Unknown estimate_id '${request.estimateId}'"))
        if (clock() > estimate.expiresAtMs) {
            throw StatusException(Status.FAILED_PRECONDITION.withDescription("Estimate expired, fetch a new one"))
        }

        val ride = Ride(
            id = "ride-" + UUID.randomUUID().toString().take(8),
            riderId = Auth.currentUserId(),   // populated by AuthInterceptor
            driver = DriverPool.assign(),
            pickup = estimate.pickup,
            dropoff = estimate.dropoff,
            driverStart = latLng { lat = estimate.pickup.lat + 0.012; lng = estimate.pickup.lng - 0.009 },
            createdAtMs = clock(),
        )

        if (request.idempotencyKey.isNotBlank()) {
            val winner = ridesByIdempotencyKey.putIfAbsent(request.idempotencyKey, ride.id)
            if (winner != null) return rides.getValue(winner).toResponse() // lost a race
        }
        rides[ride.id] = ride
        estimates.remove(estimate.id) // an estimate can be used once
        log("RequestRide: ${ride.id} assigned to ${ride.driver.name} for rider ${ride.riderId}")
        return ride.toResponse()
    }

    // =========================================================================
    // 2. SERVER STREAMING — TrackDriver
    // =========================================================================
    override fun trackDriver(request: TrackDriverRequest): Flow<DriverLocation> = flow {
        val ride = rides[request.rideId]
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Unknown ride_id '${request.rideId}'"))
        if (ride.riderId != Auth.currentUserId()) {
            throw StatusException(Status.PERMISSION_DENIED.withDescription("Not your ride"))
        }

        while (true) {
            val snap = Simulation.snapshot(ride, clock())
            emit(
                driverLocation {
                    rideId = ride.id
                    position = snap.position
                    bearingDegrees = snap.bearing
                    etaSeconds = snap.etaSeconds
                    status = snap.status
                    timestampEpochMs = clock()
                }
            )
            if (snap.status == RideStatus.RIDE_STATUS_COMPLETED) break // completes stream with OK
            delay(tickMs)
        }
    }.onCompletion { cause ->
        // When the client cancels (screen closed, deadline hit, network lost),
        // gRPC cancels this coroutine — no leaked work on the server.
        if (cause is CancellationException) log("TrackDriver(${request.rideId}): client cancelled the stream")
    }

    // =========================================================================
    // 3. CLIENT STREAMING — UploadLocations
    // =========================================================================
    override suspend fun uploadLocations(requests: Flow<LocationPoint>): UploadSummary {
        var count = 0
        var distanceKm = 0.0
        var maxSpeed = 0f
        var first: LocationPoint? = null
        var previous: LocationPoint? = null

        // `collect` returns when the client half-closes (calls onCompleted / the
        // client Flow finishes). Only then do we send the single response.
        requests.collect { point ->
            count++
            if (first == null) first = point
            previous?.let { distanceKm += haversineKm(it.position, point.position) }
            maxSpeed = maxOf(maxSpeed, point.speedMps)
            previous = point
            if (count % 10 == 0) log("UploadLocations: received $count points so far…")
        }

        val start = first ?: throw StatusException(Status.INVALID_ARGUMENT.withDescription("No points uploaded"))
        return uploadSummary {
            pointsReceived = count
            this.distanceKm = distanceKm
            durationMs = previous!!.timestampEpochMs - start.timestampEpochMs
            maxSpeedMps = maxSpeed
        }
    }

    // =========================================================================
    // 4. BIDIRECTIONAL STREAMING — TripChat
    // =========================================================================
    override fun tripChat(requests: Flow<ChatClientEvent>): Flow<ChatMessage> = channelFlow {
        val userId = Auth.currentUserId()
        var room: ChatRoom? = null
        var role = ChatRole.CHAT_ROLE_UNSPECIFIED
        var subscription: Job? = null

        try {
            // Reading (requests) and writing (send) happen independently: the
            // subscription coroutine pushes messages from other participants
            // while this loop is suspended waiting for the client's next event.
            requests.collect { event ->
                when (event.eventCase) {
                    ChatClientEvent.EventCase.JOIN -> {
                        if (room != null) {
                            throw StatusException(Status.FAILED_PRECONDITION.withDescription("Already joined"))
                        }
                        val joined = chatRooms.computeIfAbsent(event.join.rideId) { ChatRoom(event.join.rideId) }
                        room = joined
                        role = event.join.role
                        if (role == ChatRole.CHAT_ROLE_DRIVER) joined.driversOnline.incrementAndGet()
                        // UNDISPATCHED: subscribe synchronously so we can't miss our own join message.
                        subscription = launch(start = CoroutineStart.UNDISPATCHED) {
                            joined.messages.collect { send(it) }
                        }
                        joined.post(ChatRole.CHAT_ROLE_SYSTEM, "system", "$userId joined as ${role.label()}", clock())
                    }

                    ChatClientEvent.EventCase.TEXT -> {
                        val current = room ?: throw StatusException(
                            Status.FAILED_PRECONDITION.withDescription("Send JoinChat before any text")
                        )
                        current.post(role, userId, event.text, clock())
                        // No real driver connected? A bot plays the driver.
                        if (role == ChatRole.CHAT_ROLE_RIDER && current.driversOnline.get() == 0) {
                            launch {
                                delay(1_200)
                                val driverName = rides[current.rideId]?.driver?.name ?: "Driver (bot)"
                                current.post(ChatRole.CHAT_ROLE_DRIVER, driverName, botReply(event.text), clock())
                            }
                        }
                    }

                    else -> throw StatusException(Status.INVALID_ARGUMENT.withDescription("Empty chat event"))
                }
            }
        } finally {
            if (role == ChatRole.CHAT_ROLE_DRIVER) room?.driversOnline?.decrementAndGet()
        }

        // Client half-closed ("I'm done sending") — end our side of the stream too.
        log("TripChat: $userId left room ${room?.rideId}")
        subscription?.cancel()
    }

    // ---- helpers ------------------------------------------------------------

    private fun Ride.toResponse(): RideResponse = rideResponse {
        rideId = id
        status = RideStatus.RIDE_STATUS_DRIVER_ASSIGNED
        driver = this@toResponse.driver
        pickup = this@toResponse.pickup
        dropoff = this@toResponse.dropoff
    }

    private fun botReply(text: String): String = when {
        text.contains("where", ignoreCase = true) -> "I'm on the way, see the live map 🚗"
        text.contains("wait", ignoreCase = true) -> "Sure, I'll wait."
        text.contains("hi", ignoreCase = true) || text.contains("hello", ignoreCase = true) -> "Hello! Arriving shortly."
        else -> "Got it 👍"
    }
}

/** A chat room per ride. `replay` gives late joiners the recent history. */
class ChatRoom(val rideId: String) {
    val messages = MutableSharedFlow<ChatMessage>(replay = 50, extraBufferCapacity = 64)
    val driversOnline = AtomicInteger(0)

    suspend fun post(role: ChatRole, senderId: String, text: String, nowMs: Long) {
        messages.emit(
            chatMessage {
                messageId = UUID.randomUUID().toString()
                this.rideId = this@ChatRoom.rideId
                this.senderId = senderId
                senderRole = role
                this.text = text
                timestampEpochMs = nowMs
            }
        )
    }
}

private fun ChatRole.label() = when (this) {
    ChatRole.CHAT_ROLE_RIDER -> "rider"
    ChatRole.CHAT_ROLE_DRIVER -> "driver"
    else -> "guest"
}
