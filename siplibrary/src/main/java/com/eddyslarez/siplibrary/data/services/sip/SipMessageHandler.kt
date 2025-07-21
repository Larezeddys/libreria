package com.eddyslarez.siplibrary.data.services.sip

import com.eddyslarez.siplibrary.core.SipCoreManager
import com.eddyslarez.siplibrary.data.models.*
import com.eddyslarez.siplibrary.data.services.audio.SdpType
import com.eddyslarez.siplibrary.utils.CallStateManager
import com.eddyslarez.siplibrary.utils.MultiCallManager
import com.eddyslarez.siplibrary.utils.generateId
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
/**
 * Handles SIP message processing and generation with improved state management
 * Versión optimizada usando únicamente los nuevos estados
 *
 * @author Eddys Larez
 */
class SipMessageHandler(private val sipCoreManager: SipCoreManager) {

    private var callInitiationTimeout: Job? = null
    private val inviteRetryMap = mutableMapOf<String, Int>()
    private val retryJobs = mutableMapOf<String, Job>()

    private fun terminateCall() {
        sipCoreManager.audioManager.stopAllRingtones()
        sipCoreManager.handleCallTermination()
    }

    companion object {
        private const val TAG = "SipMessageHandler"
        private const val SIP_VERSION = "SIP/2.0"

        private const val CODE_TRYING = 100
        private const val CODE_RINGING = 180
        private const val CODE_SESSION_PROGRESS = 183
        private const val CODE_OK = 200
        private const val CODE_UNAUTHORIZED = 401
        private const val CODE_FORBIDDEN = 403
        private const val CODE_BUSY = 486
        private const val CODE_REQUEST_TERMINATED = 487
        private const val CODE_NOT_ACCEPTABLE = 488
        private const val CODE_DECLINE = 603

        private const val MAX_INVITE_RETRIES = 2
        private const val RETRY_DELAY_MS = 2000L
    }

    /**
     * Process SIP messages received via WebSocket
     */
    fun handleSipMessage(message: String, accountInfo: AccountInfo) {
        try {
            logIncomingMessage(message)
            val lines = message.split("\r\n")
            val firstLine = lines.firstOrNull() ?: return

            updateCSeqIfPresent(lines, accountInfo)

            when {
                firstLine.startsWith(SIP_VERSION) -> handleSipResponse(
                    firstLine,
                    message,
                    accountInfo,
                    lines
                )

                isValidSipRequest(firstLine) -> handleSipRequest(
                    firstLine,
                    message,
                    accountInfo,
                    lines
                )
            }
        } catch (e: Exception) {
            log.d(tag = TAG) { "Error in handleSipMessage: ${e.stackTraceToString()}" }
        }
    }

    private fun isValidSipRequest(firstLine: String): Boolean {
        return firstLine.startsWith("INVITE ") ||
                firstLine.startsWith("BYE ") ||
                firstLine.startsWith("CANCEL ") ||
                firstLine.startsWith("ACK ")
    }

    private fun logIncomingMessage(message: String) {
        log.d(tag = TAG) { "\n=== INCOMING SIP MESSAGE ===" }
        log.d(tag = TAG) { message.take(500) }
        log.d(tag = TAG) { "=== END OF MESSAGE ===" }
    }

    private fun updateCSeqIfPresent(lines: List<String>, accountInfo: AccountInfo) {
        lines.find { it.startsWith("CSeq:", ignoreCase = true) }?.let { cseqLine ->
            val parts = cseqLine.split("\\s+".toRegex())
            if (parts.size >= 2) {
                parts[1].toIntOrNull()?.let { seqNum ->
                    accountInfo.cseq = seqNum
                    log.d(tag = TAG) { "Updated accountInfo.cseq = $seqNum" }
                }
            }
        }
    }

    /**
     * Handle SIP requests
     */
    private fun handleSipRequest(
        requestLine: String,
        message: String,
        accountInfo: AccountInfo,
        lines: List<String>
    ) {
        val method = requestLine.split(" ")[0]

        when (method) {
            "INVITE" -> handleIncomingInvite(message, accountInfo, lines)
            "BYE" -> handleIncomingBye(message, accountInfo, lines)
            "CANCEL" -> handleIncomingCancel(message, accountInfo, lines)
            "ACK" -> handleIncomingAck(message, accountInfo, lines)
        }
    }

