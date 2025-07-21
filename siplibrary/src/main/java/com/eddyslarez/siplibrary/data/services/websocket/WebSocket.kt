package com.eddyslarez.siplibrary.data.services.websocket

import org.java_websocket.client.WebSocketClient
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer
import java.util.*
import javax.net.ssl.SSLSocketFactory
import kotlin.collections.HashMap

/**
 * Implementación de WebSocket para Android
 * 
 * @author Eddys Larez
 */
class WebSocket(private val uri: String, private val headers: Map<String, String>) :
    MultiplatformWebSocket {

    private var webSocketClient: WebSocketClient? = null
    private var listener: MultiplatformWebSocket.Listener? = null
    private var isConnectedFlag = false

    // Timers
    private var pingTimer: Timer? = null
    private var registrationRenewalTimer: Timer? = null

    // Registro de expiración para cuentas
    private val registrationExpirations = HashMap<String, Long>()

    // Constantes
    private var registrationCheckIntervalMs: Long = 300000
    private var renewBeforeExpirationMs: Long = 60000

    // Variables para medir pings
    private var lastPingSentTime: Long = 0

    override fun connect() {
        try {
            val webSocketURI = URI(uri)

            webSocketClient = object : WebSocketClient(webSocketURI, headers) {
                override fun onOpen(handshakedata: ServerHandshake) {
                    isConnectedFlag = true
                    listener?.onOpen()
                }

                override fun onMessage(message: String) {
                    listener?.onMessage(message)
                }

                override fun onMessage(bytes: ByteBuffer) {
                    val binaryString = bytes.toString()
                    listener?.onMessage(binaryString)
                }

                override fun onClose(code: Int, reason: String, remote: Boolean) {
                    isConnectedFlag = false
                    stopPingTimer()
                    stopRegistrationRenewalTimer()
                    listener?.onClose(code, reason)
                }

                override fun onError(ex: Exception) {
                    listener?.onError(ex)
                }

                override fun onWebsocketPong(conn: org.java_websocket.WebSocket?, f: Framedata?) {
                    val currentTime = System.currentTimeMillis()
                    val pingLatency = currentTime - lastPingSentTime
                    listener?.onPong(pingLatency)
                }
            }

            // Setup SSL if using secure WebSocket
            if (uri.startsWith("wss")) {
                val socketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                webSocketClient?.setSocketFactory(socketFactory)
            }

            webSocketClient?.connect()

        } catch (e: Exception) {
            listener?.onError(e)
        }
    }

    override fun send(message: String) {
        if (!isConnectedFlag || webSocketClient == null) {
            listener?.onError(Exception("Cannot send message - WebSocket not connected"))
            return
        }

        try {
            webSocketClient?.send(message)
        } catch (e: Exception) {
            listener?.onError(Exception("Send error: ${e.message}"))
        }
    }

    override fun close(code: Int, reason: String) {
        try {
            isConnectedFlag = false
            stopPingTimer()
            stopRegistrationRenewalTimer()
            webSocketClient?.close(code, reason)
            webSocketClient = null
        } catch (e: Exception) {
            listener?.onError(e)
        }
    }

    override fun isConnected(): Boolean {
        return isConnectedFlag && webSocketClient?.isOpen == true
    }

    override fun setListener(listener: MultiplatformWebSocket.Listener) {
        this.listener = listener
    }

    override fun sendPing() {
        if (!isConnectedFlag || webSocketClient == null) {
            return
        }

        try {
            lastPingSentTime = System.currentTimeMillis()
            webSocketClient?.sendPing()
        } catch (e: Exception) {
            listener?.onError(Exception("Ping error: ${e.message}"))
        }
    }

    override fun startPingTimer(intervalMs: Long) {
        stopPingTimer()

        pingTimer = Timer("WebSocketPingTimer")
        pingTimer?.schedule(object : TimerTask() {
            override fun run() {
                if (isConnected()) {
                    sendPing()
                }
            }
        }, intervalMs, intervalMs)
    }

    override fun stopPingTimer() {
        pingTimer?.cancel()
        pingTimer = null
    }

    override fun startRegistrationRenewalTimer(checkIntervalMs: Long, renewBeforeExpirationMs: Long) {
        stopRegistrationRenewalTimer()

        this.registrationCheckIntervalMs = checkIntervalMs
        this.renewBeforeExpirationMs = renewBeforeExpirationMs

        registrationRenewalTimer = Timer("RegistrationRenewalTimer")
        registrationRenewalTimer?.schedule(object : TimerTask() {
            override fun run() {
                checkRegistrationRenewals()
            }
        }, 0, registrationCheckIntervalMs)
    }

    override fun stopRegistrationRenewalTimer() {
        registrationRenewalTimer?.cancel()
        registrationRenewalTimer = null
    }

    override fun setRegistrationExpiration(accountKey: String, expirationTimeMs: Long) {
        registrationExpirations[accountKey] = expirationTimeMs
    }

    override fun renewRegistration(accountKey: String) {
        listener?.onRegistrationRenewalRequired(accountKey)
    }

    private fun checkRegistrationRenewals() {
        val currentTime = System.currentTimeMillis()

        val expirationsToCheck = HashMap(registrationExpirations)

        for ((accountKey, expirationTime) in expirationsToCheck) {
            val timeToExpiration = expirationTime - currentTime

            if (timeToExpiration < renewBeforeExpirationMs) {
                renewRegistration(accountKey)
            }
        }
    }
}