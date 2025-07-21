package com.eddyslarez.siplibrary.data.services.websocket

/**
 * Interface para WebSocket multiplataforma
 * 
 * @author Eddys Larez
 */
interface MultiplatformWebSocket {
    fun connect()
    fun send(message: String)
    fun close(code: Int = 1000, reason: String = "")
    fun isConnected(): Boolean
    fun sendPing()
    fun startPingTimer(intervalMs: Long = 60000)
    fun stopPingTimer()
    fun startRegistrationRenewalTimer(checkIntervalMs: Long = 300000, renewBeforeExpirationMs: Long = 60000)
    fun stopRegistrationRenewalTimer()
    fun setRegistrationExpiration(accountKey: String, expirationTimeMs: Long)
    fun renewRegistration(accountKey: String)

    interface Listener {
        fun onOpen()
        fun onMessage(message: String)
        fun onClose(code: Int, reason: String)
        fun onError(error: Exception)
        fun onPong(timeMs: Long)
        fun onRegistrationRenewalRequired(accountKey: String)
    }

    fun setListener(listener: Listener)
}