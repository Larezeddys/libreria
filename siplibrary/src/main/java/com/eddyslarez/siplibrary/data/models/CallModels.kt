package com.eddyslarez.siplibrary.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.datetime.Clock

/**
 * Modelos de datos para llamadas
 * 
 * @author Eddys Larez
 */


@Parcelize
enum class CallDirections : Parcelable {
    INCOMING,
    OUTGOING
}

@Parcelize
enum class CallTypes : Parcelable {
    SUCCESS,
    MISSED,
    DECLINED,
    ABORTED
}

@Parcelize
enum class RegistrationState : Parcelable {
    PROGRESS,
    OK,
    CLEARED,
    NONE,
    IN_PROGRESS,
    FAILED
}

@Parcelize
data class CallData(
    var callId: String = "",
    val from: String = "",
    val to: String = "",
    val direction: CallDirections = CallDirections.OUTGOING,
    val startTime: Long = Clock.System.now().toEpochMilliseconds(),
    var toTag: String? = null,
    var fromTag: String? = null,
    var remoteContactUri: String? = null,
    var remoteContactParams: Map<String, String> = emptyMap(),
    val remoteDisplayName: String = "",
    var inviteFromTag: String = "",
    var inviteToTag: String = "",
    var remoteSdp: String = "",
    var localSdp: String = "",
    var inviteViaBranch: String = "",
    var via: String = "",
    var originalInviteMessage: String = "",
    var originalCallInviteMessage: String = "",
    var isOnHold: Boolean? = null,
    var lastCSeqValue: Int = 0,
    var sipName: String = ""
) : Parcelable {

    fun storeInviteMessage(message: String) {
        originalInviteMessage = message
    }

    fun getRemoteParty(): String {
        return when (direction) {
            CallDirections.OUTGOING -> to
            CallDirections.INCOMING -> from
        }
    }

    fun getLocalParty(): String {
        return when (direction) {
            CallDirections.OUTGOING -> from
            CallDirections.INCOMING -> to
        }
    }

    override fun toString(): String {
        return "CallData(id=$callId, $from→$to, dir=$direction, started=$startTime, " +
                "fromTag=$fromTag, toTag=$toTag)"
    }
}

@Parcelize
data class CallLog(
    val id: String,
    val direction: CallDirections,
    val to: String,
    val formattedTo: String,
    val from: String,
    val formattedFrom: String,
    val contact: String?,
    val formattedStartDate: String,
    val duration: Int, // in seconds
    val callType: CallTypes,
    val localAddress: String
) : Parcelable

data class DtmfRequest(
    val digit: Char,
    val duration: Int = 160,
    val useInfo: Boolean = true
)

data class DtmfQueueStatus(
    val queueSize: Int,
    val isProcessing: Boolean,
    val pendingDigits: String
)

enum class AppLifecycleEvent {
    EnterBackground,
    FinishedLaunching,
    EnterForeground,
    WillTerminate,
    ProtectedDataAvailable,
    ProtectedDataWillBecomeUnavailable
}

interface AppLifecycleListener {
    fun onEvent(event: AppLifecycleEvent)
}