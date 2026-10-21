package com.ridenow.server

import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.util.concurrent.TimeUnit

/**
 * Entry point for the local RideNow gRPC server.
 *
 * Args:
 *   --port <n>      port to listen on (default 50051)
 *   --chaos <0..1>  probability that a unary call fails with UNAVAILABLE,
 *                   to watch the client's retry policy kick in (default 0)
 */
fun main(args: Array<String>) {
    val port = args.valueOf("--port")?.toInt() ?: 50051
    val chaosRate = args.valueOf("--chaos")?.toDouble() ?: 0.0

    val service = ServerInterceptors.intercept(
        RideServiceImpl(),
        // Interceptors run in REVERSE order of this list: the last one listed
        // sees the call first. So: Logging -> Auth -> Chaos -> service.
        ChaosInterceptor(chaosRate),
        AuthInterceptor(),
        LoggingInterceptor(),
    )

    val server = NettyServerBuilder.forPort(port)
        .addService(service)
        // Clients (our Android app) ping every 30s to keep NAT/LB mappings
        // alive. If a client pings more often than permitted, the server sends
        // GOAWAY "too_many_pings" — so this must be <= the client's keepAliveTime.
        .permitKeepAliveTime(20, TimeUnit.SECONDS)
        .permitKeepAliveWithoutCalls(true)
        // Cap message size (default is 4 MB) — protects the server from huge payloads.
        .maxInboundMessageSize(1 * 1024 * 1024)
        .build()
        .start()

    log("RideNow gRPC server listening on port $port (chaos=$chaosRate)")
    log("Android emulator -> 10.0.2.2:$port | physical device -> <your-mac-LAN-IP>:$port")

    Runtime.getRuntime().addShutdownHook(Thread {
        log("Shutting down: no new calls, waiting up to 5s for in-flight calls…")
        server.shutdown()
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow()
    })
    server.awaitTermination()
}

private fun Array<String>.valueOf(flag: String): String? =
    indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

internal fun log(message: String) {
    println("[${java.time.LocalTime.now().withNano(0)}] [${Thread.currentThread().name}] $message")
}
