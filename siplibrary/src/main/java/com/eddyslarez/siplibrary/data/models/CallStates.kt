package com.eddyslarez.siplibrary.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Estados detallados de llamada basados en el flujo SIP real
 * 
 * @author Eddys Larez
 */

@Parcelize
enum class
CallState : Parcelable {
    // Estados iniciales
    IDLE,
    
    // Estados de llamada saliente
    OUTGOING_INIT,          // Se envía INVITE
    OUTGOING_PROGRESS,      // Se recibe 183 Session Progress
    OUTGOING_RINGING,       // Se recibe 180 Ringing
    
    // Estados de llamada entrante
    INCOMING_RECEIVED,      // Se recibe INVITE
    
    // Estados conectados
    CONNECTED,              // Se recibe/envía 200 OK
    STREAMS_RUNNING,        // RTP fluye después de ACK
    
    // Estados de pausa/hold
    PAUSING,                // Iniciando hold
    PAUSED,                 // En hold
    RESUMING,               // Saliendo de hold
    
    // Estados de finalización
    ENDING,                 // Iniciando finalización
    ENDED,                  // Llamada terminada
    
    // Estados de error
    ERROR                   // Error en cualquier punto
}

@Parcelize
enum class CallErrorReason : Parcelable {
    NONE,
    BUSY,                   // 486 Busy Here
    NO_ANSWER,              // 408 Request Timeout
    REJECTED,               // 603 Decline
    TEMPORARILY_UNAVAILABLE, // 480 Temporarily Unavailable
    NOT_FOUND,              // 404 Not Found
    FORBIDDEN,              // 403 Forbidden
    NETWORK_ERROR,          // Problemas de red
    AUTHENTICATION_FAILED,   // 401/407
    SERVER_ERROR,           // 5xx
    UNKNOWN                 // Otros errores
}

@Parcelize
data class CallStateInfo(
    val state: CallState,
    val previousState: CallState?,
    val errorReason: CallErrorReason = CallErrorReason.NONE,
    val timestamp: Long,
    val sipCode: Int? = null,
    val sipReason: String? = null,
    val callId: String = "",
    val direction: CallDirections = CallDirections.OUTGOING
) : Parcelable {
    
    fun isOutgoingCall(): Boolean = direction == CallDirections.OUTGOING
    fun isIncomingCall(): Boolean = direction == CallDirections.INCOMING
    fun isConnected(): Boolean = state == CallState.CONNECTED || state == CallState.STREAMS_RUNNING
    fun isActive(): Boolean = state != CallState.IDLE && state != CallState.ENDED && state != CallState.ERROR
    fun hasError(): Boolean = state == CallState.ERROR || errorReason != CallErrorReason.NONE
    fun isOnHold(): Boolean = state == CallState.PAUSED
    fun isTransitioning(): Boolean = state == CallState.PAUSING || state == CallState.RESUMING || state == CallState.ENDING
}

/**
 * Mapeo de códigos SIP a razones de error
 */
object SipErrorMapper {
    fun mapSipCodeToErrorReason(sipCode: Int): CallErrorReason {
        return when (sipCode) {
            486 -> CallErrorReason.BUSY
            408 -> CallErrorReason.NO_ANSWER
            603 -> CallErrorReason.REJECTED
            480 -> CallErrorReason.TEMPORARILY_UNAVAILABLE
            404 -> CallErrorReason.NOT_FOUND
            403 -> CallErrorReason.FORBIDDEN
            401, 407 -> CallErrorReason.AUTHENTICATION_FAILED
            in 500..599 -> CallErrorReason.SERVER_ERROR
            else -> CallErrorReason.UNKNOWN
        }
    }
    
