package com.eddyslarez.siplibrary.data.services.audio

import android.app.Application
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * WebRTC Manager mejorado con interceptación de audio
 * Extiende AndroidWebRtcManager para añadir capacidades de interceptación
 * 
 * @author Eddys Larez
 */
class EnhancedWebRtcManager(
    application: Application
) : AndroidWebRtcManager(application) {
    
    companion object {
        private const val TAG = "EnhancedWebRtcManager"
    }
    
    private val audioInterceptor = AudioInterceptor(application)
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // Estado de interceptación
    private var isAudioInterceptionEnabled = false
    private var audioInterceptorListener: AudioInterceptor.AudioInterceptorListener? = null
    
    init {
        setupAudioInterceptor()
    }
    
    /**
     * Configura el interceptor de audio
     */
    private fun setupAudioInterceptor() {
        audioInterceptor.setListener(object : AudioInterceptor.AudioInterceptorListener {
            override fun onIncomingAudioReceived(audioData: ByteArray, timestamp: Long) {
                log.d(tag = TAG) { "Incoming audio intercepted: ${audioData.size} bytes" }
                audioInterceptorListener?.onIncomingAudioReceived(audioData, timestamp)
            }
            
            override fun onOutgoingAudioCaptured(audioData: ByteArray, timestamp: Long) {
                log.d(tag = TAG) { "Outgoing audio intercepted: ${audioData.size} bytes" }
                audioInterceptorListener?.onOutgoingAudioCaptured(audioData, timestamp)
            }
            
            override fun onAudioProcessed(incomingProcessed: ByteArray?, outgoingProcessed: ByteArray?) {
                audioInterceptorListener?.onAudioProcessed(incomingProcessed, outgoingProcessed)
            }
            
            override fun onRecordingStarted(incomingFile: java.io.File?, outgoingFile: java.io.File?) {
                log.d(tag = TAG) { "Audio recording started - Incoming: ${incomingFile?.name}, Outgoing: ${outgoingFile?.name}" }
                audioInterceptorListener?.onRecordingStarted(incomingFile, outgoingFile)
            }
            
            override fun onRecordingStopped() {
                log.d(tag = TAG) { "Audio recording stopped" }
                audioInterceptorListener?.onRecordingStopped()
            }
            
            override fun onError(error: String) {
                log.e(tag = TAG) { "Audio interceptor error: $error" }
                audioInterceptorListener?.onError(error)
            }
        })
    }
    
    // === OVERRIDE MÉTODOS BASE PARA INTERCEPTACIÓN ===
    
    override suspend fun createOffer(): String {
        val offer = super.createOffer()
        
        // Iniciar interceptación cuando se crea una oferta
        if (isAudioInterceptionEnabled) {
            startAudioInterception()
        }
        
        return offer
    }
    
    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String {
        val answer = super.createAnswer(accountInfo, offerSdp)
        
        // Iniciar interceptación cuando se crea una respuesta
        if (isAudioInterceptionEnabled) {
            startAudioInterception()
        }
        
        return answer
    }
    
    override fun setAudioEnabled(enabled: Boolean) {
        super.setAudioEnabled(enabled)
        
        if (enabled && isAudioInterceptionEnabled) {
            startAudioInterception()
        } else if (!enabled) {
            stopAudioInterception()
        }
    }
    
    override fun dispose() {
        stopAudioInterception()
        audioInterceptor.dispose()
        super.dispose()
    }
    
    // === MÉTODOS DE INTERCEPTACIÓN ===
    
    /**
     * Habilita la interceptación de audio
     */
    fun enableAudioInterception(enabled: Boolean) {
        isAudioInterceptionEnabled = enabled
        log.d(tag = TAG) { "Audio interception enabled: $enabled" }
        
        if (enabled && isInitialized()) {
            startAudioInterception()
        } else {
            stopAudioInterception()
        }
    }
    
    /**
     * Inicia la interceptación de audio
     */
    private fun startAudioInterception() {
        if (!audioInterceptor.isIntercepting()) {
            scope.launch {
                try {
                    audioInterceptor.startInterception()
                    log.d(tag = TAG) { "Audio interception started" }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Failed to start audio interception: ${e.message}" }
                }
            }
        }
    }
    
    /**
     * Detiene la interceptación de audio
     */
    private fun stopAudioInterception() {
        if (audioInterceptor.isIntercepting()) {
            audioInterceptor.stopInterception()
            log.d(tag = TAG) { "Audio interception stopped" }
        }
    }
    
    // === MÉTODOS PARA INYECCIÓN DE AUDIO ===
    
    /**
     * Inyecta audio personalizado para envío (reemplaza micrófono)
     */
    fun injectOutgoingAudio(audioData: ByteArray) {
        audioInterceptor.injectOutgoingAudio(audioData)
    }
    
    /**
     * Inyecta audio personalizado para reproducción (reemplaza audio recibido)
     */
    fun injectIncomingAudio(audioData: ByteArray) {
        audioInterceptor.injectIncomingAudio(audioData)
    }
    
    /**
     * Habilita/deshabilita el uso de audio personalizado para envío
     */
    fun setCustomOutgoingAudioEnabled(enabled: Boolean) {
        audioInterceptor.setCustomOutgoingAudioEnabled(enabled)
    }
    
    /**
     * Habilita/deshabilita el uso de audio personalizado para reproducción
     */
    fun setCustomIncomingAudioEnabled(enabled: Boolean) {
        audioInterceptor.setCustomIncomingAudioEnabled(enabled)
    }
    
    // === MÉTODOS PARA MANEJO DE ARCHIVOS ===
    
    /**
     * Carga audio desde archivo para inyección
     */
    fun loadAudioFromFile(file: java.io.File): ByteArray? {
        return audioInterceptor.loadAudioFromFile(file)
    }
    
    /**
     * Convierte archivo de audio a formato compatible
     */
    fun convertAudioFile(
        inputFile: java.io.File,
        outputFile: java.io.File,
        inputFormat: AudioFileFormat
    ): Boolean {
        return audioInterceptor.convertAudioFile(inputFile, outputFile, inputFormat)
    }
    
    /**
     * Obtiene los archivos de grabación actuales
     */
    fun getRecordingFiles(): Pair<java.io.File?, java.io.File?> {
        return audioInterceptor.getRecordingFiles()
    }
    
    // === CONFIGURACIÓN ===
    
    /**
     * Configura el listener para eventos de interceptación
     */
    fun setAudioInterceptorListener(listener: AudioInterceptor.AudioInterceptorListener?) {
        this.audioInterceptorListener = listener
    }
    
    /**
     * Habilita/deshabilita la grabación de audio
     */
    fun setAudioRecordingEnabled(enabled: Boolean) {
        audioInterceptor.setAudioRecordingEnabled(enabled)
    }
    
    /**
     * Habilita/deshabilita interceptación de audio entrante
     */
    fun setIncomingInterceptionEnabled(enabled: Boolean) {
        audioInterceptor.setIncomingInterceptionEnabled(enabled)
    }
    
    /**
     * Habilita/deshabilita interceptación de audio saliente
     */
    fun setOutgoingInterceptionEnabled(enabled: Boolean) {
        audioInterceptor.setOutgoingInterceptionEnabled(enabled)
    }
    
    // === INFORMACIÓN DE ESTADO ===
    
    /**
     * Verifica si la interceptación está activa
     */
    fun isAudioInterceptionActive(): Boolean {
        return audioInterceptor.isIntercepting()
    }
    
    /**
     * Obtiene el tamaño de las colas de audio
     */
    fun getAudioQueueSizes(): Map<String, Int> {
        return audioInterceptor.getQueueSizes()
    }
    
    /**
     * Obtiene información de diagnóstico del interceptor
     */
    fun getAudioInterceptorDiagnostics(): String {
        return buildString {
            appendLine("=== AUDIO INTERCEPTOR DIAGNOSTICS ===")
            appendLine("Interception enabled: $isAudioInterceptionEnabled")
            appendLine("Currently intercepting: ${audioInterceptor.isIntercepting()}")
            appendLine("Recording: ${audioInterceptor.isRecording()}")
            appendLine("Playing: ${audioInterceptor.isPlaying()}")
            
            val queueSizes = audioInterceptor.getQueueSizes()
            appendLine("Queue sizes:")
            queueSizes.forEach { (name, size) ->
                appendLine("  $name: $size")
            }
            
            val (incomingFile, outgoingFile) = audioInterceptor.getRecordingFiles()
            appendLine("Recording files:")
            appendLine("  Incoming: ${incomingFile?.name ?: "None"}")
            appendLine("  Outgoing: ${outgoingFile?.name ?: "None"}")
        }
    }
}