    /**
     * Handle SIP responses
     */
    fun handleSipResponse(
        statusLine: String,
        message: String,
        accountInfo: AccountInfo,
        lines: List<String>
    ) {
        val statusCode = statusLine.split(" ")[1].toIntOrNull()
        val method = SipMessageParser.extractMethodFromCSeq(message, lines)
        val isReInvite = method == "INVITE" && accountInfo.currentCallData?.isOnHold != null

        log.d(tag = TAG) { "Status code: $statusCode, Method: $method, IsReInvite: $isReInvite" }

        when (method) {
            "REGISTER" -> handleRegisterResponse(statusCode, message, accountInfo, lines)
            "INVITE" -> {
                if (isReInvite) {
                    handleReInviteResponse(statusCode, message, accountInfo, lines)
                } else {
                    handleInviteResponse(statusCode, message, accountInfo, lines)
                }
            }
            "BYE" -> handleByeResponse(statusCode, message, accountInfo, lines)
            "CANCEL" -> handleCancelResponse(statusCode, message, accountInfo, lines)
            else -> log.d(tag = TAG) { "Response for unhandled method: $method" }
        }
    }

    // ===================== INCOMING REQUEST HANDLERS =====================

    private fun handleIncomingInvite(
        message: String,
        accountInfo: AccountInfo,
        lines: List<String>
    ) {
        log.d(tag = TAG) { "🔔 Incoming call received" }
        sipCoreManager.currentAccountInfo = accountInfo
        sipCoreManager.isCallFromPush = true

        try {
            val callData = createIncomingCallData(message, lines, accountInfo)
            setupIncomingCall(callData, accountInfo, lines)

            // Actualizar estado detallado
            CallStateManager.incomingCallReceived(callData.callId, callData.from)

            // Send immediate responses
            sendTrying(accountInfo, callData)

            // Delayed ringing and UI updates
            CoroutineScope(Dispatchers.IO).launch {
                delay(50)
                sendRinging(accountInfo, callData)
                updateUIForIncomingCall(callData)
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling incoming INVITE: ${e.stackTraceToString()}" }
            accountInfo.currentCallData?.let { callData ->
                CallStateManager.callError(
                    callData.callId,
                    errorReason = CallErrorReason.NETWORK_ERROR
                )
            }
        }
    }

    private fun createIncomingCallData(
        message: String,
        lines: List<String>,
        accountInfo: AccountInfo
    ): CallData {
        val callId = SipMessageParser.extractHeader(lines, "Call-ID")
        val via = SipMessageParser.extractHeader(lines, "Via")
        val from = SipMessageParser.extractHeader(lines, "From")
        val fromTag = SipMessageParser.extractTag(from)
        val toTag = generateId()
        val sdpContent = SipMessageParser.extractSdpContent(message)
        val fromDisplayName = SipMessageParser.extractDisplayName(from)
        val fromUri = SipMessageParser.extractUriFromHeader(from)
        val fromNumber = SipMessageParser.extractUserFromUri(fromUri)

        return CallData(
            callId = callId,
            to = accountInfo.username,
            from = fromNumber,
            direction = CallDirections.INCOMING,
            remoteDisplayName = fromDisplayName,
            remoteSdp = sdpContent,
            via = via,
            fromTag = fromTag,
            toTag = toTag,
            inviteFromTag = fromTag,
            inviteToTag = toTag
        ).apply {
            originalInviteMessage = message
        }
    }

    private fun setupIncomingCall(
        callData: CallData,
        accountInfo: AccountInfo,
        lines: List<String>
    ) {
        val cseqHeader = SipMessageParser.extractHeader(lines, "CSeq")
        val cseqValue = cseqHeader.split(" ")[0].toIntOrNull() ?: accountInfo.cseq

        accountInfo.apply {
            cseq = cseqValue
            currentCallData = callData
            this.callId = callData.callId
            fromTag = callData.fromTag
            toTag = callData.toTag
        }
    }

    private fun updateUIForIncomingCall(callData: CallData) {
        log.d(tag = TAG) { "Updating UI for incoming call from ${callData.from}" }

        CallStateManager.callerNumber(callData.from)
        CallStateManager.callId(callData.callId)

        sipCoreManager.notifyCallStateChanged(CallState.INCOMING_RECEIVED)

        CoroutineScope(Dispatchers.IO).launch {
            sipCoreManager.audioManager.playRingtone()
        }

        sipCoreManager.windowManager.bringToFront()
    }

    private fun handleTerminationRequest(
        message: String,
        accountInfo: AccountInfo,
        lines: List<String>,
        requestType: String
    ) {
        log.d(tag = TAG) { "📞 $requestType received" }

        try {
            val callId = SipMessageParser.extractHeader(lines, "Call-ID")
            val currentCallData = accountInfo.currentCallData

            if (currentCallData == null || currentCallData.callId != callId) {
                log.d(tag = TAG) { "$requestType received for non-active call, ignoring" }
                return
            }

            clearRetryData(callId)

            // Iniciar proceso de finalización
            CallStateManager.startEnding(callId)

            when (requestType) {
                "BYE" -> {
                    val sipResponse = SipMessageBuilder.buildByeOkResponse(accountInfo, lines)
                    accountInfo.webSocketClient?.send(sipResponse)
                    log.d(tag = TAG) { "Sending 200 OK for BYE" }

                    val endTime = Clock.System.now().toEpochMilliseconds()
                    val callType = if (sipCoreManager.getCurrentCallState().isConnected()) {
                        CallTypes.SUCCESS
                    } else if (currentCallData.direction == CallDirections.INCOMING) {
                        CallTypes.MISSED
                    } else {
                        CallTypes.ABORTED
                    }

                    sipCoreManager.callHistoryManager.addCallLog(currentCallData, callType, endTime)
                }
                "CANCEL" -> {
                    // Obtener estado específico de la llamada
                    val callState = MultiCallManager.getCallState(callId)
                    if (callState?.state != CallState.INCOMING_RECEIVED && 
                        callState?.state != CallState.OUTGOING_RINGING) {
                        log.d(tag = TAG) { "CANCEL received but call not in INCOMING/RINGING state, ignoring" }
                        return
                    }

                    val okResponse = SipMessageBuilder.buildCancelOkResponse(accountInfo, lines)
                    accountInfo.webSocketClient?.send(okResponse)

                    val requestTerminatedResponse = SipMessageBuilder.buildRequestTerminatedResponse(accountInfo, currentCallData)
                    accountInfo.webSocketClient?.send(requestTerminatedResponse)

                    val endTime = Clock.System.now().toEpochMilliseconds()
                    val callType = if (currentCallData.direction == CallDirections.INCOMING) {
                        CallTypes.MISSED
                    } else {
                        CallTypes.ABORTED
                    }

                    sipCoreManager.callHistoryManager.addCallLog(currentCallData, callType, endTime)
                }
            }

            sipCoreManager.audioManager.stopAllRingtones()

            // Finalizar llamada
            CallStateManager.callEnded(callId)
            sipCoreManager.notifyCallStateChanged(CallState.ENDED)

            // Solo dispose WebRTC si no hay más llamadas activas
            if (MultiCallManager.getAllCalls().size <= 1) {
                sipCoreManager.webRtcManager.dispose()
            }
            
            // Solo limpiar si es la llamada actual
            if (accountInfo.currentCallData?.callId == callId) {
                accountInfo.currentCallData = null
            }
            
            terminateCall()

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling $requestType: ${e.stackTraceToString()}" }
            accountInfo.currentCallData?.let { callData ->
                CallStateManager.callError(
                    callData.callId,
                    errorReason = CallErrorReason.NETWORK_ERROR
                )
            }
        }
    }

    private fun handleIncomingBye(message: String, accountInfo: AccountInfo, lines: List<String>) {
        handleTerminationRequest(message, accountInfo, lines, "BYE")
    }

    private fun handleIncomingCancel(
        message: String,
        accountInfo: AccountInfo,
        lines: List<String>
    ) {
        handleTerminationRequest(message, accountInfo, lines, "CANCEL")
    }

    private fun handleIncomingAck(message: String, accountInfo: AccountInfo, lines: List<String>) {
        log.d(tag = TAG) { "✅ ACK received for call" }

        try {
            val callId = SipMessageParser.extractHeader(lines, "Call-ID")
            val currentCallData = accountInfo.currentCallData

            if (currentCallData == null || currentCallData.callId != callId) {
                log.d(tag = TAG) { "ACK received for non-active call, ignoring" }
                return
            }

            sipCoreManager.audioManager.stopAllRingtones()

            // Transición a streams running
            CallStateManager.streamsRunning(callId)
            sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)

            log.d(tag = TAG) { "🟢 Call connected after receiving ACK" }
            sipCoreManager.callStartTimeMillis = Clock.System.now().toEpochMilliseconds()
            accountInfo.isCallConnected = true
            accountInfo.callStartTime = sipCoreManager.callStartTimeMillis
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling incoming ACK: ${e.stackTraceToString()}" }
            accountInfo.currentCallData?.let { callData ->
                CallStateManager.callError(
                    callData.callId,
                    errorReason = CallErrorReason.NETWORK_ERROR
                )
            }
        }
    }

