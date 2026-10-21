package com.ridenow.android.data

import android.util.Log
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private val AUTHORIZATION: Metadata.Key<String> =
    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

/**
 * Attaches `authorization: Bearer <token>` metadata to every call.
 * Reads the token lazily per call, so a refreshed token is picked up automatically.
 */
class AuthClientInterceptor(private val tokenProvider: () -> String) : ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> = object : SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
        override fun start(responseListener: Listener<RespT>, headers: Metadata) {
            headers.put(AUTHORIZATION, "Bearer ${tokenProvider()}")
            super.start(responseListener, headers)
        }
    }
}

/** Logs start + final status + latency of every call to Logcat and the in-app Logs tab. */
class LoggingClientInterceptor(private val callLog: GrpcCallLog) : ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        val name = method.bareMethodName ?: method.fullMethodName
        return object : SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            private val start = System.nanoTime()
            private var messagesIn = 0
            private var messagesOut = 0

            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                callLog.add("→ $name (${method.type})${callOptions.deadline?.let { " deadline=$it" } ?: ""}")
                super.start(object : SimpleForwardingClientCallListener<RespT>(responseListener) {
                    override fun onMessage(message: RespT) {
                        messagesIn++
                        super.onMessage(message)
                    }

                    override fun onClose(status: Status, trailers: Metadata) {
                        val ms = (System.nanoTime() - start) / 1_000_000
                        val desc = status.description?.let { " – $it" } ?: ""
                        callLog.add("← $name ${status.code}$desc | sent=$messagesOut recv=$messagesIn | ${ms}ms")
                        super.onClose(status, trailers)
                    }
                }, headers)
            }

            override fun sendMessage(message: ReqT) {
                messagesOut++
                super.sendMessage(message)
            }
        }
    }
}

/** In-memory log of gRPC calls, shown in the "Logs" tab. Thread-safe. */
class GrpcCallLog(private val maxEntries: Int = 200) {
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun add(line: String) {
        Log.d("gRPC", line)
        val stamped = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}  $line"
        _entries.update { (listOf(stamped) + it).take(maxEntries) }
    }

    fun clear() = _entries.update { emptyList() }
}
