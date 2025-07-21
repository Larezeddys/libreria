package com.eddyslarez.siplibrary.utils

import com.eddyslarez.siplibrary.data.models.RegistrationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock

/**
 * Gestor optimizado de estados de registro con prevención de duplicados
 * 
 * @author Eddys Larez
 */
object RegistrationStateManager {
    
    data class RegistrationStateInfo(
        val state: RegistrationState,
        val previousState: RegistrationState?,
        val timestamp: Long,
        val accountKey: String = "",
        val errorMessage: String? = null
    )
    
    // Estados por cuenta individual
    private val _accountStatesFlow = MutableStateFlow<Map<String, RegistrationStateInfo>>(emptyMap())
    val accountStatesFlow: StateFlow<Map<String, RegistrationStateInfo>> = _accountStatesFlow.asStateFlow()
    
    // Estado global (para compatibilidad temporal)
    private val _globalStateFlow = MutableStateFlow(RegistrationState.NONE)
    val globalStateFlow: StateFlow<RegistrationState> = _globalStateFlow.asStateFlow()
    
    // Historial de cambios de estado
    private val _stateHistoryFlow = MutableStateFlow<List<RegistrationStateInfo>>(emptyList())
    val stateHistoryFlow: StateFlow<List<RegistrationStateInfo>> = _stateHistoryFlow.asStateFlow()
    
    /**
     * Actualiza el estado de registro para una cuenta específica
     */
    fun updateAccountState(
        accountKey: String,
        newState: RegistrationState,
        errorMessage: String? = null
    ): Boolean {
        val currentStates = _accountStatesFlow.value
        val currentStateInfo = currentStates[accountKey]
        
        // Prevenir duplicados del mismo estado
        if (currentStateInfo?.state == newState && currentStateInfo.errorMessage == errorMessage) {
            log.d(tag = "RegistrationStateManager") { 
                "Duplicate state transition prevented: $newState for account $accountKey" 
            }
            return false
        }
        
        // Crear nueva información de estado
        val newStateInfo = RegistrationStateInfo(
            state = newState,
            previousState = currentStateInfo?.state,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            accountKey = accountKey,
            errorMessage = errorMessage
        )
        
        // Actualizar estados por cuenta
        val updatedStates = currentStates.toMutableMap()
        updatedStates[accountKey] = newStateInfo
        _accountStatesFlow.value = updatedStates
        
        // Actualizar estado global (usar la primera cuenta registrada exitosamente)
        val globalState = when {
            updatedStates.values.any { it.state == RegistrationState.OK } -> RegistrationState.OK
            updatedStates.values.any { it.state == RegistrationState.IN_PROGRESS } -> RegistrationState.IN_PROGRESS
            updatedStates.values.any { it.state == RegistrationState.FAILED } -> RegistrationState.FAILED
            else -> RegistrationState.NONE
        }
        _globalStateFlow.value = globalState
        
        // Añadir al historial
        val currentHistory = _stateHistoryFlow.value.toMutableList()
        currentHistory.add(newStateInfo)
        
        // Mantener solo los últimos 100 estados
        if (currentHistory.size > 100) {
            currentHistory.removeAt(0)
        }
        _stateHistoryFlow.value = currentHistory
        
        log.d(tag = "RegistrationStateManager") { 
            "State transition for $accountKey: ${currentStateInfo?.state} -> $newState" 
        }
        
        return true
    }
    
    /**
     * Remueve una cuenta del gestor de estados
     */
    fun removeAccount(accountKey: String) {
        val currentStates = _accountStatesFlow.value.toMutableMap()
        currentStates.remove(accountKey)
        _accountStatesFlow.value = currentStates
        
        // Recalcular estado global
        val globalState = when {
            currentStates.values.any { it.state == RegistrationState.OK } -> RegistrationState.OK
            currentStates.values.any { it.state == RegistrationState.IN_PROGRESS } -> RegistrationState.IN_PROGRESS
            currentStates.values.any { it.state == RegistrationState.FAILED } -> RegistrationState.FAILED
            else -> RegistrationState.NONE
        }
        _globalStateFlow.value = globalState
        
        log.d(tag = "RegistrationStateManager") { "Account $accountKey removed from state manager" }
    }
    
    /**
     * Limpia todos los estados
     */
    fun clearAllStates() {
        _accountStatesFlow.value = emptyMap()
        _globalStateFlow.value = RegistrationState.CLEARED
        log.d(tag = "RegistrationStateManager") { "All registration states cleared" }
    }
    
    // === MÉTODOS DE CONSULTA ===
    
    fun getAccountState(accountKey: String): RegistrationState {
        return _accountStatesFlow.value[accountKey]?.state ?: RegistrationState.NONE
    }
    
    fun getAllAccountStates(): Map<String, RegistrationState> {
        return _accountStatesFlow.value.mapValues { it.value.state }
    }
    
    fun getGlobalState(): RegistrationState = _globalStateFlow.value
    
    fun getRegisteredAccounts(): List<String> {
        return _accountStatesFlow.value.filter { it.value.state == RegistrationState.OK }.keys.toList()
    }
    
    fun hasRegisteredAccounts(): Boolean {
        return _accountStatesFlow.value.values.any { it.state == RegistrationState.OK }
    }
    
    fun getFailedAccounts(): List<String> {
        return _accountStatesFlow.value.filter { it.value.state == RegistrationState.FAILED }.keys.toList()
    }
    
    fun getStateHistory(): List<RegistrationStateInfo> = _stateHistoryFlow.value
    
    fun clearHistory() {
        _stateHistoryFlow.value = emptyList()
    }
    
    /**
     * Diagnóstico del estado actual
     */
    fun getDiagnosticInfo(): String {
        val accountStates = _accountStatesFlow.value
        val globalState = _globalStateFlow.value
        val history = getStateHistory()
        
        return buildString {
            appendLine("=== REGISTRATION STATE DIAGNOSTIC ===")
            appendLine("Global State: $globalState")
            appendLine("Total Accounts: ${accountStates.size}")
            appendLine("Registered Accounts: ${getRegisteredAccounts().size}")
            appendLine("Failed Accounts: ${getFailedAccounts().size}")
            appendLine("History Count: ${history.size}")
            
            appendLine("\n--- Account States ---")
            accountStates.forEach { (accountKey, stateInfo) ->
                appendLine("$accountKey: ${stateInfo.state} (${stateInfo.errorMessage ?: "OK"})")
            }
            
            if (history.isNotEmpty()) {
                appendLine("\n--- Recent State History ---")
                history.takeLast(10).forEach { stateInfo ->
                    appendLine("${stateInfo.timestamp}: ${stateInfo.accountKey} -> ${stateInfo.previousState} -> ${stateInfo.state}")
                }
            }
        }
    }
}