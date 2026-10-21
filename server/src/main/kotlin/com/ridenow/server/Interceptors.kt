package com.ridenow.server

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor.MethodType
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import kotlin.random.Random

/** Metadata (= HTTP/2 headers) keys. Keys are lowercase ASCII; binary keys end in "-bin". */
object Headers {
    val AUTHORIZATION: Metadata.Key<String> =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    val PREVIOUS_ATTEMPTS: Metadata.Key<String> =
        Metadata.Key.of("grpc-previous-rpc-attempts", Metadata.ASCII_STRING_MARSHALLER)
}

/** Values the interceptors put on the gRPC [Context] for the service to read. */
object Auth {
    val USER_ID: Context.Key<String> = Context.key("user-id")
    fun currentUserId(): String = USER_ID.get() ?: "anonymous"
}

/**
 * Cross-cutting concern #1: authentication.
 *
 * Reads `authorization: Bearer <token>` from request metadata. In production you
 * would verify a JWT/OAuth token; here any token "demo-<user>" is accepted.
 * Rejects with UNAUTHENTICATED *before* the service code ever runs.
 */
class AuthInterceptor : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val token = headers.get(Headers.AUTHORIZATION)?.removePrefix("Bearer ")?.trim()
        if (token == null || !token.startsWith("demo-")) {
            call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid bearer token"), Metadata())
            return object : ServerCall.Listener<ReqT>() {} // no-op listener
        }
        // Attach the user id to the Context so the handler can read Auth.currentUserId().
        val ctx = Context.current().withValue(Auth.USER_ID, token.removePrefix("demo-"))
        return Contexts.interceptCall(ctx, call, headers, next)
    }
}

/**
 * Cross-cutting concern #2: observability.
 * Logs every call's method, type, final status and latency.
 */
class LoggingInterceptor : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val method = call.methodDescriptor
        val start = System.nanoTime()
        // Set by the client's retry machinery on attempt 2+ — lets you SEE retries.
        val attempt = headers.get(Headers.PREVIOUS_ATTEMPTS)?.let { " [retry #$it]" } ?: ""
        log("--> ${method.fullMethodName} (${method.type})$attempt")
        val wrapped = object : SimpleForwardingServerCall<ReqT, RespT>(call) {
            override fun close(status: Status, trailers: Metadata) {
                val ms = (System.nanoTime() - start) / 1_000_000
                val detail = status.description?.let { " - $it" } ?: ""
                log("<-- ${method.fullMethodName} ${status.code}$detail (${ms}ms)")
                super.close(status, trailers)
            }
        }
        return next.startCall(wrapped, headers)
    }
}

/**
 * Fault injection for learning: randomly fails UNARY calls with UNAVAILABLE so
 * you can watch the Android client's automatic retry policy (see GrpcClient).
 */
class ChaosInterceptor(private val failureRate: Double) : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        if (call.methodDescriptor.type == MethodType.UNARY && Random.nextDouble() < failureRate) {
            call.close(Status.UNAVAILABLE.withDescription("Chaos: simulated outage"), Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }
        return next.startCall(call, headers)
    }
}