    // ===================== RESPONSE HANDLERS =====================

    private fun handleRegisterResponse(
        statusCode: Int?,
        message: String,
        accountInfo: AccountInfo,
        lines: List<String>
    ) {
        when (statusCode) {
            CODE_TRYING -> handleTrying()
            CODE_OK -> handleRegisterOk(message, accountInfo, lines)
            CODE_UNAUTHORIZED -> handleAuthenticationChallenge(accountInfo, message, lines)
            else -> handleRegisterError(message, accountInfo, lines)
        }
    }

    private fun handleInviteResponse(
        statusCode: Int?,
        message: String,
        accountInfo: AccountInfo,
        lines: List<String>
    ) {
        // Cancelar timeout si recibimos cualquier respuesta
        callInitiationTimeout?.cancel()
        val callData = accountInfo.currentCallData

        when (statusCode) {
            CODE_TRYING -> {
                handleTrying()
                startCallInitiationTimeout(accountInfo)
            }

            CODE_SESSION_PROGRESS -> {
                callData?.let { data ->
                    CallStateManager.outgoingCallProgress(data.callId, CODE_SESSION_PROGRESS)
                    clearRetryData(data.callId)
                }
                handleSessionProgress()
            }

            CODE_RINGING -> {
                callData?.let { data ->
                    CallStateManager.outgoingCallRinging(data.callId, CODE_RINGING)
                    clearRetryData(data.callId)
                }
                handleRinging()
            }

            CODE_OK -> {
                callData?.let { data ->
                    CallStateManager.callConnected(data.callId, CODE_OK)
                    clearRetryData(data.callId)
                }
                handleInviteOk(message, accountInfo, lines)
                sipCoreManager.audioManager.stopAllRingtones()
            }

            CODE_UNAUTHORIZED -> handleAuthenticationChallenge(accountInfo, message, lines)

            CODE_BUSY -> {
                callData?.let { data ->
                    CallStateManager.callError(
                        data.callId,
                        CODE_BUSY,
                        "Busy Here",
                        CallErrorReason.BUSY
                    )
                    clearRetryData(data.callId)
                }
                handleBusy()
            }

            CODE_FORBIDDEN, CODE_NOT_ACCEPTABLE -> {
                handleRetryableError(statusCode, message, accountInfo, lines)
            }

            CODE_REQUEST_TERMINATED -> {
                callData?.let { data ->
                    CallStateManager.callEnded(data.callId, CODE_REQUEST_TERMINATED, "Request Terminated")
                }
                handleRequestTerminated(accountInfo, lines)
            }

            else -> {
                if (statusCode != null && statusCode >= 400) {
                    handleRetryableError(statusCode, message, accountInfo, lines)
                } else {
                    handleOtherStatusCodes(statusCode)
                }
            }
        }
    }

