package com.eddyslarez.siplibrary.data.services.audio

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import com.eddyslarez.siplibrary.R
import com.eddyslarez.siplibrary.utils.log
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Use case for playing ringtones during calls
 *
 * @author Eddys Larez
 */
class AudioManager(private val application: Application) {
    private var outgoingRingtoneJob: Job? = null
    private var incomingRingtoneJob: Job? = null

    private val TAG = "AudioManager"
    private var incomingRingtone: MediaPlayer? = null
    private var outgoingRingtone: MediaPlayer? = null

    private var incomingRingtoneUri: Uri? = null
    private var outgoingRingtoneUri: Uri? = null

    init {
        // Valores por defecto (archivos locales en res/raw)
        incomingRingtoneUri = "android.resource://${application.packageName}/${R.raw.call}".toUri()
        outgoingRingtoneUri =
            "android.resource://${application.packageName}/${R.raw.ringback}".toUri()
    }

    fun setIncomingRingtone(uri: Uri) {
        incomingRingtoneUri = uri
        log.d(tag = TAG) { "Incoming ringtone URI set: $uri" }
    }

    fun setOutgoingRingtone(uri: Uri) {
        outgoingRingtoneUri = uri
        log.d(tag = TAG) { "Outgoing ringtone URI set: $uri" }
    }

    fun playRingtone() {
        stopRingtone() // Detenemos si ya hay uno sonando

        val uri = incomingRingtoneUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        incomingRingtoneJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val mediaPlayer = MediaPlayer().apply {
                        setDataSource(application, uri)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build()
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            setAudioStreamType(android.media.AudioManager.STREAM_RING)
                        }

                        prepare()
                        start()
                    }

                    incomingRingtone = mediaPlayer
                    log.d(tag = TAG) { "Incoming ringtone started" }

                    while (mediaPlayer.isPlaying && isActive) {
                        delay(100)
                    }

                    mediaPlayer.release()
                    incomingRingtone = null

                    delay(1000) // pausa de 1 segundo entre repeticiones

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error playing incoming ringtone: ${e.message}" }
                    break
                }
            }
        }
    }


    fun playOutgoingRingtone() {
        stopOutgoingRingtone() // Asegúrate de detener cualquier intento anterior

        val uri = outgoingRingtoneUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        outgoingRingtoneJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val mediaPlayer = MediaPlayer().apply {
                        setDataSource(application, uri)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build()
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            setAudioStreamType(android.media.AudioManager.STREAM_VOICE_CALL)
                        }

                        prepare()
                        start()
                    }

                    outgoingRingtone = mediaPlayer
                    log.d(tag = TAG) { "Outgoing ringtone started" }

                    // Espera hasta que termine el sonido
                    while (mediaPlayer.isPlaying && isActive) {
                        delay(100)
                    }

                    mediaPlayer.release()
                    outgoingRingtone = null

                    delay(1000) // Pausa entre repeticiones

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error playing outgoing ringtone: ${e.message}" }
                    break
                }
            }
        }
    }

    fun stopRingtone() {
        try {
            incomingRingtoneJob?.cancel()
            incomingRingtoneJob = null

            incomingRingtone?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            incomingRingtone = null
            log.d(tag = TAG) { "Incoming ringtone stopped" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping incoming ringtone: ${e.message}" }
        }
    }


    fun stopOutgoingRingtone() {
        try {
            outgoingRingtoneJob?.cancel()
            outgoingRingtoneJob = null

            outgoingRingtone?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            outgoingRingtone = null
            log.d(tag = TAG) { "Outgoing ringtone stopped" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping outgoing ringtone: ${e.message}" }
        }
    }


    fun stopAllRingtones() {
        stopRingtone()
        stopOutgoingRingtone()
    }
}
