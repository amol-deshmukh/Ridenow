package com.ridenow.server

import com.ridenow.proto.v1.ChatRole
import com.ridenow.proto.v1.RideServiceGrpcKt.RideServiceCoroutineStub
import com.ridenow.proto.v1.RideStatus
import com.ridenow.proto.v1.RideType
import com.ridenow.proto.v1.chatClientEvent
import com.ridenow.proto.v1.fareEstimateRequest
import com.ridenow.proto.v1.joinChat
import com.ridenow.proto.v1.latLng
import com.ridenow.proto.v1.locationPoint
import com.ridenow.proto.v1.rideRequest
import com.ridenow.proto.v1.trackDriverRequest
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Tests the real service + interceptors over gRPC's **in-process transport**:
 * full gRPC semantics (metadata, status codes, streaming) without opening a socket.
 */
class RideServiceTest {
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var stub: RideServiceCoroutineStub

    private val pickup = latLng { lat = 12.9352; lng = 77.6245 }
    private val dropoff = latLng { lat = 12.9784; lng = 77.6408 }

    @Before
    fun setUp() {
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(ServerInterceptors.intercept(RideServiceImpl(tickMs = 10), AuthInterceptor()))
            .build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        stub = RideServiceCoroutineStub(channel).withInterceptors(authHeader("demo-alice"))
    }

    @After
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `unary - fare estimate is computed`() = runBlocking {
        val resp = stub.getFareEstimate(fareRequest())
        assertTrue(resp.estimateId.startsWith("est-"))
        assertTrue(resp.fareMinorUnits > 0)
        assertEquals("INR", resp.currency)
    }

    @Test
    fun `unary - missing fields returns INVALID_ARGUMENT`() = runBlocking {
        assertStatus(Status.Code.INVALID_ARGUMENT) { stub.getFareEstimate(fareEstimateRequest { }) }
    }

    @Test
    fun `auth - missing token returns UNAUTHENTICATED`() = runBlocking {
        assertStatus(Status.Code.UNAUTHENTICATED) { RideServiceCoroutineStub(channel).getFareEstimate(fareRequest()) }
    }

    @Test
    fun `unary - request ride is idempotent`() = runBlocking {
        val estimate = stub.getFareEstimate(fareRequest())
        val first = stub.requestRide(rideRequest { estimateId = estimate.estimateId; idempotencyKey = "k1" })
        val retry = stub.requestRide(rideRequest { estimateId = estimate.estimateId; idempotencyKey = "k1" })
        assertEquals(first.rideId, retry.rideId)
    }

    @Test
    fun `server streaming - track driver emits updates`() = runBlocking {
        val ride = bookRide()
        val updates = stub.trackDriver(trackDriverRequest { rideId = ride }).take(3).toList()
        assertEquals(3, updates.size)
        assertEquals(RideStatus.RIDE_STATUS_DRIVER_ARRIVING, updates.first().status)
    }

    @Test
    fun `server streaming - other users cannot track my ride`() = runBlocking {
        val ride = bookRide()
        val bob = RideServiceCoroutineStub(channel).withInterceptors(authHeader("demo-bob"))
        assertStatus(Status.Code.PERMISSION_DENIED) { bob.trackDriver(trackDriverRequest { rideId = ride }).toList() }
    }

    @Test
    fun `client streaming - upload returns summary`() = runBlocking {
        val points = (0 until 20).map { i ->
            locationPoint {
                driverId = "d-1"
                position = latLng { lat = 12.90 + i * 0.001; lng = 77.60 }
                speedMps = i.toFloat()
                timestampEpochMs = i * 1_000L
            }
        }
        val summary = stub.uploadLocations(points.asFlow())
        assertEquals(20, summary.pointsReceived)
        assertEquals(19_000L, summary.durationMs)
        assertEquals(19f, summary.maxSpeedMps)
    }

    @Test
    fun `bidi streaming - rider gets bot reply`() = runBlocking {
        val outgoing = flow {
            emit(chatClientEvent { join = joinChat { rideId = "r-1"; role = ChatRole.CHAT_ROLE_RIDER } })
            emit(chatClientEvent { text = "hello" })
            kotlinx.coroutines.delay(2_000) // keep stream open long enough for the bot reply
        }
        val received = stub.tripChat(outgoing).take(3).toList()
        assertEquals(listOf(ChatRole.CHAT_ROLE_SYSTEM, ChatRole.CHAT_ROLE_RIDER, ChatRole.CHAT_ROLE_DRIVER),
            received.map { it.senderRole })
    }

    // ---- helpers ------------------------------------------------------------

    private fun fareRequest() = fareEstimateRequest {
        pickup = this@RideServiceTest.pickup
        dropoff = this@RideServiceTest.dropoff
        rideType = RideType.RIDE_TYPE_ECONOMY
    }

    private suspend fun bookRide(): String {
        val estimate = stub.getFareEstimate(fareRequest())
        return stub.requestRide(rideRequest { estimateId = estimate.estimateId }).rideId
    }

    private fun authHeader(token: String) = MetadataUtils.newAttachHeadersInterceptor(
        Metadata().apply { put(Headers.AUTHORIZATION, "Bearer $token") }
    )

    private suspend fun assertStatus(expected: Status.Code, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $expected")
        } catch (e: StatusException) {
            assertEquals(expected, e.status.code)
        }
    }
}