    private fun handleRetryableError(
        statusCode: Int,
        message: String,
        accountInfo: AccountInfo,
        lines: List<String>
    ) {
        val callData = accountInfo.currentCallData ?: return
        val callId = callData.callId

        log.d(tag = TAG) { "Received retryable error: $statusCode for call $callId" }

        val currentRetries = inviteRetryMap[callId] ?: 0

        if (currentRetries < MAX_INVITE_RETRIES) {
            val nextRetryCount = currentRetries + 1
            inviteRetryMap[callId] = nextRetryCount

            log.d(tag = TAG) { "Scheduling retry $nextRetryCount/${MAX_INVITE_RETRIES} for call $callId after error $statusCode" }

            // Cancelar job de retry anterior si existe
            retryJobs[callId]?.cancel()

            // Programar nuevo intento
            retryJobs[callId] = CoroutineScope(Dispatchers.IO).launch {
                try {
                    delay(RETRY_DELAY_MS)

                    // Verificar que la llamada aún esté activa
                    if (accountInfo.currentCallData?.callId == callId) {
                        log.d(tag = TAG) { "Retrying INVITE for call $callId (attempt $nextRetryCount)" }

                        // Generar nuevo Call-ID y tags para el retry
                        val newCallId = generateId()
                        val newFromTag = generateId()

                        // Actualizar callData con nuevos identificadores
                        callData.callId = newCallId
                        callData.inviteFromTag = newFromTag

                        // Actualizar accountInfo
                        accountInfo.callId = newCallId
                        accountInfo.fromTag = newFromTag

                        // Reiniciar estado para retry
                        CallStateManager.startOutgoingCall(newCallId, callData.to)

                        // Actualizar el mapa de reintentos con el nuevo callId
                        inviteRetryMap.remove(callId)
                        inviteRetryMap[newCallId] = nextRetryCount

                        // Limpiar job anterior
                        retryJobs.remove(callId)

                        // Enviar nuevo INVITE
                        sendInvite(accountInfo, callData)

                        log.d(tag = TAG) { "INVITE retry sent for call $newCallId" }
                    } else {
                        log.d(tag = TAG) { "Call $callId no longer active, canceling retry" }
                        clearRetryData(callId)
                    }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error during INVITE retry: ${e.message}" }
                    clearRetryData(callId)
                    handleFinalCallFailure(accountInfo)
                }
            }
        } else {
            log.d(tag = TAG) { "Max retries (${MAX_INVITE_RETRIES}) reached for call $callId with error $statusCode" }

            // Error final después de reintentos
            CallStateManager.callError(
                callId,
                statusCode,
                "Max retries reached",
                SipErrorMapper.mapSipCodeToErrorReason(statusCode)
            )

            clearRetryData(callId)
            handleFinalCallFailure(accountInfo)
        }
    }

