package com.ridenow.android.data

import io.grpc.Status

fun Throwable.grpcCode(): Status.Code = Status.fromThrowable(this).code

/**
 * Map gRPC status codes to something a user can act on.
 * The interview-worthy part is deciding which codes are *retryable*
 * (UNAVAILABLE, sometimes DEADLINE_EXCEEDED / RESOURCE_EXHAUSTED) vs *client
 * bugs* (INVALID_ARGUMENT, NOT_FOUND, FAILED_PRECONDITION) vs *auth*.
 */
fun Throwable.toUserMessage(): String {
    val status = Status.fromThrowable(this)
    val detail = status.description?.let { " ($it)" } ?: ""
    return when (status.code) {
        Status.Code.UNAVAILABLE -> "Can't reach the server. Is it running?$detail"
        Status.Code.DEADLINE_EXCEEDED -> "The server took too long to respond."
        Status.Code.UNAUTHENTICATED -> "Session expired. Please log in again."
        Status.Code.PERMISSION_DENIED -> "You don't have access to this.$detail"
        Status.Code.NOT_FOUND -> "Not found$detail"
        Status.Code.INVALID_ARGUMENT -> "Invalid request$detail"
        Status.Code.FAILED_PRECONDITION -> "Can't do that right now$detail"
        Status.Code.RESOURCE_EXHAUSTED -> "Too many requests. Try again shortly."
        else -> "Something went wrong: ${status.code}$detail"
    }
}
