package com.bandrecorder.core.network

import kotlinx.coroutines.flow.Flow

data class LinkPeer(
    val peerId: String,
    val displayName: String? = null,
    val endpoint: String? = null
)

sealed class LinkTransportEvent {
    data class Hosting(val port: Int) : LinkTransportEvent()
    data class Connected(val peer: LinkPeer) : LinkTransportEvent()
    data class Disconnected(val peerId: String, val reason: String? = null) : LinkTransportEvent()
    data class Message(val peerId: String, val payload: RemoteMessage) : LinkTransportEvent()
    data class Failure(val reason: String) : LinkTransportEvent()
}

interface LinkTransport {
    val events: Flow<LinkTransportEvent>

    suspend fun host(port: Int)

    suspend fun connect(host: String, port: Int)

    suspend fun sendTo(peerId: String, message: RemoteMessage)

    suspend fun broadcast(message: RemoteMessage)

    suspend fun stop()
}
