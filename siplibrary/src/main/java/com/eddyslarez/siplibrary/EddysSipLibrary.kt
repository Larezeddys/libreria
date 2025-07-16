package com.eddyslarez.siplibrary

import android.app.Application
import android.content.Context
import com.eddyslarez.siplibrary.core.SipCoreManager
import com.eddyslarez.siplibrary.data.models.*
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.utils.CallStateManager
import com.eddyslarez.siplibrary.utils.RegistrationStateManager
import com.eddyslarez.siplibrary.utils.log
import com.eddyslarez.siplibrary.utils.MultiCallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import android.net.Uri

/**
 * EddysSipLibrary - Biblioteca SIP/VoIP para Android (Versión Optimizada)
 * Versión simplificada usando únicamente los nuevos estados
 *
 * @author Eddys Larez
 * @version 1.5.0
 */
class EddysSipLibrary private constructor() {

    private var sipCoreManager: SipCoreManager? = null
    private var isInitialized = false
    private lateinit var config: SipConfig
    private val listeners = mutableSetOf<SipEventListener>()
    private var registrationListener: RegistrationListener? = null
    private var callListener: CallListener? = null
    private var incomingCallListener: IncomingCallListener? = null

    companion object {
        @Volatile
        private var INSTANCE: EddysSipLibrary? = null
        private const val TAG = "EddysSipLibrary"

        fun getInstance(): EddysSipLibrary {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: EddysSipLibrary().also { INSTANCE = it }
            }
        }
    }

    data class SipConfig(
        val defaultDomain: String = "",
        val webSocketUrl: String = "",
        val userAgent: String = "",
        val enableLogs: Boolean = true,
        val enableAutoReconnect: Boolean = true,
        val pingIntervalMs: Long = 30000L,
        val incomingRingtoneUri: Uri? = null,
        val outgoingRingtoneUri: Uri? = null
    )

    /**
     * Listener principal para todos los eventos SIP
     */
    interface SipEventListener {
        fun onRegistrationStateChanged(state: RegistrationState, username: String, domain: String) {}
        fun onCallStateChanged(stateInfo: CallStateInfo) {}
        fun onIncomingCall(callInfo: IncomingCallInfo) {}
        fun onCallConnected(callInfo: CallInfo) {}
        fun onCallEnded(callInfo: CallInfo, reason: CallEndReason) {}
        fun onCallFailed(error: String, callInfo: CallInfo?) {}
        fun onDtmfReceived(digit: Char, callInfo: CallInfo) {}
        fun onAudioDeviceChanged(device: AudioDevice) {}
        fun onNetworkStateChanged(isConnected: Boolean) {}
    }

    /**
     * Listener específico para estados de registro
     */
    interface RegistrationListener {
        fun onRegistrationSuccessful(username: String, domain: String)
        fun onRegistrationFailed(username: String, domain: String, error: String)
        fun onUnregistered(username: String, domain: String)
        fun onRegistrationExpiring(username: String, domain: String, expiresIn: Long)
    }

    /**
     * Listener específico para estados de llamada
     */
    interface CallListener {
        fun onCallInitiated(callInfo: CallInfo)
        fun onCallRinging(callInfo: CallInfo)
        fun onCallConnected(callInfo: CallInfo)
        fun onCallHeld(callInfo: CallInfo)
        fun onCallResumed(callInfo: CallInfo)
        fun onCallEnded(callInfo: CallInfo, reason: CallEndReason)
        fun onCallTransferred(callInfo: CallInfo, transferTo: String)
        fun onMuteStateChanged(isMuted: Boolean, callInfo: CallInfo)
        fun onCallStateChanged(stateInfo: CallStateInfo)
    }

    /**
     * Listener específico para llamadas entrantes
     */
    interface IncomingCallListener {
        fun onIncomingCall(callInfo: IncomingCallInfo)
        fun onIncomingCallCancelled(callInfo: IncomingCallInfo)
        fun onIncomingCallTimeout(callInfo: IncomingCallInfo)
    }

    /**
     * Información de llamada
     */
    data class CallInfo(
        val callId: String,
        val phoneNumber: String,
        val displayName: String?,
        val direction: CallDirection,
        val startTime: Long,
        val duration: Long = 0,
        val isOnHold: Boolean = false,
        val isMuted: Boolean = false,
        val localAccount: String,
        val codec: String? = null,
        val state: CallState? = null,
        val isCurrentCall: Boolean = false
    )

    /**
     * Información de llamada entrante
     */
    data class IncomingCallInfo(
        val callId: String,
        val callerNumber: String,
        val callerName: String?,
        val targetAccount: String,
        val timestamp: Long,
        val headers: Map<String, String> = emptyMap()
    )

    /**
     * Dirección de la llamada
     */
    enum class CallDirection {
        INCOMING,
        OUTGOING
    }

    /**
     * Razones de finalización de llamada
     */
    enum class CallEndReason {
        NORMAL_HANGUP,
        BUSY,
        NO_ANSWER,
        REJECTED,
        NETWORK_ERROR,
        CANCELLED,
        TIMEOUT,
        ERROR
    }

    fun initialize(
        application: Application,
        config: SipConfig = SipConfig()
    ) {
        if (isInitialized) {
            log.w(tag = TAG) { "Library already initialized" }
            return
        }

        try {
            log.d(tag = TAG) { "Initializing EddysSipLibrary v1.5.0 Optimized by Eddys Larez" }

            this.config = config
            sipCoreManager = SipCoreManager.createInstance(application, config)
            sipCoreManager?.initialize()

            // Configurar listeners internos
            setupInternalListeners()

            // Configurar ringtones personalizados si se proporcionan
            config.incomingRingtoneUri?.let { uri ->
                sipCoreManager?.audioManager?.setIncomingRingtone(uri)
            }
            config.outgoingRingtoneUri?.let { uri ->
                sipCoreManager?.audioManager?.setOutgoingRingtone(uri)
            }

            isInitialized = true
            log.d(tag = TAG) { "EddysSipLibrary initialized successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing library: ${e.message}" }
            throw SipLibraryException("Failed to initialize library", e)
        }
    }

    private fun setupInternalListeners() {
        sipCoreManager?.let { manager ->

            // Configurar callback para eventos principales
            manager.setCallbacks(object : SipCallbacks {
                override fun onCallTerminated() {
                    log.d(tag = TAG) { "Internal callback: onCallTerminated" }
                    val callInfo = getCurrentCallInfo()
                    notifyCallEnded(callInfo, CallEndReason.NORMAL_HANGUP)
                }

                override fun onRegistrationStateChanged(state: RegistrationState) {
                    log.d(tag = TAG) { "Internal callback: onRegistrationStateChanged - $state" }
                }

                override fun onAccountRegistrationStateChanged(username: String, domain: String, state: RegistrationState) {
                    log.d(tag = TAG) { "Internal callback: onAccountRegistrationStateChanged - $username@$domain -> $state" }
                    notifyRegistrationStateChanged(state, username, domain)
                }

                override fun onIncomingCall(callerNumber: String, callerName: String?) {
                    log.d(tag = TAG) { "Internal callback: onIncomingCall from $callerNumber" }
                    val callInfo = createIncomingCallInfoFromCurrentCall(callerNumber, callerName)
                    notifyIncomingCall(callInfo)
                }

                override fun onCallConnected() {
                    log.d(tag = TAG) { "Internal callback: onCallConnected" }
                    getCurrentCallInfo()?.let { notifyCallConnected(it) }
                }

                override fun onCallFailed(error: String) {
                    log.d(tag = TAG) { "Internal callback: onCallFailed - $error" }
                    val callInfo = getCurrentCallInfo()
                    notifyCallFailed(error, callInfo)
                }
            })

            // Observar estados usando el nuevo CallStateManager
            CoroutineScope(Dispatchers.Main).launch {
                CallStateManager.callStateFlow.collect { stateInfo ->
                    // Obtener información completa de la llamada
                    val callInfo = getCallInfoForState(stateInfo)
                    val enhancedStateInfo = stateInfo.copy(
                        // Agregar información adicional si es necesario
                    )
                    
                    notifyCallStateChanged(enhancedStateInfo)

                    // Mapear a eventos específicos para compatibilidad
                    callInfo?.let { info ->
                    when (stateInfo.state) {
                        CallState.CONNECTED -> notifyCallConnected(info)
                        CallState.OUTGOING_RINGING -> notifyCallRinging(info)
                        CallState.OUTGOING_INIT -> notifyCallInitiated(info)
                        CallState.INCOMING_RECEIVED -> handleIncomingCall()
                        CallState.ENDED -> notifyCallEnded(info, CallEndReason.NORMAL_HANGUP)
                        CallState.PAUSED -> notifyCallHeld(info)
                        CallState.STREAMS_RUNNING -> notifyCallResumed(info)
                        CallState.ERROR -> {
                            val reason = mapErrorReasonToCallEndReason(stateInfo.errorReason)
                            notifyCallEnded(info, reason)
                        }
                        else -> {}
                    }
                    }
                }
            }
        }
    }

    // === MÉTODOS PARA CONFIGURAR LISTENERS ===

    /**
     * Añade un listener general para eventos SIP
     */
    fun addSipEventListener(listener: SipEventListener) {
        listeners.add(listener)
        log.d(tag = TAG) { "SipEventListener added. Total listeners: ${listeners.size}" }
    }

    /**
     * Remueve un listener general
     */
    fun removeSipEventListener(listener: SipEventListener) {
        listeners.remove(listener)
        log.d(tag = TAG) { "SipEventListener removed. Total listeners: ${listeners.size}" }
    }

    /**
     * Configura un listener específico para registro
     */
    fun setRegistrationListener(listener: RegistrationListener?) {
        this.registrationListener = listener
        log.d(tag = TAG) { "RegistrationListener configured" }
    }

    /**
     * Configura un listener específico para llamadas
     */
    fun setCallListener(listener: CallListener?) {
        this.callListener = listener
        log.d(tag = TAG) { "CallListener configured" }
    }

    /**
     * Configura un listener específico para llamadas entrantes
     */
    fun setIncomingCallListener(listener: IncomingCallListener?) {
        this.incomingCallListener = listener
        log.d(tag = TAG) { "IncomingCallListener configured" }
    }

    // === MÉTODOS DE NOTIFICACIÓN INTERNA ===

    private fun notifyRegistrationStateChanged(state: RegistrationState, username: String, domain: String) {
        log.d(tag = TAG) { "Notifying registration state change: $state for $username@$domain to ${listeners.size} listeners" }

        listeners.forEach { listener ->
            try {
                listener.onRegistrationStateChanged(state, username, domain)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in listener onRegistrationStateChanged: ${e.message}" }
            }
        }

        registrationListener?.let { listener ->
            try {
                when (state) {
                    RegistrationState.OK -> listener.onRegistrationSuccessful(username, domain)
                    RegistrationState.FAILED -> listener.onRegistrationFailed(username, domain, "Registration failed")
                    RegistrationState.NONE, RegistrationState.CLEARED -> listener.onUnregistered(username, domain)
                    else -> {}
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in RegistrationListener: ${e.message}" }
            }
        }
    }

    /**
     * Notificar cambios de estado
     */
    private fun notifyCallStateChanged(stateInfo: CallStateInfo) {
        log.d(tag = TAG) { "Notifying call state change: ${stateInfo.state} to ${listeners.size} listeners" }

        listeners.forEach { listener ->
            try {
                listener.onCallStateChanged(stateInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in listener onCallStateChanged: ${e.message}" }
            }
        }

        callListener?.let { listener ->
            try {
                listener.onCallStateChanged(stateInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in CallListener onCallStateChanged: ${e.message}" }
            }
        }
    }

    private fun notifyCallInitiated(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call initiated to ${listeners.size} listeners" }

        try {
            callListener?.onCallInitiated(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallInitiated: ${e.message}" }
        }
    }

    private fun notifyCallConnected(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call connected to ${listeners.size} listeners" }

        listeners.forEach { listener ->
            try {
                listener.onCallConnected(callInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in listener onCallConnected: ${e.message}" }
            }
        }

        try {
            callListener?.onCallConnected(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallConnected: ${e.message}" }
        }
    }

    private fun notifyCallRinging(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call ringing" }

        try {
            callListener?.onCallRinging(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallRinging: ${e.message}" }
        }
    }

    private fun notifyCallHeld(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call held" }

        try {
            callListener?.onCallHeld(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallHeld: ${e.message}" }
        }
    }

    private fun notifyCallResumed(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call resumed" }

        try {
            callListener?.onCallResumed(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallResumed: ${e.message}" }
        }
    }

    private fun notifyCallEnded(callInfo: CallInfo?, reason: CallEndReason) {
        callInfo?.let { info ->
            log.d(tag = TAG) { "Notifying call ended to ${listeners.size} listeners" }

            listeners.forEach { listener ->
                try {
                    listener.onCallEnded(info, reason)
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in listener onCallEnded: ${e.message}" }
                }
            }

            try {
                callListener?.onCallEnded(info, reason)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in CallListener onCallEnded: ${e.message}" }
            }
        }
    }

    private fun notifyIncomingCall(callInfo: IncomingCallInfo) {
        log.d(tag = TAG) { "Notifying incoming call to ${listeners.size} listeners" }

        listeners.forEach { listener ->
            try {
                listener.onIncomingCall(callInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in listener onIncomingCall: ${e.message}" }
            }
        }

        try {
            incomingCallListener?.onIncomingCall(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in IncomingCallListener: ${e.message}" }
        }
    }

    private fun notifyCallFailed(error: String, callInfo: CallInfo?) {
        log.d(tag = TAG) { "Notifying call failed to ${listeners.size} listeners" }

        listeners.forEach { listener ->
            try {
                listener.onCallFailed(error, callInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in listener onCallFailed: ${e.message}" }
            }
        }
    }

    private fun handleIncomingCall() {
        // Crear información de llamada entrante desde el core manager
        val manager = sipCoreManager ?: return
        val account = manager.currentAccountInfo ?: return
        val callData = account.currentCallData ?: return

        val callInfo = IncomingCallInfo(
            callId = callData.callId,
            callerNumber = callData.from,
            callerName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
            targetAccount = account.username,
            timestamp = callData.startTime
        )

        notifyIncomingCall(callInfo)
    }

    /**
     * Crear IncomingCallInfo desde los datos actuales de la llamada
     */
    private fun createIncomingCallInfoFromCurrentCall(callerNumber: String, callerName: String?): IncomingCallInfo {
        val manager = sipCoreManager ?: return IncomingCallInfo(
            callId = generateCallId(),
            callerNumber = callerNumber,
            callerName = callerName,
            targetAccount = "",
            timestamp = System.currentTimeMillis()
        )

        val account = manager.currentAccountInfo
        val callData = account?.currentCallData

        return IncomingCallInfo(
            callId = callData?.callId ?: generateCallId(),
            callerNumber = callerNumber,
            callerName = callerName,
            targetAccount = account?.username ?: "",
            timestamp = callData?.startTime ?: System.currentTimeMillis()
        )
    }

    // === MÉTODOS AUXILIARES ===

    /**
     * Obtiene información de llamada para un estado específico
     */
    private fun getCallInfoForState(stateInfo: CallStateInfo): CallInfo? {
        val manager = sipCoreManager ?: return null
        val calls = MultiCallManager.getAllCalls()
        
        // Buscar la llamada específica por callId
        val callData = calls.find { it.callId == stateInfo.callId }
            ?: manager.currentAccountInfo?.currentCallData
            ?: return null

        val account = manager.currentAccountInfo ?: return null
        val currentCall1 = calls.size == 1 && 
            stateInfo.state != CallState.ENDED && 
            stateInfo.state != CallState.ERROR && 
            stateInfo.state != CallState.ENDING && 
            stateInfo.state != CallState.IDLE
        
        return try {
            CallInfo(
                callId = callData.callId,
                phoneNumber = if (callData.direction == CallDirections.INCOMING) callData.from else callData.to,
                displayName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
                direction = if (callData.direction == CallDirections.INCOMING) CallDirection.INCOMING else CallDirection.OUTGOING,
                startTime = callData.startTime,
                duration = if (callData.startTime > 0) System.currentTimeMillis() - callData.startTime else 0,
                isOnHold = callData.isOnHold ?: false,
                isMuted = manager.webRtcManager.isMuted(),
                localAccount = account.username,
                codec = null,
                state = stateInfo.state,
                isCurrentCall = currentCall1
            )
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating CallInfo: ${e.message}" }
            null
        }
    }

    /**
     * Método getCurrentCallInfo() actualizado
     */
    private fun getCurrentCallInfo(): CallInfo? {
        val manager = sipCoreManager ?: return null
        val account = manager.currentAccountInfo ?: return null
        val calls = MultiCallManager.getAllCalls()
        val callData = calls.firstOrNull() ?: account.currentCallData ?: return null
        
        val currentCall1 = calls.size == 1 && 
            CallStateManager.getCurrentState().let { state ->
                state.state != CallState.ENDED && 
                state.state != CallState.ERROR && 
                state.state != CallState.ENDING && 
                state.state != CallState.IDLE
            }

        return try {
            // Obtener estado actual
            val currentState = CallStateManager.getCurrentState()

            CallInfo(
                callId = callData.callId,
                phoneNumber = if (callData.direction == CallDirections.INCOMING) callData.from else callData.to,
                displayName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
                direction = if (callData.direction == CallDirections.INCOMING) CallDirection.INCOMING else CallDirection.OUTGOING,
                startTime = manager.callStartTimeMillis,
                duration = if (manager.callStartTimeMillis > 0) System.currentTimeMillis() - manager.callStartTimeMillis else 0,
                isOnHold = callData.isOnHold ?: false,
                isMuted = manager.webRtcManager.isMuted(),
                localAccount = account.username,
                codec = null,
                state = currentState.state,
                isCurrentCall = currentCall1
            )
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating CallInfo: ${e.message}" }
            null
        }
    }

    private fun mapErrorReasonToCallEndReason(errorReason: CallErrorReason): CallEndReason {
        return when (errorReason) {
            CallErrorReason.BUSY -> CallEndReason.BUSY
            CallErrorReason.NO_ANSWER -> CallEndReason.NO_ANSWER
            CallErrorReason.REJECTED -> CallEndReason.REJECTED
            CallErrorReason.NETWORK_ERROR -> CallEndReason.NETWORK_ERROR
            else -> CallEndReason.ERROR
        }
    }

    private fun generateCallId(): String {
        return "call_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }

    // === MÉTODOS PÚBLICOS DE LA API ===

    /**
     * Registra una cuenta SIP
     */
    fun registerAccount(
        username: String,
        password: String,
        domain: String? = null,
        pushToken: String? = null,
        pushProvider: String = "fcm"
    ) {
        checkInitialized()

        val finalDomain = domain ?: sipCoreManager?.getDefaultDomain() ?: "mcn.ru"
        val finalToken = pushToken ?: ""

        log.d(tag = TAG) { "Registering account: $username@$finalDomain" }

        sipCoreManager?.register(
            username = username,
            password = password,
            domain = finalDomain,
            provider = pushProvider,
            token = finalToken
        )
    }

    /**
     * Desregistra una cuenta SIP específica
     */
    fun unregisterAccount(username: String, domain: String) {
        checkInitialized()
        log.d(tag = TAG) { "Unregistering account: $username@$domain" }
        sipCoreManager?.unregister(username, domain)
    }

    /**
     * Desregistra todas las cuentas
     */
    fun unregisterAllAccounts() {
        checkInitialized()
        log.d(tag = TAG) { "Unregistering all accounts" }
        sipCoreManager?.unregisterAllAccounts()
    }

    /**
     * Obtiene el estado de registro para una cuenta específica
     */
    fun getRegistrationState(username: String, domain: String): RegistrationState {
        checkInitialized()
        val accountKey = "$username@$domain"
        return sipCoreManager?.getRegistrationState(accountKey) ?: RegistrationState.NONE
    }

    /**
     * Obtiene todos los estados de registro
     */
    fun getAllRegistrationStates(): Map<String, RegistrationState> {
        checkInitialized()
        return sipCoreManager?.getAllRegistrationStates() ?: emptyMap()
    }

    /**
     * Flow para observar estados de registro de todas las cuentas
     */
    fun getRegistrationStatesFlow(): Flow<Map<String, RegistrationState>> {
        checkInitialized()
        return sipCoreManager?.registrationStatesFlow ?: flowOf(emptyMap())
    }

    /**
     * Flow para observar estados de llamada
     */
    fun getCallStateFlow(): Flow<CallStateInfo> {
        checkInitialized()
        return CallStateManager.callStateFlow
    }

    /**
     * Obtener estado actual
     */
    fun getCurrentCallState(): CallStateInfo {
        checkInitialized()
        return CallStateManager.getCurrentState()
    }

    /**
     * Obtener historial de estados de llamada
     */
    fun getCallStateHistory(): List<CallStateInfo> {
        checkInitialized()
        return CallStateManager.getStateHistory()
    }

    /**
     * Limpiar historial de estados
     */
    fun clearCallStateHistory() {
        checkInitialized()
        CallStateManager.clearHistory()
    }

    /**
     * Cambia el dispositivo de audio (entrada o salida) durante una llamada.
     */
    fun changeAudioDevice(device: AudioDevice) {
        checkInitialized()
        sipCoreManager?.changeAudioDevice(device)
    }

    /**
     * Refresca la lista de dispositivos de audio disponibles.
     */
    fun refreshAudioDevices() {
        checkInitialized()
        sipCoreManager?.refreshAudioDevices()
    }

    /**
     * Devuelve el par de dispositivos de audio actuales (input, output).
     */
    fun getCurrentAudioDevices(): Pair<AudioDevice?, AudioDevice?> {
        checkInitialized()
        return sipCoreManager?.getCurrentDevices() ?: Pair(null, null)
    }

    /**
     * Devuelve todos los dispositivos de audio disponibles (inputs, outputs).
     */
    fun getAvailableAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        checkInitialized()
        return sipCoreManager?.getAudioDevices() ?: Pair(emptyList(), emptyList())
    }

    /**
     * Realiza una llamada
     */
    fun makeCall(
        phoneNumber: String,
        username: String? = null,
        domain: String? = null
    ) {
        checkInitialized()

        val finalUsername = username ?: sipCoreManager?.getCurrentUsername()
        val finalDomain = domain ?: sipCoreManager?.getDefaultDomain() ?: ""

        if (finalUsername == null) {
            throw SipLibraryException("No registered account available for calling")
        }

        log.d(tag = TAG) { "Making call to $phoneNumber from $finalUsername@$finalDomain" }

        sipCoreManager?.makeCall(phoneNumber, finalUsername, finalDomain)
    }

    /**
     * Acepta una llamada (con soporte para múltiples llamadas)
     */
    fun acceptCall(callId: String? = null) {
        checkInitialized()
        
        val calls = MultiCallManager.getAllCalls()
        val targetCallId = if (callId == null && calls.size == 1) {
            // Llamada única, usar sin callId
            log.d(tag = TAG) { "Accepting single call" }
            sipCoreManager?.acceptCall()
            return
        } else {
            callId ?: calls.firstOrNull()?.callId
        }
        
        if (targetCallId != null) {
            log.d(tag = TAG) { "Accepting call: $targetCallId" }
            sipCoreManager?.acceptCall(targetCallId)
        } else {
            log.w(tag = TAG) { "No call to accept" }
        }
    }

    /**
     * Rechaza una llamada (con soporte para múltiples llamadas)
     */
    fun declineCall(callId: String? = null) {
        checkInitialized()
        
        val calls = MultiCallManager.getAllCalls()
        val targetCallId = if (callId == null && calls.size == 1) {
            // Llamada única, usar sin callId
            log.d(tag = TAG) { "Declining single call" }
            sipCoreManager?.declineCall()
            return
        } else {
            callId ?: calls.firstOrNull()?.callId
        }
        
        if (targetCallId != null) {
            log.d(tag = TAG) { "Declining call: $targetCallId" }
            sipCoreManager?.declineCall(targetCallId)
        } else {
            log.w(tag = TAG) { "No call to decline" }
        }
    }

    /**
     * Termina una llamada (con soporte para múltiples llamadas)
     */
    fun endCall(callId: String? = null) {
        checkInitialized()
        
        val calls = MultiCallManager.getAllCalls()
        val targetCallId = if (callId == null && calls.size == 1) {
            // Llamada única, usar sin callId
            log.d(tag = TAG) { "Ending single call" }
            sipCoreManager?.endCall()
            return
        } else {
            callId ?: calls.firstOrNull()?.callId
        }
        
        if (targetCallId != null) {
            log.d(tag = TAG) { "Ending call: $targetCallId" }
            sipCoreManager?.endCall(targetCallId)
        } else {
            log.w(tag = TAG) { "No call to end" }
        }
    }

    /**
     * Pone una llamada en espera (con soporte para múltiples llamadas)
     */
    fun holdCall(callId: String? = null) {
        checkInitialized()
        
        val calls = MultiCallManager.getAllCalls()
        val targetCallId = if (callId == null && calls.size == 1) {
            // Llamada única, usar sin callId
            log.d(tag = TAG) { "Holding single call" }
            sipCoreManager?.holdCall()
            return
        } else {
            callId ?: calls.firstOrNull()?.callId
        }
        
        if (targetCallId != null) {
            log.d(tag = TAG) { "Holding call: $targetCallId" }
            sipCoreManager?.holdCall(targetCallId)
        } else {
            log.w(tag = TAG) { "No call to hold" }
        }
    }

    /**
     * Reanuda una llamada (con soporte para múltiples llamadas)
     */
    fun resumeCall(callId: String? = null) {
        checkInitialized()
        
        val calls = MultiCallManager.getAllCalls()
        val targetCallId = if (callId == null && calls.size == 1) {
            // Llamada única, usar sin callId
            log.d(tag = TAG) { "Resuming single call" }
            sipCoreManager?.resumeCall()
            return
        } else {
            callId ?: calls.firstOrNull()?.callId
        }
        
        if (targetCallId != null) {
            log.d(tag = TAG) { "Resuming call: $targetCallId" }
            sipCoreManager?.resumeCall(targetCallId)
        } else {
            log.w(tag = TAG) { "No call to resume" }
        }
    }
    
    /**
     * Alias para resumeCall para compatibilidad
     */
    fun unholdCall(callId: String? = null) = resumeCall(callId)

    /**
     * Obtiene todas las llamadas activas
     */
    fun getAllCalls(): List<CallInfo> {
        checkInitialized()
        return MultiCallManager.getAllCalls().mapNotNull { callData ->
            try {
                val account = sipCoreManager?.currentAccountInfo ?: return@mapNotNull null
                val calls = MultiCallManager.getAllCalls()
                val currentCall1 = calls.size == 1 && 
                    CallStateManager.getCurrentState().let { state ->
                        state.state != CallState.ENDED && 
                        state.state != CallState.ERROR && 
                        state.state != CallState.ENDING && 
                        state.state != CallState.IDLE
                    }
                
                CallInfo(
                    callId = callData.callId,
                    phoneNumber = if (callData.direction == CallDirections.INCOMING) callData.from else callData.to,
                    displayName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
                    direction = if (callData.direction == CallDirections.INCOMING) CallDirection.INCOMING else CallDirection.OUTGOING,
                    startTime = callData.startTime,
                    duration = if (callData.startTime > 0) System.currentTimeMillis() - callData.startTime else 0,
                    isOnHold = callData.isOnHold ?: false,
                    isMuted = sipCoreManager?.webRtcManager?.isMuted() ?: false,
                    localAccount = account.username,
                    codec = null,
                    state = CallStateManager.getStateForCall(callData.callId)?.state,
                    isCurrentCall = currentCall1 && callData.callId == CallStateManager.getCurrentCallId()
                )
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error creating CallInfo for ${callData.callId}: ${e.message}" }
                null
            }
        }
    }

    fun toggleMute() {
        checkInitialized()
        log.d(tag = TAG) { "Toggling mute" }
        sipCoreManager?.mute()

        getCurrentCallInfo()?.let { callInfo ->
            val isMuted = sipCoreManager?.webRtcManager?.isMuted() ?: false
            val mutedCallInfo = callInfo.copy(isMuted = isMuted)
            try {
                callListener?.onMuteStateChanged(isMuted, mutedCallInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in CallListener onMuteStateChanged: ${e.message}" }
            }
        }
    }

    // === MÉTODOS DE INFORMACIÓN ===

    fun hasActiveCall(): Boolean {
        checkInitialized()
        return MultiCallManager.hasActiveCalls()
    }

    // === MÉTODOS ADICIONALES ===

    fun sendDtmf(digit: Char, duration: Int = 160): Boolean {
        checkInitialized()
        return sipCoreManager?.sendDtmf(digit, duration) ?: false
    }

    fun sendDtmfSequence(digits: String, duration: Int = 160): Boolean {
        checkInitialized()
        return sipCoreManager?.sendDtmfSequence(digits, duration) ?: false
    }

    fun isMuted(): Boolean {
        checkInitialized()
        return sipCoreManager?.webRtcManager?.isMuted() ?: false
    }

    fun getAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        checkInitialized()
        return sipCoreManager?.webRtcManager?.getAllAudioDevices() ?: Pair(emptyList(), emptyList())
    }

    fun changeAudioOutput(device: AudioDevice): Boolean {
        checkInitialized()
        return sipCoreManager?.webRtcManager?.changeAudioOutputDeviceDuringCall(device) ?: false
    }

    fun getCallLogs(): List<CallLog> {
        checkInitialized()
        return sipCoreManager?.callLogs() ?: emptyList()
    }

    fun getMissedCalls(): List<CallLog> {
        checkInitialized()
        return sipCoreManager?.getMissedCalls() ?: emptyList()
    }

    fun getCallLogsForNumber(phoneNumber: String): List<CallLog> {
        checkInitialized()
        return sipCoreManager?.getCallLogsForNumber(phoneNumber) ?: emptyList()
    }

    fun clearCallLogs() {
        checkInitialized()
        sipCoreManager?.clearCallLogs()
    }

    fun updatePushToken(token: String, provider: String = "fcm") {
        checkInitialized()
        sipCoreManager?.enterPushMode(token)
    }

    fun getSystemHealthReport(): String {
        checkInitialized()
        return sipCoreManager?.getSystemHealthReport() ?: "Library not initialized"
    }

    fun isSystemHealthy(): Boolean {
        checkInitialized()
        return sipCoreManager?.isSipCoreManagerHealthy() ?: false
    }

    private fun checkInitialized() {
        if (!isInitialized || sipCoreManager == null) {
            throw SipLibraryException("Library not initialized. Call initialize() first.")
        }
    }

    fun diagnoseListeners(): String {
        return buildString {
            appendLine("=== LISTENERS DIAGNOSTIC ===")
            appendLine("Library initialized: $isInitialized")
            appendLine("SipCoreManager: ${sipCoreManager != null}")
            appendLine("General listeners count: ${listeners.size}")
            appendLine("Registration listener: ${registrationListener != null}")
            appendLine("Call listener: ${callListener != null}")
            appendLine("Incoming call listener: ${incomingCallListener != null}")

            appendLine("\n--- Current States ---")
            appendLine("Current call state: ${getCurrentCallState()}")
            appendLine("Registration states: ${getAllRegistrationStates()}")

            appendLine("\n--- Active Accounts ---")
            sipCoreManager?.let { manager ->
                appendLine("Current account: ${manager.getCurrentUsername()}")
                appendLine("Core manager healthy: ${manager.isSipCoreManagerHealthy()}")
            }

            appendLine("\n--- Call State History ---")
            val history = getCallStateHistory()
            appendLine("History entries: ${history.size}")
            history.takeLast(5).forEach { state ->
                appendLine("${state.timestamp}: ${state.previousState} -> ${state.state}")
            }
        }
    }

    fun dispose() {
        if (isInitialized) {
            log.d(tag = TAG) { "Disposing EddysSipLibrary" }
            sipCoreManager?.dispose()
            sipCoreManager = null
            listeners.clear()
            registrationListener = null
            callListener = null
            incomingCallListener = null
            isInitialized = false
            log.d(tag = TAG) { "EddysSipLibrary disposed" }
        }
    }

    // === INTERFAZ INTERNA DE CALLBACKS ===

    internal interface SipCallbacks {
        fun onCallTerminated() {}
        fun onRegistrationStateChanged(state: RegistrationState) {}
        fun onAccountRegistrationStateChanged(username: String, domain: String, state: RegistrationState) {}
        fun onIncomingCall(callerNumber: String, callerName: String?) {}
        fun onCallConnected() {}
        fun onCallFailed(error: String) {}
    }

    class SipLibraryException(message: String, cause: Throwable? = null) : Exception(message, cause)
}