package com.bandrecorder.core.network

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HotspotSocketTransport : LinkTransport {
    private val _events = MutableSharedFlow<LinkTransportEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events = _events.asSharedFlow()

    private val peers = ConcurrentHashMap<String, WebSocket>()
    private var server: WebSocketServer? = null
    private var client: WebSocketClient? = null
    private var clientPeerId: String? = null

    override suspend fun host(port: Int) {
        stop()
        val socketServer = object : WebSocketServer(InetSocketAddress(port)) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
                val peerId = conn.remoteSocketAddress?.toString() ?: UUID.randomUUID().toString()
                peers[peerId] = conn
                _events.tryEmit(
                    LinkTransportEvent.Connected(
                        LinkPeer(peerId = peerId, endpoint = conn.remoteSocketAddress?.toString())
                    )
                )
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
                val peerId = peers.entries.firstOrNull { it.value == conn }?.key ?: return
                peers.remove(peerId)
                _events.tryEmit(LinkTransportEvent.Disconnected(peerId, reason))
            }

            override fun onMessage(conn: WebSocket, message: String) {
                val peerId = peers.entries.firstOrNull { it.value == conn }?.key ?: return
                runCatching { RemoteMessageCodec.decode(message) }
                    .onSuccess { _events.tryEmit(LinkTransportEvent.Message(peerId, it)) }
                    .onFailure { _events.tryEmit(LinkTransportEvent.Failure("Invalid payload: ${it.message}")) }
            }

            override fun onError(conn: WebSocket?, ex: Exception) {
                _events.tryEmit(LinkTransportEvent.Failure(ex.message ?: "WebSocket server error"))
            }

            override fun onStart() {
                _events.tryEmit(LinkTransportEvent.Hosting(port))
            }
        }
        server = socketServer
        socketServer.start()
    }

    override suspend fun connect(host: String, port: Int) {
        stop()
        val endpoint = URI("ws://$host:$port")
        val socketClient = object : WebSocketClient(endpoint) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                val peerId = "host@$host:$port"
                clientPeerId = peerId
                _events.tryEmit(
                    LinkTransportEvent.Connected(
                        LinkPeer(peerId = peerId, displayName = "Master", endpoint = endpoint.toString())
                    )
                )
            }

            override fun onMessage(message: String) {
                val peerId = clientPeerId ?: return
                runCatching { RemoteMessageCodec.decode(message) }
                    .onSuccess { _events.tryEmit(LinkTransportEvent.Message(peerId, it)) }
                    .onFailure { _events.tryEmit(LinkTransportEvent.Failure("Invalid payload: ${it.message}")) }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                val peerId = clientPeerId ?: "host@$host:$port"
                clientPeerId = null
                _events.tryEmit(LinkTransportEvent.Disconnected(peerId, reason))
            }

            override fun onError(ex: Exception) {
                _events.tryEmit(LinkTransportEvent.Failure(ex.message ?: "WebSocket client error"))
            }
        }
        client = socketClient
        socketClient.connect()
    }

    override suspend fun sendTo(peerId: String, message: RemoteMessage) {
        val payload = RemoteMessageCodec.encode(message)
        peers[peerId]?.send(payload)
        if (clientPeerId == peerId) {
            client?.send(payload)
        }
    }

    override suspend fun broadcast(message: RemoteMessage) {
        val payload = RemoteMessageCodec.encode(message)
        peers.values.forEach { it.send(payload) }
        client?.send(payload)
    }

    override suspend fun stop() {
        peers.values.forEach { runCatching { it.close() } }
        peers.clear()
        runCatching { server?.stop() }
        server = null
        runCatching { client?.close() }
        client = null
        clientPeerId = null
    }
}