    fun getErrorDescription(reason: CallErrorReason): String {
        return when (reason) {
            CallErrorReason.NONE -> "Sin error"
            CallErrorReason.BUSY -> "Ocupado"
            CallErrorReason.NO_ANSWER -> "Sin respuesta"
            CallErrorReason.REJECTED -> "Rechazada"
            CallErrorReason.TEMPORARILY_UNAVAILABLE -> "Temporalmente no disponible"
            CallErrorReason.NOT_FOUND -> "Usuario no encontrado"
            CallErrorReason.FORBIDDEN -> "Prohibido"
            CallErrorReason.NETWORK_ERROR -> "Error de red"
            CallErrorReason.AUTHENTICATION_FAILED -> "Error de autenticación"
            CallErrorReason.SERVER_ERROR -> "Error del servidor"
            CallErrorReason.UNKNOWN -> "Error desconocido"
        }
    }
}

/**
 * Validador de transiciones de estado
 */
object CallStateTransitionValidator {
    
    private val validOutgoingTransitions = mapOf(
        CallState.IDLE to setOf(CallState.OUTGOING_INIT),
        CallState.OUTGOING_INIT to setOf(CallState.OUTGOING_PROGRESS, CallState.OUTGOING_RINGING, CallState.ERROR, CallState.ENDING),
        CallState.OUTGOING_PROGRESS to setOf(CallState.OUTGOING_RINGING, CallState.CONNECTED, CallState.ERROR, CallState.ENDING),
        CallState.OUTGOING_RINGING to setOf(CallState.CONNECTED, CallState.ERROR, CallState.ENDING),
        CallState.CONNECTED to setOf(CallState.STREAMS_RUNNING, CallState.PAUSING, CallState.ENDING, CallState.ERROR),
        CallState.STREAMS_RUNNING to setOf(CallState.PAUSING, CallState.ENDING, CallState.ERROR),
        CallState.PAUSING to setOf(CallState.PAUSED, CallState.ERROR),
        CallState.PAUSED to setOf(CallState.RESUMING, CallState.ENDING, CallState.ERROR),
        CallState.RESUMING to setOf(CallState.STREAMS_RUNNING, CallState.ERROR),
        CallState.ENDING to setOf(CallState.ENDED),
        CallState.ENDED to setOf(CallState.IDLE),
        CallState.ERROR to setOf(CallState.ENDED, CallState.IDLE)
    )
    
    private val validIncomingTransitions = mapOf(
        CallState.IDLE to setOf(CallState.INCOMING_RECEIVED),
        CallState.INCOMING_RECEIVED to setOf(CallState.CONNECTED, CallState.ERROR, CallState.ENDING),
        CallState.CONNECTED to setOf(CallState.STREAMS_RUNNING, CallState.PAUSING, CallState.ENDING, CallState.ERROR),
        CallState.STREAMS_RUNNING to setOf(CallState.PAUSING, CallState.ENDING, CallState.ERROR),
        CallState.PAUSING to setOf(CallState.PAUSED, CallState.ERROR),
        CallState.PAUSED to setOf(CallState.RESUMING, CallState.ENDING, CallState.ERROR),
        CallState.RESUMING to setOf(CallState.STREAMS_RUNNING, CallState.ERROR),
        CallState.ENDING to setOf(CallState.ENDED),
        CallState.ENDED to setOf(CallState.IDLE),
        CallState.ERROR to setOf(CallState.ENDED, CallState.IDLE)
    )
    
    fun isValidTransition(
        from: CallState,
        to: CallState,
        direction: CallDirections
    ): Boolean {
        val validTransitions = if (direction == CallDirections.OUTGOING) {
            validOutgoingTransitions
        } else {
            validIncomingTransitions
        }
        
        return validTransitions[from]?.contains(to) ?: false
    }
    
    fun getValidNextStates(
        currentState: CallState,
        direction: CallDirections
    ): Set<CallState> {
        val validTransitions = if (direction == CallDirections.OUTGOING) {
            validOutgoingTransitions
        } else {
            validIncomingTransitions
        }
        
        return validTransitions[currentState] ?: emptySet()
    }
}