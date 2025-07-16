package com.eddyslarez.siplibrary.utils
import com.eddyslarez.siplibrary.data.models.CallData
import com.eddyslarez.siplibrary.data.models.CallState
import com.eddyslarez.siplibrary.data.models.CallStateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock

/**
 * Gestor de múltiples llamadas simultáneas
 *
 * @author Eddys Larez
 */
object MultiCallManager {

    // Scope para corrutinas
    private val scope = CoroutineScope(Dispatchers.IO)

    // Mapa de llamadas activas por callId
    private val _activeCalls = MutableStateFlow<Map<String, CallData>>(emptyMap())
    val activeCallsFlow: StateFlow<Map<String, CallData>> = _activeCalls.asStateFlow()

    // Estados de cada llamada
    private val _callStates = MutableStateFlow<Map<String, CallStateInfo>>(emptyMap())
    val callStatesFlow: StateFlow<Map<String, CallStateInfo>> = _callStates.asStateFlow()

    /**
     * Añade una nueva llamada al gestor
     */
    fun addCall(callData: CallData) {
        val currentCalls = _activeCalls.value.toMutableMap()
        currentCalls[callData.callId] = callData
        _activeCalls.value = currentCalls

        // Inicializar estado de la llamada
        val initialState = if (callData.direction == com.eddyslarez.siplibrary.data.models.CallDirections.INCOMING) {
            CallState.INCOMING_RECEIVED
        } else {
            CallState.OUTGOING_INIT
        }

        updateCallState(callData.callId, initialState)

        // Asumiendo que tienes un logger disponible
        // log.d(tag = "MultiCallManager") { "Call added: ${callData.callId} (${callData.direction})" }
    }

    /**
     * Remueve una llamada del gestor
     */
    fun removeCall(callId: String) {
        val currentCalls = _activeCalls.value.toMutableMap()
        val removedCall = currentCalls.remove(callId)
        _activeCalls.value = currentCalls

        val currentStates = _callStates.value.toMutableMap()
        currentStates.remove(callId)
        _callStates.value = currentStates

        if (removedCall != null) {
            // log.d(tag = "MultiCallManager") { "Call removed: $callId" }
        }
    }

    /**
     * Actualiza el estado de una llamada específica
     */
    fun updateCallState(callId: String, newState: CallState, errorReason: com.eddyslarez.siplibrary.data.models.CallErrorReason = com.eddyslarez.siplibrary.data.models.CallErrorReason.NONE) {
        val currentStates = _callStates.value.toMutableMap()
        val previousState = currentStates[callId]

        val newStateInfo = CallStateInfo(
            state = newState,
            previousState = previousState?.state,
            errorReason = errorReason,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            callId = callId,
            direction = getCall(callId)?.direction ?: com.eddyslarez.siplibrary.data.models.CallDirections.OUTGOING
        )

        currentStates[callId] = newStateInfo
        _callStates.value = currentStates

        // Si la llamada terminó, removerla después de un breve delay
        if (newState == CallState.ENDED || newState == CallState.ERROR) {
            scope.launch {
                delay(1000) // Esperar 1 segundo
                removeCall(callId)
            }
        }

        // log.d(tag = "MultiCallManager") { "Call state updated: $callId -> $newState" }
    }

    /**
     * Obtiene una llamada específica
     */
    fun getCall(callId: String): CallData? {
        return _activeCalls.value[callId]
    }

    /**
     * Obtiene el estado de una llamada específica
     */
    fun getCallState(callId: String): CallStateInfo? {
        return _callStates.value[callId]
    }

    /**
     * Obtiene todas las llamadas activas
     */
    fun getAllCalls(): List<CallData> {
        return _activeCalls.value.values.toList()
    }

    /**
     * Obtiene todos los estados de llamadas
     */
    fun getAllCallStates(): Map<String, CallStateInfo> {
        return _callStates.value
    }

    /**
     * Verifica si hay llamadas activas
     */
    fun hasActiveCalls(): Boolean {
        return _activeCalls.value.isNotEmpty()
    }

    /**
     * Obtiene la llamada actual (primera llamada activa)
     */
    fun getCurrentCall(): CallData? {
        return _activeCalls.value.values.firstOrNull()
    }

    /**
     * Obtiene llamadas por estado
     */
    fun getCallsByState(state: CallState): List<CallData> {
        val callsInState = _callStates.value.filter { it.value.state == state }.keys
        return _activeCalls.value.filter { it.key in callsInState }.values.toList()
    }

    /**
     * Obtiene llamadas entrantes
     */
    fun getIncomingCalls(): List<CallData> {
        return _activeCalls.value.values.filter {
            it.direction == com.eddyslarez.siplibrary.data.models.CallDirections.INCOMING
        }
    }

    /**
     * Obtiene llamadas salientes
     */
    fun getOutgoingCalls(): List<CallData> {
        return _activeCalls.value.values.filter {
            it.direction == com.eddyslarez.siplibrary.data.models.CallDirections.OUTGOING
        }
    }

    /**
     * Obtiene llamadas conectadas
     */
    fun getConnectedCalls(): List<CallData> {
        return getCallsByState(CallState.CONNECTED) + getCallsByState(CallState.STREAMS_RUNNING)
    }

    /**
     * Obtiene llamadas en espera
     */
    fun getHeldCalls(): List<CallData> {
        return getCallsByState(CallState.PAUSED)
    }

    /**
     * Limpia todas las llamadas
     */
    fun clearAllCalls() {
        _activeCalls.value = emptyMap()
        _callStates.value = emptyMap()
         log.d(tag = "MultiCallManager") { "All calls cleared" }
    }

    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        val calls = _activeCalls.value
        val states = _callStates.value

        return buildString {
            appendLine("=== MULTI CALL MANAGER DIAGNOSTIC ===")
            appendLine("Active calls: ${calls.size}")
            appendLine("Call states: ${states.size}")

            appendLine("\n--- Active Calls ---")
            calls.forEach { (callId, callData) ->
                val state = states[callId]?.state ?: "UNKNOWN"
                appendLine("$callId: ${callData.from} -> ${callData.to} ($state)")
            }

            appendLine("\n--- Call States ---")
            states.forEach { (callId, stateInfo) ->
                appendLine("$callId: ${stateInfo.previousState} -> ${stateInfo.state}")
            }
        }
    }
}