    private fun handleFinalCallFailure(accountInfo: AccountInfo) {
        log.d(tag = TAG) { "Call failed after all retry attempts" }

        accountInfo.currentCallData = null
        accountInfo.resetCallState()

        sipCoreManager.notifyCallStateChanged(CallState.ERROR)
    }

    private fun clearRetryData(callId: String) {
        inviteRetryMap.remove(callId)
        retryJobs[callId]?.cancel()
        retryJobs.remove(callId)
    }

    private fun startCallInitiationTimeout(accountInfo: AccountInfo) {
        callInitiationTimeout = CoroutineScope(Dispatchers.IO).launch {
            delay(30000)
            log.d(tag = TAG) { "Call initiation timeout" }

            accountInfo.currentCallData?.let { callData ->
                CallStateManager.callError(
                    callData.callId,
                    errorReason = CallErrorReason.NO_ANSWER
                )
            }

            sipCoreManager.notifyCallStateChanged(CallState.ERROR)
            accountInfo.currentCallData = null
        }
    }

    private fun handleReInviteResponse(
        statusCode: Int?,
        message: String,
        accountInfo: AccountInfo,
        lines: List<String>
    ) {
        log.d(tag = TAG) { "Response to re-INVITE received: $statusCode" }
        val callData = accountInfo.currentCallData ?: return

        when (statusCode) {
            CODE_TRYING -> log.d(tag = TAG) { "Provisional response (100)" }
            CODE_OK -> {
                log.d(tag = TAG) { "Re-INVITE accepted (200 OK)" }
                val sdpContent = SipMessageParser.extractSdpContent(message)
                callData.remoteSdp = sdpContent
                sendAck(accountInfo, callData)

                // Actualizar estado según hold/resume
                if (callData.isOnHold == true) {
                    CallStateManager.callOnHold(callData.callId)
                } else {
                    CallStateManager.streamsRunning(callData.callId)
                }

                val stateMessage = if (callData.isOnHold == true) "placed on hold" else "resumed"
                log.d(tag = TAG) { "Call $stateMessage successfully" }
            }

            CODE_UNAUTHORIZED -> handleAuthenticationChallenge(accountInfo, message, lines)
            CODE_BUSY -> {
                log.d(tag = TAG) { "Re-INVITE rejected: Busy (486)" }
                restorePreviousHoldState(callData)
            }

            else -> {
                log.d(tag = TAG) { "Unhandled re-INVITE response: $statusCode" }
                restorePreviousHoldState(callData)
            }
        }
    }

    private fun restorePreviousHoldState(callData: CallData) {
        callData.isOnHold = !callData.isOnHold!!

        // Restaurar estado anterior
        if (callData.isOnHold!!) {
            CallStateManager.callOnHold(callData.callId)
        } else {
            CallStateManager.streamsRunning(callData.callId)
        }

        val newState = if (callData.isOnHold!!) CallState.PAUSED else CallState.STREAMS_RUNNING
        sipCoreManager.notifyCallStateChanged(newState)
    }

