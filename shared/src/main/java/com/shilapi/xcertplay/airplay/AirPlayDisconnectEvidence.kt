package com.shilapi.xcertplay.airplay

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

/** A terminal symptom observed by the application, without transport addresses or request data. */
data class AirPlayDisconnectEvidence(
    val source: Source,
    val reason: Reason,
    val exceptionType: String? = null,
) {
    enum class Source { CONTROL, EVENT_READ, EVENT_WRITE, EVENT_ACCEPT, VIDEO, IAP2_TUNNEL, LISTENER, SESSION }
    enum class Reason {
        PEER_EOF, PEER_TEARDOWN, CHANNEL_CLOSED, TIMEOUT, CONNECTION_RESET, BROKEN_PIPE, NETWORK_UNAVAILABLE,
        DECRYPT_FAILED, IO_ERROR, ERROR, UNSPECIFIED_CLOSE,
    }

    internal companion object {
        fun from(source: Source, error: Throwable?, reason: Reason? = null): AirPlayDisconnectEvidence {
            val chain = generateSequence(error) { it.cause }.take(6).toList()
            val observed = reason ?: when {
                error == null || chain.any { it is EOFException } -> Reason.PEER_EOF
                chain.any { it is SocketTimeoutException || it.matchesMessage("etimedout", "timed out") } -> Reason.TIMEOUT
                chain.any { it.matchesMessage("econnreset", "connection reset") } -> Reason.CONNECTION_RESET
                chain.any { it.matchesMessage("epipe", "broken pipe") } -> Reason.BROKEN_PIPE
                chain.any { it.matchesMessage("enetdown", "enetunreach", "ehostunreach", "unreachable", "network is down", "no route to host") } ->
                    Reason.NETWORK_UNAVAILABLE
                chain.any { it is IOException } -> Reason.IO_ERROR
                else -> Reason.ERROR
            }
            return AirPlayDisconnectEvidence(source, observed, error?.javaClass?.simpleName?.take(80))
        }

        private fun Throwable.matchesMessage(vararg phrases: String): Boolean {
            val detail = message?.lowercase(Locale.ROOT) ?: return false
            return phrases.any(detail::contains)
        }
    }
}
