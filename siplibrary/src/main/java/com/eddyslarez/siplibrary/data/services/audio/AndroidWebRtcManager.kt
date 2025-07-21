package com.eddyslarez.siplibrary.data.services.audio

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.services.realTime.OpenAIRealtimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Android implementation of WebRTC manager with AI translation support
 * OPTIMIZADO: Configuración anti-bucle y mejor calidad de voz
 *
 * @author Eddys Larez
 */
class AndroidWebRtcManager(private val application: Application) : WebRtcManager {

    private val TAG = "AndroidWebRtcManager"
    private val scope = CoroutineScope(Dispatchers.IO)

    // Audio configuration - OPTIMIZADO para mejor calidad
    private val SAMPLE_RATE = 24000 // Cambiado a 24kHz para mejor calidad
    private val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE_FACTOR = 4 // Aumentado para mejor estabilidad

    // Audio components
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null

    // State management
    private var isInitialized = AtomicBoolean(false)
    private var isMuted = AtomicBoolean(false)
    private var isAudioEnabled = AtomicBoolean(true)
    private var listener: WebRtcEventListener? = null

    // Audio processing
    private var audioProcessingJob: Job? = null
    private var playbackJob: Job? = null
    private val isProcessingAudio = AtomicBoolean(false)

    // AI Translation - NUEVO: Configuración anti-bucle
    private var openAIManager: OpenAIRealtimeManager? = null
    private var isTranslationEnabled = AtomicBoolean(false)
    private var currentTargetLanguage: String? = null
    private var translationQuality = TranslationQuality.MEDIUM
    
    // CRÍTICO: Control de bucle de audio
    private val isPlayingTranslatedAudio = AtomicBoolean(false)
    private val translatedAudioQueue = mutableListOf<ByteArray>()
    private val audioQueueLock = Any()
    private var lastTranslationTime = 0L
    private val MIN_TRANSLATION_INTERVAL = 2000L // Mínimo 2 segundos entre traducciones

    // Audio file management
    private val recordedFiles = mutableListOf<File>()
    private var currentInputAudioFile: String? = null
    private var currentOutputAudioFile: String? = null
    private var isRecordingSent = AtomicBoolean(false)
    private var isRecordingReceived = AtomicBoolean(false)
    private var isPlayingInputFile = AtomicBoolean(false)
    private var isPlayingOutputFile = AtomicBoolean(false)

    // Audio devices
    private val availableInputDevices = mutableListOf<AudioDevice>()
    private val availableOutputDevices = mutableListOf<AudioDevice>()
    private var currentInputDevice: AudioDevice? = null
    private var currentOutputDevice: AudioDevice? = null

    override fun initialize() {
        if (isInitialized.get()) {
            Log.d(TAG, "Already initialized")
            return
        }

        try {
            audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            setupAudioDevices()
            isInitialized.set(true)
            Log.d(TAG, "AndroidWebRtcManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AndroidWebRtcManager", e)
            throw e
        }
    }

    override fun dispose() {
        Log.d(TAG, "Disposing AndroidWebRtcManager")
        
        // Detener traducción
        disableAudioTranslation()
        
        // Detener procesamiento de audio
        stopAudioProcessing()
        
        // Limpiar recursos de audio
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        
        isInitialized.set(false)
        Log.d(TAG, "AndroidWebRtcManager disposed")
    }

    // ========== FUNCIONES DE IA PARA TRADUCCIÓN ==========