    private fun handleByeResponse(
        statusCode: Int?,
        message: String,
        accountInfo: AccountInfo,
        lines: List<String>
    ) {
        when (statusCode) {
            CODE_OK -> {
                log.d(tag = TAG) { "BYE accepted by server" }

                accountInfo.currentCallData?.let { callData ->
                    CallStateManager.callEnded(callData.callId, CODE_OK, "OK")
                }

                terminateCall()
            }

            else -> log.d(tag = TAG) { "Unhandled BYE response: $statusCode" }
        }
    }

    private fun handleCancelResponse(
        statusCode: Int?,
        message: String,
        accountInfo: AccountInfo,
        lines: List<String>
    ) {
        when (statusCode) {
            CODE_OK -> {
                log.d(tag = TAG) { "CANCEL accepted (200 OK)" }

                accountInfo.currentCallData?.let { callData ->
                    CallStateManager.callEnded(callData.callId, CODE_OK, "Cancelled")
                }

                terminateCall()
            }

            else -> {
                log.d(tag = TAG) { "Unexpected CANCEL response: $statusCode" }

                accountInfo.currentCallData?.let { callData ->
                    CallStateManager.callEnded(callData.callId, statusCode, "Unexpected response")
                }

                terminateCall()
                sipCoreManager.notifyCallStateChanged(CallState.ENDED)
                accountInfo.resetCallState()
            }
        }
    }

    // ===================== SPECIFIC STATUS HANDLERS =====================

    private fun handleInviteOk(message: String, accountInfo: AccountInfo, lines: List<String>) {
        log.d(tag = TAG) { "Outgoing call accepted (200 OK)" }

        val callData = accountInfo.currentCallData ?: return
        val toHeader = SipMessageParser.extractHeader(lines, "To")
        val toTag = SipMessageParser.extractTag(toHeader)
        val sdpContent = SipMessageParser.extractSdpContent(message)

        callData.remoteSdp = sdpContent
        callData.inviteToTag = toTag

        CoroutineScope(Dispatchers.IO).launch {
            sipCoreManager.webRtcManager.setRemoteDescription(sdpContent, SdpType.ANSWER)
        }

        sendAck(accountInfo, callData)
        sipCoreManager.audioManager.stopAllRingtones()

        sipCoreManager.notifyCallStateChanged(CallState.CONNECTED)
        sipCoreManager.callStartTimeMillis = Clock.System.now().toEpochMilliseconds()
    }

    private fun handleRequestTerminated(accountInfo: AccountInfo, lines: List<String>) {
        log.d(tag = TAG) { "Call was canceled (487 Request Terminated)" }

        val callData = accountInfo.currentCallData
        if (callData != null) {
            val ackMessage = SipMessageBuilder.buildAckFor487Response(accountInfo, callData, lines)
            accountInfo.webSocketClient?.send(ackMessage)
        }
        sipCoreManager.audioManager.stopAllRingtones()

        sipCoreManager.notifyCallStateChanged(CallState.ENDED)
        accountInfo.resetCallState()
    }

    private fun handleTrying() {
        log.d(tag = TAG) { "Trying (100)" }
        sipCoreManager.notifyCallStateChanged(CallState.OUTGOING_INIT)
    }

    private fun handleSessionProgress() {
        log.d(tag = TAG) { "Session Progress (183)" }
        sipCoreManager.notifyCallStateChanged(CallState.OUTGOING_PROGRESS)
    }

    private fun handleRinging() {
        sipCoreManager.notifyCallStateChanged(CallState.OUTGOING_RINGING)
        sipCoreManager.audioManager.playOutgoingRingtone()
        log.d(tag = TAG) { "Call established - Ringing (180)" }
    }

    private fun handleRegisterOk(message: String, accountInfo: AccountInfo, lines: List<String>) {
        val expires = SipMessageParser.extractExpiresValue(message)
        log.d(tag = TAG) { "Successful registration with expiration: $expires seconds" }

        accountInfo.isRegistered = true

        sipCoreManager.handleRegistrationSuccess(accountInfo)

        // Configure renewal
        val expiresMs = expires * 1000L
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
        val expirationTime = Clock.System.now().toEpochMilliseconds() + expiresMs
        accountInfo.webSocketClient?.setRegistrationExpiration(accountKey, expirationTime)

        sipCoreManager.updateRegistrationState(accountKey, RegistrationState.OK)

        log.d(tag = TAG) { "Registration renewal configured for ${accountInfo.username}" }
    }

