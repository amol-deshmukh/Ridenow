package com.ridenow.server

import com.ridenow.proto.v1.Driver
import com.ridenow.proto.v1.LatLng
import com.ridenow.proto.v1.RideStatus
import com.ridenow.proto.v1.RideType
import com.ridenow.proto.v1.driver
import com.ridenow.proto.v1.latLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ----------------------------------------------------------------------------
// Server-side models. Note they are NOT the generated proto classes: keeping
// wire types (proto) separate from domain types lets each evolve independently.
// ----------------------------------------------------------------------------

data class Estimate(
    val id: String,
    val pickup: LatLng,
    val dropoff: LatLng,
    val rideType: RideType,
    val fareMinorUnits: Long,
    val expiresAtMs: Long,
)

data class Ride(
    val id: String,
    val riderId: String,
    val driver: Driver,
    val pickup: LatLng,
    val dropoff: LatLng,
    val driverStart: LatLng,
    val createdAtMs: Long,
)

/** A point-in-time view of where the driver is, derived purely from elapsed time. */
data class RideSnapshot(
    val status: RideStatus,
    val position: LatLng,
    val bearing: Float,
    val etaSeconds: Int,
)

object Simulation {
    // Timeline of every ride (seconds since the ride was created).
    const val ARRIVE_SECONDS = 15   // driver drives to pickup
    const val WAIT_SECONDS = 3      // driver waits at pickup
    const val TRIP_SECONDS = 25     // pickup -> dropoff
    const val TOTAL_SECONDS = ARRIVE_SECONDS + WAIT_SECONDS + TRIP_SECONDS

    /**
     * Ride progress is a pure function of time on the *server*. So if the client's
     * stream drops and it reconnects, it simply resumes at the correct position —
     * the stream is a view of server state, not the state itself.
     */
    fun snapshot(ride: Ride, nowMs: Long): RideSnapshot {
        val t = (nowMs - ride.createdAtMs) / 1000.0
        return when {
            t < ARRIVE_SECONDS -> {
                val f = t / ARRIVE_SECONDS
                RideSnapshot(
                    RideStatus.RIDE_STATUS_DRIVER_ARRIVING,
                    lerp(ride.driverStart, ride.pickup, f),
                    bearing(ride.driverStart, ride.pickup),
                    (ARRIVE_SECONDS - t).roundToInt(),
                )
            }
            t < ARRIVE_SECONDS + WAIT_SECONDS -> RideSnapshot(
                RideStatus.RIDE_STATUS_DRIVER_ARRIVED, ride.pickup, bearing(ride.pickup, ride.dropoff), TRIP_SECONDS,
            )
            t < TOTAL_SECONDS -> {
                val f = (t - ARRIVE_SECONDS - WAIT_SECONDS) / TRIP_SECONDS
                RideSnapshot(
                    RideStatus.RIDE_STATUS_IN_PROGRESS,
                    lerp(ride.pickup, ride.dropoff, f),
                    bearing(ride.pickup, ride.dropoff),
                    (TOTAL_SECONDS - t).roundToInt(),
                )
            }
            else -> RideSnapshot(RideStatus.RIDE_STATUS_COMPLETED, ride.dropoff, 0f, 0)
        }
    }
}

object Pricing {
    private val baseFare = mapOf(
        RideType.RIDE_TYPE_ECONOMY to 4000L, // ₹40.00 in paise
        RideType.RIDE_TYPE_PREMIUM to 8000L,
        RideType.RIDE_TYPE_XL to 10000L,
    )
    private val perKm = mapOf(
        RideType.RIDE_TYPE_ECONOMY to 1200L,
        RideType.RIDE_TYPE_PREMIUM to 2200L,
        RideType.RIDE_TYPE_XL to 2600L,
    )

    fun fare(type: RideType, distanceKm: Double, surge: Double): Long =
        ((baseFare.getValue(type) + perKm.getValue(type) * distanceKm) * surge).toLong()
}

object DriverPool {
    private val drivers = listOf(
        driver { driverId = "d-101"; name = "Ravi Kumar"; vehicle = "Maruti Swift Dzire"; plate = "KA 01 AB 1234"; rating = 4.9f },
        driver { driverId = "d-102"; name = "Priya Sharma"; vehicle = "Hyundai Aura"; plate = "KA 05 MN 4321"; rating = 4.8f },
        driver { driverId = "d-103"; name = "Arjun Rao"; vehicle = "Toyota Innova"; plate = "KA 03 XY 9876"; rating = 4.7f },
    )

    fun assign(): Driver = drivers.random()
}

// ---- Geo helpers ------------------------------------------------------------

fun haversineKm(a: LatLng, b: LatLng): Double {
    val r = 6371.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

fun lerp(a: LatLng, b: LatLng, f: Double): LatLng = latLng {
    lat = a.lat + (b.lat - a.lat) * f
    lng = a.lng + (b.lng - a.lng) * f
}

fun bearing(a: LatLng, b: LatLng): Float {
    val deg = Math.toDegrees(atan2(b.lng - a.lng, b.lat - a.lat))
    return ((deg + 360) % 360).toFloat()
}