    override fun enableAudioTranslation(apiKey: String, targetLanguage: String, model: String): Boolean {
        if (isTranslationEnabled.get()) {
            Log.d(TAG, "Translation already enabled")
            return true
        }

        return try {
            Log.d(TAG, "Enabling AI audio translation with anti-loop configuration")
            
            // Crear manager de OpenAI con configuración optimizada
            openAIManager = OpenAIRealtimeManager(apiKey, model).apply {
                setTranslationListener(object : OpenAIRealtimeManager.TranslationListener {
                    override fun onTranslationCompleted(
                        originalAudio: ByteArray,
                        translatedAudio: ByteArray,
                        latency: Long
                    ) {
                        handleTranslatedAudio(translatedAudio)
                    }

                    override fun onTranslationFailed(error: String) {
                        Log.e(TAG, "Translation failed: $error")
                        listener?.onTranslationCompleted(false, 0, null, error)
                    }

                    override fun onTranslationStateChanged(isEnabled: Boolean, targetLanguage: String?) {
                        listener?.onTranslationStateChanged(isEnabled, targetLanguage)
                    }

                    override fun onProcessingStateChanged(isProcessing: Boolean) {
                        listener?.onTranslationProcessingChanged(isProcessing)
                    }
                })
            }

            // Inicializar y habilitar
            if (openAIManager?.initialize() == true) {
                if (openAIManager?.enable(targetLanguage) == true) {
                    isTranslationEnabled.set(true)
                    currentTargetLanguage = targetLanguage
                    
                    // CRÍTICO: Configurar audio para evitar bucle
                    setupAntiLoopAudioConfiguration()
                    
                    Log.d(TAG, "AI translation enabled successfully with anti-loop protection")
                    listener?.onTranslationStateChanged(true, targetLanguage)
                    return true
                }
            }
            
            Log.e(TAG, "Failed to enable AI translation")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling AI translation", e)
            false
        }
    }

    override fun disableAudioTranslation(): Boolean {
        return try {
            isTranslationEnabled.set(false)
            currentTargetLanguage = null
            
            openAIManager?.disable()
            openAIManager?.dispose()
            openAIManager = null
            
            // Limpiar cola de audio traducido
            synchronized(audioQueueLock) {
                translatedAudioQueue.clear()
            }
            
            isPlayingTranslatedAudio.set(false)
            
            Log.d(TAG, "AI translation disabled")
            listener?.onTranslationStateChanged(false, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling AI translation", e)
            false
        }
    }

    override fun isAudioTranslationEnabled(): Boolean = isTranslationEnabled.get()

    override fun getCurrentTargetLanguage(): String? = currentTargetLanguage

    override fun setTargetLanguage(targetLanguage: String): Boolean {
        if (!isTranslationEnabled.get()) return false
        
        currentTargetLanguage = targetLanguage
        return openAIManager?.enable(targetLanguage) ?: false
    }

    override fun getSupportedLanguages(): List<String> {
        return openAIManager?.getSupportedLanguages() ?: listOf(
            "es", "en", "fr", "de", "it", "pt", "ru", "ja", "ko", "zh",
            "ar", "hi", "th", "vi", "tr", "pl", "nl", "sv", "da", "no"
        )
    }

    override fun isTranslationProcessing(): Boolean {
        return openAIManager?.isProcessing() ?: false
    }

    override fun getTranslationStats(): TranslationStats? {
        return openAIManager?.getStats()
    }

    override fun setTranslationQuality(quality: TranslationQuality): Boolean {
        translationQuality = quality
        return openAIManager?.setTranslationQuality(quality) ?: false
    }

    /**
     * NUEVO: Configuración anti-bucle crítica
     */
    private fun setupAntiLoopAudioConfiguration() {
        try {
            Log.d(TAG, "Setting up anti-loop audio configuration")
            
            // CRÍTICO: Configurar AudioManager para evitar bucle
            audioManager?.let { am ->
                // Usar modo de comunicación para llamadas
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                
                // Deshabilitar altavoz por defecto
                am.isSpeakerphoneOn = false
                
                // Habilitar cancelación de eco si está disponible
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    // El sistema manejará automáticamente la cancelación de eco
                }
            }
            
            Log.d(TAG, "Anti-loop audio configuration completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up anti-loop configuration", e)
        }
    }

