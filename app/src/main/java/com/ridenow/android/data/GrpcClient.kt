package com.ridenow.android.data

import android.content.Context
import io.grpc.ManagedChannel
import io.grpc.android.AndroidChannelBuilder
import io.grpc.okhttp.OkHttpChannelBuilder
import java.util.concurrent.TimeUnit

/**
 * Owns the ONE [ManagedChannel] for the whole app.
 *
 * A channel = a virtual connection to a server. Under the hood it's a single
 * HTTP/2 TCP connection that multiplexes ALL calls (unary + every stream) as
 * separate HTTP/2 streams. Channels are expensive (TCP + TLS handshake), stubs
 * are cheap — so: one channel per server, create stubs freely.
 */
class GrpcClient(
    context: Context,
    host: String,
    port: Int,
    tokenProvider: () -> String,
    callLog: GrpcCallLog,
) {
    val channel: ManagedChannel =
        // AndroidChannelBuilder wraps the OkHttp builder and listens to
        // ConnectivityManager: on network change (wifi -> LTE) it resets the
        // connection backoff and reconnects immediately instead of waiting.
        AndroidChannelBuilder.usingBuilder(
            OkHttpChannelBuilder.forAddress(host, port)
                .usePlaintext() // local dev only! production = TLS (just drop this line)
        )
            .context(context.applicationContext)
            // --- Liveness -----------------------------------------------------
            // Send HTTP/2 PING every 30s while calls are active, so a dead
            // connection (NAT timeout, LB drop) is detected quickly. Must be >=
            // the server's permitKeepAliveTime or the server will GOAWAY us.
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(false) // don't wake the radio when idle (battery)
            // Close the TCP connection after 5 min of no calls (battery, server sockets).
            // The channel transparently reconnects on the next call.
            .idleTimeout(5, TimeUnit.MINUTES)
            // --- Resilience ---------------------------------------------------
            .defaultServiceConfig(serviceConfig)
            .enableRetry()
            .maxInboundMessageSize(4 * 1024 * 1024)
            .userAgent("RideNow-Android/1.0")
            // --- Cross-cutting concerns --------------------------------------
            // Like OkHttp interceptors. The LAST one added runs FIRST on a call.
            .intercept(LoggingClientInterceptor(callLog), AuthClientInterceptor(tokenProvider))
            .build()

    private companion object {
        /**
         * gRPC "service config" — declarative per-method timeouts + retry policy.
         * Usually served by the backend via DNS/xDS; here we hard-code a default.
         *
         * Retries are transparent: the generated stub call just takes longer.
         * Only retry codes that mean "the request never reached business logic
         * or is safe to repeat" — UNAVAILABLE is the classic one.
         */
        private fun retryPolicy() = mapOf(
            "maxAttempts" to 4.0,          // numbers MUST be Double (JSON semantics)
            "initialBackoff" to "0.5s",
            "maxBackoff" to "4s",
            "backoffMultiplier" to 2.0,    // 0.5s, 1s, 2s (+ jitter)
            "retryableStatusCodes" to listOf("UNAVAILABLE"),
        )

        private fun method(name: String) = mapOf("service" to "ridenow.v1.RideService", "method" to name)

        val serviceConfig: Map<String, Any> = mapOf(
            "methodConfig" to listOf(
                mapOf(
                    "name" to listOf(method("GetFareEstimate")),
                    "timeout" to "5s", // overall deadline incl. all retries
                    "retryPolicy" to retryPolicy(),
                ),
                mapOf(
                    // Safe to retry ONLY because RideRequest carries an idempotency_key.
                    "name" to listOf(method("RequestRide")),
                    "timeout" to "10s",
                    "retryPolicy" to retryPolicy(),
                ),
            )
        )
    }
}