    private fun handleRegisterError(message: String, accountInfo: AccountInfo, lines: List<String>) {
        log.d(TAG) { "Registration Error" }
        val reason = SipMessageParser.extractStatusReason(message)

        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
        sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)

        sipCoreManager.handleRegistrationError(accountInfo, reason)
    }

    private fun handleBusy() {
        log.d(tag = TAG) { "Call rejected: Busy (486)" }
        sipCoreManager.notifyCallStateChanged(CallState.ERROR)
    }

    private fun handleOtherStatusCodes(statusCode: Int?) {
        log.d(tag = TAG) { "Unhandled SIP code: $statusCode" }

        if (statusCode != null && statusCode >= 400) {
            sipCoreManager.currentAccountInfo?.let { accountInfo ->
                val accountKey = "${accountInfo.username}@${accountInfo.domain}"
                sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)

                // Error en llamada actual si existe
                accountInfo.currentCallData?.let { callData ->
                    CallStateManager.callError(
                        callData.callId,
                        statusCode,
                        "Unhandled error",
                        SipErrorMapper.mapSipCodeToErrorReason(statusCode)
                    )
                }
            }
            sipCoreManager.notifyCallStateChanged(CallState.ERROR)
        }
    }

    private fun handleAuthenticationChallenge(
        accountInfo: AccountInfo,
        message: String,
        lines: List<String>
    ) {
        log.d(tag = TAG) { "Authentication challenge 401" }

        try {
            val method = SipMessageParser.extractMethodFromCSeq(message, lines)
            accountInfo.method = method
            log.d(tag = TAG) { "Method: $method" }

            val authData = AuthenticationHandler.extractAuthenticationData(lines) ?: return
            log.d(tag = TAG) { "authData: $authData" }

            val authResponse =
                AuthenticationHandler.calculateAuthResponse(accountInfo, authData, method)
            log.d(tag = TAG) { "authResponse: $authResponse" }
            AuthenticationHandler.updateAccountAuthInfo(accountInfo, authData, authResponse, method)

            when (method) {
                "REGISTER" -> sendAuthenticatedRegister(accountInfo)
                "INVITE" -> accountInfo.currentCallData?.let {
                    sendAuthenticatedInvite(
                        accountInfo,
                        it
                    )
                }

                else -> log.d(tag = TAG) { "Unhandled method for authentication: $method" }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "CRITICAL ERROR in handleAuthenticationChallenge: ${e.stackTraceToString()}" }
        }
    }

    // ===================== SENDING METHODS =====================

    /**
     * Generic message sender with error handling
     */
    private fun sendSipMessage(
        messageBuilder: () -> String,
        messageType: String,
        accountInfo: AccountInfo
    ) {
        try {
            val sipMessage = messageBuilder()
            log.d(tag = TAG) { "Sending $messageType" }
            accountInfo.webSocketClient?.send(sipMessage)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending $messageType: ${e.stackTraceToString()}" }
        }
    }

    fun sendRegister(accountInfo: AccountInfo, isAppInBackground: Boolean) {
        sendSipMessage(
            messageBuilder = {
                val callId = generateId()
                val fromTag = generateId()
                accountInfo.callId = callId
                accountInfo.fromTag = fromTag
                SipMessageBuilder.buildRegisterMessage(
                    accountInfo,
                    callId,
                    fromTag,
                    isAppInBackground
                )
            },
            messageType = "REGISTER",
            accountInfo = accountInfo
        )
    }

    fun sendInvite(accountInfo: AccountInfo, callData: CallData) {
        sendSipMessage(
            messageBuilder = {
                val sipMessage =
                    SipMessageBuilder.buildInviteMessage(accountInfo, callData, callData.localSdp)
                callData.originalInviteMessage = sipMessage
                callData.storeInviteMessage(sipMessage)
                sipMessage
            },
            messageType = "INVITE",
            accountInfo = accountInfo
        )
    }

    fun sendUnregister(accountInfo: AccountInfo) {
        sendSipMessage(
            messageBuilder = {
                val callId = accountInfo.callId ?: generateId()
                val fromTag = accountInfo.fromTag ?: generateId()
                SipMessageBuilder.buildUnregisterMessage(accountInfo, callId, fromTag)
            },
            messageType = "UNREGISTER",
            accountInfo = accountInfo
        )
    }

    fun sendDeclineResponse(accountInfo: AccountInfo, callData: CallData) {
        sendSipMessage(
            messageBuilder = { SipMessageBuilder.buildDeclineResponse(accountInfo, callData) },
            messageType = "603 DECLINE",
            accountInfo = accountInfo
        )

        // Estado de error por rechazo
        CallStateManager.callError(
            callData.callId,
            603,
            "Decline",
            CallErrorReason.REJECTED
        )
        
        // Detener ringtones
        sipCoreManager.audioManager.stopAllRingtones()

        terminateCall()
    }

    fun sendBye(accountInfo: AccountInfo, callData: CallData) {
        // Iniciar finalización
        CallStateManager.startEnding(callData.callId)

        sendSipMessage(
            messageBuilder = { SipMessageBuilder.buildByeMessage(accountInfo, callData) },
            messageType = "BYE",
            accountInfo = accountInfo
        )
    }

    fun sendTrying(accountInfo: AccountInfo, callData: CallData) {
        sendSipMessage(
            messageBuilder = { SipMessageBuilder.buildTryingResponse(accountInfo, callData) },
            messageType = "100 TRYING",
            accountInfo = accountInfo
        )
    }

    fun sendRinging(accountInfo: AccountInfo, callData: CallData) {
        sendSipMessage(
            messageBuilder = { SipMessageBuilder.buildRingingResponse(accountInfo, callData) },
            messageType = "180 RINGING",
            accountInfo = accountInfo
        )
    }

    fun sendAck(accountInfo: AccountInfo, callData: CallData) {
        sendSipMessage(
            messageBuilder = { SipMessageBuilder.buildAckMessage(accountInfo, callData) },
            messageType = "ACK",
            accountInfo = accountInfo
        )
    }

    fun sendInviteOkResponse(accountInfo: AccountInfo, callData: CallData) {
        // Llamada conectada
        CallStateManager.callConnected(callData.callId, 200)

        sendSipMessage(
            messageBuilder = { SipMessageBuilder.buildInviteOkResponse(accountInfo, callData) },
            messageType = "200 OK (INVITE)",
            accountInfo = accountInfo
        )
    }

    fun sendDtmfInfo(accountInfo: AccountInfo, callData: CallData, digit: Char, duration: Int) {
        sendSipMessage(
            messageBuilder = {
                SipMessageBuilder.buildDtmfInfoMessage(accountInfo, callData, digit, duration)
            },
            messageType = "INFO (DTMF)",
            accountInfo = accountInfo
        )
    }

    fun sendCancel(accountInfo: AccountInfo, callData: CallData) {
        // Iniciar finalización por cancelación
        CallStateManager.startEnding(callData.callId)
        
        // Detener outgoing ringtone inmediatamente
        sipCoreManager.audioManager.stopOutgoingRingtone()

        sendSipMessage(
            messageBuilder = {
                val sipMessage = SipMessageBuilder.buildCancelMessage(accountInfo, callData)
                sipMessage
            },
            messageType = "CANCEL",
            accountInfo = accountInfo
        )
    }

    fun sendReInvite(accountInfo: AccountInfo, callData: CallData, sdp: String) {
        sendSipMessage(
            messageBuilder = { SipMessageBuilder.buildReInviteMessage(accountInfo, callData, sdp) },
            messageType = "RE-INVITE",
            accountInfo = accountInfo
        )
    }

    private fun sendAuthenticatedRegister(accountInfo: AccountInfo) {
        log.d(tag = TAG) { "accountInfo: ${accountInfo.authorizationHeader}" }

        sendSipMessage(
            messageBuilder = {
                SipMessageBuilder.buildAuthenticatedRegisterMessage(
                    accountInfo,
                    sipCoreManager.isAppInBackground
                )
            },
            messageType = "authenticated REGISTER",
            accountInfo = accountInfo
        )
    }

    private fun sendAuthenticatedInvite(accountInfo: AccountInfo, callData: CallData) {
        sendSipMessage(
            messageBuilder = {
                val sipMessage = SipMessageBuilder.buildAuthenticatedInviteMessage(
                    accountInfo,
                    callData,
                    callData.localSdp
                )
                callData.originalCallInviteMessage = sipMessage
                sipMessage
            },
            messageType = "authenticated INVITE",
            accountInfo = accountInfo
        )
    }
}