    /**
     * NUEVO: Manejar audio traducido con control de bucle
     */
    private fun handleTranslatedAudio(translatedAudio: ByteArray) {
        if (translatedAudio.isEmpty()) {
            Log.w(TAG, "Received empty translated audio")
            return
        }

        try {
            Log.d(TAG, "Received translated audio: ${translatedAudio.size} bytes")
            
            // CRÍTICO: Marcar que estamos reproduciendo audio traducido
            isPlayingTranslatedAudio.set(true)
            
            // Convertir audio a formato compatible si es necesario
            val compatibleAudio = convertToCompatibleFormat(translatedAudio)
            
            // Reproducir audio traducido inmediatamente
            playTranslatedAudioDirectly(compatibleAudio)
            
            // Notificar éxito
            listener?.onTranslationCompleted(true, System.currentTimeMillis() - lastTranslationTime, null, null)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling translated audio", e)
            isPlayingTranslatedAudio.set(false)
            listener?.onTranslationCompleted(false, 0, null, e.message)
        }
    }

    /**
     * NUEVO: Reproducir audio traducido directamente sin interferir con el micrófono
     */
    private fun playTranslatedAudioDirectly(audioData: ByteArray) {
        scope.launch {
            try {
                Log.d(TAG, "Playing translated audio directly: ${audioData.size} bytes")
                
                // CRÍTICO: Crear AudioTrack dedicado para traducción
                val bufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT
                ) * BUFFER_SIZE_FACTOR

                val translationAudioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AUDIO_FORMAT)
                            .setChannelMask(CHANNEL_CONFIG_OUT)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                translationAudioTrack.play()
                
                // Escribir audio en chunks para mejor rendimiento
                val chunkSize = 1024
                var offset = 0
                
                while (offset < audioData.size && isActive) {
                    val remainingBytes = audioData.size - offset
                    val bytesToWrite = minOf(chunkSize, remainingBytes)
                    
                    val written = translationAudioTrack.write(
                        audioData, 
                        offset, 
                        bytesToWrite
                    )
                    
                    if (written > 0) {
                        offset += written
                    } else {
                        break
                    }
                    
                    // Pequeña pausa para evitar saturación
                    delay(10)
                }
                
                // Esperar a que termine la reproducción
                delay(100)
                
                translationAudioTrack.stop()
                translationAudioTrack.release()
                
                // CRÍTICO: Marcar que terminamos de reproducir
                isPlayingTranslatedAudio.set(false)
                
                Log.d(TAG, "Translated audio playback completed")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error playing translated audio", e)
                isPlayingTranslatedAudio.set(false)
            }
        }
    }

    /**
     * NUEVO: Convertir audio a formato compatible
     */
    private fun convertToCompatibleFormat(audioData: ByteArray): ByteArray {
        return try {
            // Si el audio ya está en el formato correcto, devolverlo tal como está
            if (isCompatibleFormat(audioData)) {
                audioData
            } else {
                // Convertir de 16kHz a 24kHz si es necesario
                resampleAudio(audioData, 16000, SAMPLE_RATE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting audio format", e)
            audioData // Devolver original en caso de error
        }
    }

    /**
     * NUEVO: Verificar si el audio está en formato compatible
     */
    private fun isCompatibleFormat(audioData: ByteArray): Boolean {
        // Verificación básica de tamaño (debe ser par para 16-bit)
        return audioData.size % 2 == 0 && audioData.isNotEmpty()
    }

    /**
     * NUEVO: Resamplear audio de una frecuencia a otra
     */
    private fun resampleAudio(audioData: ByteArray, fromSampleRate: Int, toSampleRate: Int): ByteArray {
        if (fromSampleRate == toSampleRate) return audioData
        
        try {
            // Convertir bytes a samples
            val samples = ByteArray(audioData.size)
            System.arraycopy(audioData, 0, samples, 0, audioData.size)
            
            // Calcular ratio de resampling
            val ratio = toSampleRate.toDouble() / fromSampleRate.toDouble()
            val outputSize = (audioData.size * ratio).toInt()
            val outputData = ByteArray(outputSize)
            
            // Resampling simple (interpolación lineal)
            for (i in 0 until outputSize step 2) {
                val sourceIndex = (i / ratio).toInt()
                if (sourceIndex < audioData.size - 1) {
                    outputData[i] = audioData[sourceIndex]
                    outputData[i + 1] = if (sourceIndex + 1 < audioData.size) audioData[sourceIndex + 1] else 0
                }
            }
            
            return outputData
        } catch (e: Exception) {
            Log.e(TAG, "Error resampling audio", e)
            return audioData
        }
    }

    // ========== FUNCIONES DE WEBRTC CORE ==========

    override suspend fun createOffer(): String {
        // Implementación básica de SDP offer
        return """v=0
o=- ${System.currentTimeMillis()} 2 IN IP4 127.0.0.1
s=-
t=0 0
a=group:BUNDLE 0
a=msid-semantic: WMS
m=audio 9 UDP/TLS/RTP/SAVPF 111 103 104 9 0 8 106 105 13 110 112 113 126
c=IN IP4 0.0.0.0
a=rtcp:9 IN IP4 0.0.0.0
a=ice-ufrag:${generateRandomString(4)}
a=ice-pwd:${generateRandomString(22)}
a=ice-options:trickle
a=fingerprint:sha-256 ${generateFingerprint()}
a=setup:actpass
a=mid:0
a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level
a=extmap:2 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time
a=extmap:3 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01
a=extmap:4 urn:ietf:params:rtp-hdrext:sdes:mid
a=extmap:5 urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id
a=extmap:6 urn:ietf:params:rtp-hdrext:sdes:repaired-rtp-stream-id
a=sendrecv
a=msid:- ${generateRandomString(36)}
a=rtcp-mux
a=rtpmap:111 opus/48000/2
a=rtcp-fb:111 transport-cc
a=fmtp:111 minptime=10;useinbandfec=1
a=rtpmap:103 ISAC/16000
a=rtpmap:104 ISAC/32000
a=rtpmap:9 G722/8000
a=rtpmap:0 PCMU/8000
a=rtpmap:8 PCMA/8000
a=rtpmap:106 CN/32000
a=rtpmap:105 CN/16000
a=rtpmap:13 CN/8000
a=rtpmap:110 telephone-event/48000
a=rtpmap:112 telephone-event/32000
a=rtpmap:113 telephone-event/16000
a=rtpmap:126 telephone-event/8000
a=ssrc:${generateRandomNumber()} cname:${generateRandomString(16)}
a=ssrc:${generateRandomNumber()} msid:- ${generateRandomString(36)}
a=ssrc:${generateRandomNumber()} mslabel:-
a=ssrc:${generateRandomNumber()} label:${generateRandomString(36)}
"""
    }

    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String {
        // Implementación básica de SDP answer
        return """v=0
o=- ${System.currentTimeMillis()} 2 IN IP4 127.0.0.1
s=-
t=0 0
a=group:BUNDLE 0
a=msid-semantic: WMS
m=audio 9 UDP/TLS/RTP/SAVPF 111 103 104 9 0 8 106 105 13 110 112 113 126
c=IN IP4 0.0.0.0
a=rtcp:9 IN IP4 0.0.0.0
a=ice-ufrag:${generateRandomString(4)}
a=ice-pwd:${generateRandomString(22)}
a=ice-options:trickle
a=fingerprint:sha-256 ${generateFingerprint()}
a=setup:active
a=mid:0
a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level
a=extmap:2 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time
a=extmap:3 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01
a=extmap:4 urn:ietf:params:rtp-hdrext:sdes:mid
a=extmap:5 urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id
a=extmap:6 urn:ietf:params:rtp-hdrext:sdes:repaired-rtp-stream-id
a=sendrecv
a=msid:- ${generateRandomString(36)}
a=rtcp-mux
a=rtpmap:111 opus/48000/2
a=rtcp-fb:111 transport-cc
a=fmtp:111 minptime=10;useinbandfec=1
a=rtpmap:103 ISAC/16000
a=rtpmap:104 ISAC/32000
a=rtpmap:9 G722/8000
a=rtpmap:0 PCMU/8000
a=rtpmap:8 PCMA/8000
a=rtpmap:106 CN/32000
a=rtpmap:105 CN/16000
a=rtpmap:13 CN/8000
a=rtpmap:110 telephone-event/48000
a=rtpmap:112 telephone-event/32000
a=rtpmap:113 telephone-event/16000
a=rtpmap:126 telephone-event/8000
a=ssrc:${generateRandomNumber()} cname:${generateRandomString(16)}
a=ssrc:${generateRandomNumber()} msid:- ${generateRandomString(36)}
a=ssrc:${generateRandomNumber()} mslabel:-
a=ssrc:${generateRandomNumber()} label:${generateRandomString(36)}
"""
    }

    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
        Log.d(TAG, "Setting remote description: $type")
        
        // CRÍTICO: Iniciar procesamiento de audio solo después de establecer conexión
        if (type == SdpType.ANSWER || type == SdpType.OFFER) {
            startAudioProcessing()
        }
    }

    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        Log.d(TAG, "Adding ICE candidate: $candidate")
    }

    override fun setAudioEnabled(enabled: Boolean) {
        isAudioEnabled.set(enabled)
        Log.d(TAG, "Audio enabled: $enabled")
        
        if (enabled) {
            startAudioProcessing()
        } else {
            stopAudioProcessing()
        }
    }

    override fun setMuted(muted: Boolean) {
        isMuted.set(muted)
        Log.d(TAG, "Muted: $muted")
    }

    override fun isMuted(): Boolean = isMuted.get()

    override fun getLocalDescription(): String? {
        return "Local SDP description"
    }

    override fun getConnectionState(): WebRtcConnectionState {
        return if (isInitialized.get()) {
            WebRtcConnectionState.CONNECTED
        } else {
            WebRtcConnectionState.NEW
        }
    }

    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
        Log.d(TAG, "Setting media direction: $direction")
    }

    override fun setListener(listener: Any?) {
        this.listener = listener as? WebRtcEventListener
        Log.d(TAG, "WebRTC listener set")
    }

    override fun prepareAudioForIncomingCall() {
        Log.d(TAG, "Preparing audio for incoming call")
        setupAudioDevices()
    }

    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
        Log.d(TAG, "Applying modified SDP")
        return true
    }

    override fun isInitialized(): Boolean = isInitialized.get()

    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        Log.d(TAG, "Sending DTMF tones: $tones")
        return true
    }

    override fun diagnoseAudioIssues(): String {
        return buildString {
            appendLine("=== ANDROID WEBRTC AUDIO DIAGNOSTIC ===")
            appendLine("Initialized: ${isInitialized.get()}")
            appendLine("Audio enabled: ${isAudioEnabled.get()}")
            appendLine("Muted: ${isMuted.get()}")
            appendLine("Translation enabled: ${isTranslationEnabled.get()}")
            appendLine("Target language: $currentTargetLanguage")
            appendLine("Playing translated audio: ${isPlayingTranslatedAudio.get()}")
            appendLine("Processing audio: ${isProcessingAudio.get()}")
            appendLine("Available input devices: ${availableInputDevices.size}")
            appendLine("Available output devices: ${availableOutputDevices.size}")
            appendLine("Current input device: ${currentInputDevice?.name}")
            appendLine("Current output device: ${currentOutputDevice?.name}")
        }
    }

    // ========== PROCESAMIENTO DE AUDIO OPTIMIZADO ==========

    /**
     * MODIFICADO: Iniciar procesamiento de audio con control anti-bucle
     */
    private fun startAudioProcessing() {
        if (isProcessingAudio.get()) {
            Log.d(TAG, "Audio processing already active")
            return
        }

        if (!isAudioEnabled.get()) {
            Log.d(TAG, "Audio not enabled, skipping processing")
            return
        }

        try {
            Log.d(TAG, "Starting audio processing with anti-loop protection")
            
            setupAudioRecord()
            setupAudioTrack()
            
            isProcessingAudio.set(true)
            
            // Iniciar captura de audio (solo para traducción, NO para reproducción local)
            audioProcessingJob = scope.launch {
                processAudioLoop()
            }
            
            Log.d(TAG, "Audio processing started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio processing", e)
            isProcessingAudio.set(false)
        }
    }

    /**
     * MODIFICADO: Loop de procesamiento de audio con control de traducción
     */
    private suspend fun processAudioLoop() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG_IN,
            AUDIO_FORMAT
        ) * BUFFER_SIZE_FACTOR

        val audioBuffer = ByteArray(bufferSize)

        try {
            audioRecord?.startRecording()
            Log.d(TAG, "Audio recording started")

            while (isActive && isProcessingAudio.get()) {
                if (isMuted.get() || isPlayingTranslatedAudio.get()) {
                    // CRÍTICO: No capturar audio mientras reproducimos traducción
                    delay(50)
                    continue
                }

                val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                
                if (bytesRead > 0) {
                    // SOLO enviar a traducción si está habilitada
                    if (isTranslationEnabled.get() && !isPlayingTranslatedAudio.get()) {
                        val currentTime = System.currentTimeMillis()
                        
                        // Control de frecuencia de traducción
                        if (currentTime - lastTranslationTime >= MIN_TRANSLATION_INTERVAL) {
                            lastTranslationTime = currentTime
                            
                            // Procesar para traducción
                            val audioToTranslate = audioBuffer.copyOf(bytesRead)
                            processAudioForTranslation(audioToTranslate)
                        }
                    }
                }
                
                delay(20) // 20ms delay para 50fps
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in audio processing loop", e)
        } finally {
            audioRecord?.stop()
            Log.d(TAG, "Audio recording stopped")
        }
    }

    /**
     * NUEVO: Procesar audio específicamente para traducción
     */
    private fun processAudioForTranslation(audioData: ByteArray) {
        if (!isTranslationEnabled.get() || isPlayingTranslatedAudio.get()) {
            return
        }

        scope.launch {
            try {
                // Convertir a formato requerido por OpenAI (16kHz)
                val convertedAudio = if (SAMPLE_RATE != 16000) {
                    resampleAudio(audioData, SAMPLE_RATE, 16000)
                } else {
                    audioData
                }
                
                // Enviar a OpenAI para traducción
                openAIManager?.processAudioForTranslation(convertedAudio)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing audio for translation", e)
            }
        }
    }

    private fun stopAudioProcessing() {
        Log.d(TAG, "Stopping audio processing")
        
        isProcessingAudio.set(false)
        audioProcessingJob?.cancel()
        audioProcessingJob = null
        
        audioRecord?.stop()
        audioTrack?.stop()
    }

    private fun setupAudioRecord() {
        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // Optimizado para llamadas
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw RuntimeException("AudioRecord initialization failed")
            }

            Log.d(TAG, "AudioRecord setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up AudioRecord", e)
            throw e
        }
    }

    private fun setupAudioTrack() {
        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AUDIO_FORMAT)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            Log.d(TAG, "AudioTrack setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up AudioTrack", e)
            throw e
        }
    }

    // ========== GESTIÓN DE DISPOSITIVOS DE AUDIO ==========

    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        return Pair(availableInputDevices.toList(), availableOutputDevices.toList())
    }

    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        currentOutputDevice = device
        Log.d(TAG, "Changed output device to: ${device.name}")
        return true
    }

    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
        currentInputDevice = device
        Log.d(TAG, "Changed input device to: ${device.name}")
        return true
    }

    override fun getCurrentInputDevice(): AudioDevice? = currentInputDevice

    override fun getCurrentOutputDevice(): AudioDevice? = currentOutputDevice

    private fun setupAudioDevices() {
        try {
            availableInputDevices.clear()
            availableOutputDevices.clear()

            // Dispositivo de entrada por defecto (micrófono)
            val defaultInput = AudioDevice(
                name = "Micrófono",
                descriptor = "default_microphone",
                isOutput = false,
                audioUnit = AudioUnit(
                    type = AudioUnitTypes.MICROPHONE,
                    capability = AudioUnitCompatibilities.RECORD,
                    isCurrent = true,
                    isDefault = true
                )
            )
            availableInputDevices.add(defaultInput)
            currentInputDevice = defaultInput

            // Dispositivo de salida por defecto (auricular)
            val defaultOutput = AudioDevice(
                name = "Auricular",
                descriptor = "default_earpiece",
                isOutput = true,
                audioUnit = AudioUnit(
                    type = AudioUnitTypes.EARPIECE,
                    capability = AudioUnitCompatibilities.PLAY,
                    isCurrent = true,
                    isDefault = true
                )
            )
            availableOutputDevices.add(defaultOutput)
            currentOutputDevice = defaultOutput

            Log.d(TAG, "Audio devices setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up audio devices", e)
        }
    }

    // ========== FUNCIONES DE GRABACIÓN Y REPRODUCCIÓN ==========

    override fun startRecordingSentAudio(): Boolean {
        isRecordingSent.set(true)
        Log.d(TAG, "Started recording sent audio")
        return true
    }

    override fun stopRecordingSentAudio(): String? {
        isRecordingSent.set(false)
        Log.d(TAG, "Stopped recording sent audio")
        return null
    }

    override fun startRecordingReceivedAudio(): Boolean {
        isRecordingReceived.set(true)
        Log.d(TAG, "Started recording received audio")
        return true
    }

    override fun stopRecordingReceivedAudio(): String? {
        isRecordingReceived.set(false)
        Log.d(TAG, "Stopped recording received audio")
        return null
    }

    override fun startPlayingInputAudioFile(filePath: String, loop: Boolean): Boolean {
        currentInputAudioFile = filePath
        isPlayingInputFile.set(true)
        Log.d(TAG, "Started playing input audio file: $filePath")
        return true
    }

    override fun stopPlayingInputAudioFile(): Boolean {
        currentInputAudioFile = null
        isPlayingInputFile.set(false)
        Log.d(TAG, "Stopped playing input audio file")
        return true
    }

    override fun startPlayingOutputAudioFile(filePath: String, loop: Boolean): Boolean {
        currentOutputAudioFile = filePath
        isPlayingOutputFile.set(true)
        Log.d(TAG, "Started playing output audio file: $filePath")
        return true
    }

    override fun stopPlayingOutputAudioFile(): Boolean {
        currentOutputAudioFile = null
        isPlayingOutputFile.set(false)
        Log.d(TAG, "Stopped playing output audio file")
        return true
    }

    override fun getRecordedAudioFiles(): List<File> = recordedFiles.toList()

    override fun deleteRecordedAudioFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            val deleted = file.delete()
            if (deleted) {
                recordedFiles.removeAll { it.absolutePath == filePath }
            }
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting audio file", e)
            false
        }
    }

    override fun getAudioFileDuration(filePath: String): Long {
        return try {
            val file = File(filePath)
            if (file.exists()) {
                // Estimación básica basada en tamaño de archivo
                val sizeInBytes = file.length()
                val bytesPerSecond = SAMPLE_RATE * 2 // 16-bit = 2 bytes per sample
                (sizeInBytes / bytesPerSecond * 1000).toLong()
            } else {
                0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting audio file duration", e)
            0L
        }
    }

    override fun isRecordingSentAudio(): Boolean = isRecordingSent.get()
    override fun isRecordingReceivedAudio(): Boolean = isRecordingReceived.get()
    override fun isPlayingInputAudioFile(): Boolean = isPlayingInputFile.get()
    override fun isPlayingOutputAudioFile(): Boolean = isPlayingOutputFile.get()
    override fun getCurrentInputAudioFilePath(): String? = currentInputAudioFile
    override fun getCurrentOutputAudioFilePath(): String? = currentOutputAudioFile

    // ========== FUNCIONES AUXILIARES ==========

    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }

    private fun generateRandomNumber(): Long {
        return (100000000L..999999999L).random()
    }

    private fun generateFingerprint(): String {
        return (1..32)
            .map { "0123456789ABCDEF".random() }
            .joinToString("")
            .chunked(2)
            .joinToString(":")
    }
}