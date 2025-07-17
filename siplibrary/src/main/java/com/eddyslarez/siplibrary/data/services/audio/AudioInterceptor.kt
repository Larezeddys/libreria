package com.eddyslarez.siplibrary.data.services.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Interceptor de audio para WebRTC que permite:
 * - Interceptar audio entrante y saliente
 * - Inyectar audio personalizado
 * - Convertir formatos de audio
 * - Guardar audio original
 * - Manejar codec PCMA/8000
 * 
 * @author Eddys Larez
 */
class AudioInterceptor(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioInterceptor"
        
        // Configuración para PCMA/8000
        const val SAMPLE_RATE = 8000
        const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE_MS = 20 // 20ms frames típicos para VoIP
        const val FRAME_SIZE_BYTES = (SAMPLE_RATE * FRAME_SIZE_MS / 1000) * 2 // 16-bit = 2 bytes
        
        // Configuración de buffers
        const val BUFFER_SIZE_FACTOR = 4
        const val MAX_QUEUE_SIZE = 100
    }
    
    // Estados
    private var isIntercepting = false
    private var isRecording = false
    private var isPlaying = false
    
    // Coroutines
    private val interceptorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    private var processingJob: Job? = null
    
    // Audio components
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    // Buffers y colas
    private val incomingAudioQueue = ConcurrentLinkedQueue<ByteArray>()
    private val outgoingAudioQueue = ConcurrentLinkedQueue<ByteArray>()
    private val customIncomingQueue = ConcurrentLinkedQueue<ByteArray>()
    private val customOutgoingQueue = ConcurrentLinkedQueue<ByteArray>()
    
    // Archivos de grabación
    private var incomingAudioFile: File? = null
    private var outgoingAudioFile: File? = null
    private var incomingFileStream: FileOutputStream? = null
    private var outgoingFileStream: FileOutputStream? = null
    
    // Callbacks
    private var audioInterceptorListener: AudioInterceptorListener? = null
    
    // Configuración
    private var enableIncomingInterception = true
    private var enableOutgoingInterception = true
    private var enableAudioRecording = true
    private var customIncomingAudioEnabled = false
    private var customOutgoingAudioEnabled = false
    
    interface AudioInterceptorListener {
        fun onIncomingAudioReceived(audioData: ByteArray, timestamp: Long)
        fun onOutgoingAudioCaptured(audioData: ByteArray, timestamp: Long)
        fun onAudioProcessed(incomingProcessed: ByteArray?, outgoingProcessed: ByteArray?)
        fun onRecordingStarted(incomingFile: File?, outgoingFile: File?)
        fun onRecordingStopped()
        fun onError(error: String)
    }
    
    /**
     * Inicia la interceptación de audio
     */
    fun startInterception() {
        if (isIntercepting) {
            log.w(tag = TAG) { "Audio interception already started" }
            return
        }
        
        try {
            log.d(tag = TAG) { "Starting audio interception" }
            
            setupAudioComponents()
            setupRecordingFiles()
            
            isIntercepting = true
            
            // Iniciar jobs de procesamiento
            startRecordingJob()
            startPlaybackJob()
            startProcessingJob()
            
            audioInterceptorListener?.onRecordingStarted(incomingAudioFile, outgoingAudioFile)
            
            log.d(tag = TAG) { "Audio interception started successfully" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error starting audio interception: ${e.message}" }
            audioInterceptorListener?.onError("Failed to start audio interception: ${e.message}")
            stopInterception()
        }
    }
    
    /**
     * Detiene la interceptación de audio
     */
    fun stopInterception() {
        if (!isIntercepting) return
        
        log.d(tag = TAG) { "Stopping audio interception" }
        
        isIntercepting = false
        isRecording = false
        isPlaying = false
        
        // Cancelar jobs
        recordingJob?.cancel()
        playbackJob?.cancel()
        processingJob?.cancel()
        
        // Limpiar componentes de audio
        cleanupAudioComponents()
        
        // Cerrar archivos de grabación
        closeRecordingFiles()
        
        // Limpiar colas
        clearQueues()
        
        audioInterceptorListener?.onRecordingStopped()
        
        log.d(tag = TAG) { "Audio interception stopped" }
    }
    
    /**
     * Configura los componentes de audio
     */
    private fun setupAudioComponents() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG_IN,
            AUDIO_FORMAT
        ) * BUFFER_SIZE_FACTOR
        
        // Configurar AudioRecord para captura
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_CONFIG_IN,
            AUDIO_FORMAT,
            bufferSize
        )
        
        // Configurar AudioTrack para reproducción
        val playbackBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG_OUT,
            AUDIO_FORMAT
        ) * BUFFER_SIZE_FACTOR
        
        audioTrack = AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            SAMPLE_RATE,
            CHANNEL_CONFIG_OUT,
            AUDIO_FORMAT,
            playbackBufferSize,
            AudioTrack.MODE_STREAM
        )
        
        log.d(tag = TAG) { "Audio components configured - Buffer: $bufferSize, Playback: $playbackBufferSize" }
    }
    
    /**
     * Configura los archivos de grabación
     */
    private fun setupRecordingFiles() {
        if (!enableAudioRecording) return
        
        val recordingsDir = File(context.filesDir, "sip_recordings")
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }
        
        val timestamp = System.currentTimeMillis()
        
        incomingAudioFile = File(recordingsDir, "incoming_${timestamp}.pcm")
        outgoingAudioFile = File(recordingsDir, "outgoing_${timestamp}.pcm")
        
        incomingFileStream = FileOutputStream(incomingAudioFile)
        outgoingFileStream = FileOutputStream(outgoingAudioFile)
        
        log.d(tag = TAG) { "Recording files created: ${incomingAudioFile?.name}, ${outgoingAudioFile?.name}" }
    }
    
    /**
     * Inicia el job de grabación (captura de audio saliente)
     */
    private fun startRecordingJob() {
        recordingJob = interceptorScope.launch {
            val buffer = ByteArray(FRAME_SIZE_BYTES)
            
            try {
                audioRecord?.startRecording()
                isRecording = true
                
                log.d(tag = TAG) { "Recording job started" }
                
                while (isRecording && isActive) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    
                    if (bytesRead > 0) {
                        val audioData = buffer.copyOf(bytesRead)
                        val timestamp = System.currentTimeMillis()
                        
                        // Procesar audio saliente
                        processOutgoingAudio(audioData, timestamp)
                    }
                    
                    delay(1) // Pequeña pausa para evitar uso excesivo de CPU
                }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in recording job: ${e.message}" }
                audioInterceptorListener?.onError("Recording error: ${e.message}")
            } finally {
                audioRecord?.stop()
                isRecording = false
                log.d(tag = TAG) { "Recording job stopped" }
            }
        }
    }
    
    /**
     * Inicia el job de reproducción (audio entrante)
     */
    private fun startPlaybackJob() {
        playbackJob = interceptorScope.launch {
            try {
                audioTrack?.play()
                isPlaying = true
                
                log.d(tag = TAG) { "Playback job started" }
                
                while (isPlaying && isActive) {
                    // Procesar audio entrante desde la cola
                    val audioData = if (customIncomingAudioEnabled && customIncomingQueue.isNotEmpty()) {
                        customIncomingQueue.poll()
                    } else {
                        incomingAudioQueue.poll()
                    }
                    
                    audioData?.let { data ->
                        val processedData = processIncomingAudio(data, System.currentTimeMillis())
                        
                        // Reproducir audio procesado
                        audioTrack?.write(processedData, 0, processedData.size)
                    }
                    
                    delay(FRAME_SIZE_MS.toLong()) // Mantener timing de frames
                }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in playback job: ${e.message}" }
                audioInterceptorListener?.onError("Playback error: ${e.message}")
            } finally {
                audioTrack?.stop()
                isPlaying = false
                log.d(tag = TAG) { "Playback job stopped" }
            }
        }
    }
    
    /**
     * Inicia el job de procesamiento general
     */
    private fun startProcessingJob() {
        processingJob = interceptorScope.launch {
            while (isIntercepting && isActive) {
                try {
                    // Procesar colas y mantener sincronización
                    maintainQueueSizes()
                    
                    delay(100) // Verificar cada 100ms
                    
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in processing job: ${e.message}" }
                }
            }
        }
    }
    
    /**
     * Procesa audio saliente (del micrófono o personalizado)
     */
    private fun processOutgoingAudio(audioData: ByteArray, timestamp: Long) {
        try {
            // Usar audio personalizado si está habilitado
            val finalAudioData = if (customOutgoingAudioEnabled && customOutgoingQueue.isNotEmpty()) {
                customOutgoingQueue.poll() ?: audioData
            } else {
                audioData
            }
            
            // Convertir a formato PCMA si es necesario
            val pcmaData = convertToPCMA(finalAudioData)
            
            // Guardar audio original
            if (enableAudioRecording) {
                outgoingFileStream?.write(audioData)
                outgoingFileStream?.flush()
            }
            
            // Añadir a cola para WebRTC
            if (outgoingAudioQueue.size < MAX_QUEUE_SIZE) {
                outgoingAudioQueue.offer(pcmaData)
            }
            
            // Notificar listener
            audioInterceptorListener?.onOutgoingAudioCaptured(audioData, timestamp)
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing outgoing audio: ${e.message}" }
        }
    }
    
    /**
     * Procesa audio entrante (de WebRTC)
     */
    private fun processIncomingAudio(audioData: ByteArray, timestamp: Long): ByteArray {
        return try {
            // Convertir desde PCMA a PCM
            val pcmData = convertFromPCMA(audioData)
            
            // Guardar audio original
            if (enableAudioRecording) {
                incomingFileStream?.write(audioData)
                incomingFileStream?.flush()
            }
            
            // Notificar listener
            audioInterceptorListener?.onIncomingAudioReceived(audioData, timestamp)
            
            pcmData
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing incoming audio: ${e.message}" }
            audioData
        }
    }
    
    /**
     * Convierte PCM 16-bit a PCMA (A-law)
     */
    private fun convertToPCMA(pcmData: ByteArray): ByteArray {
        val pcmaData = ByteArray(pcmData.size / 2) // PCMA es 8-bit, PCM es 16-bit
        
        for (i in pcmaData.indices) {
            val sampleIndex = i * 2
            if (sampleIndex + 1 < pcmData.size) {
                // Leer sample de 16-bit (little endian)
                val sample = ((pcmData[sampleIndex + 1].toInt() and 0xFF) shl 8) or 
                           (pcmData[sampleIndex].toInt() and 0xFF)
                
                // Convertir a A-law
                pcmaData[i] = linearToAlaw(sample.toShort()).toByte()
            }
        }
        
        return pcmaData
    }
    
    /**
     * Convierte PCMA (A-law) a PCM 16-bit
     */
    private fun convertFromPCMA(pcmaData: ByteArray): ByteArray {
        val pcmData = ByteArray(pcmaData.size * 2) // PCM es 16-bit, PCMA es 8-bit
        
        for (i in pcmaData.indices) {
            val alawSample = pcmaData[i].toInt() and 0xFF
            val pcmSample = alawToLinear(alawSample.toByte())
            
            val pcmIndex = i * 2
            // Escribir sample de 16-bit (little endian)
            pcmData[pcmIndex] = (pcmSample.toInt() and 0xFF).toByte()
            pcmData[pcmIndex + 1] = ((pcmSample.toInt() shr 8) and 0xFF).toByte()
        }
        
        return pcmData
    }
    
    /**
     * Convierte linear PCM a A-law
     */
    private fun linearToAlaw(pcm: Short): Short {
        var mask = 0x55
        var seg = 0
        var aval: Int
        
        var pcmVal = pcm.toInt()
        if (pcmVal >= 0) {
            mask = mask or 0x80
        } else {
            pcmVal = -pcmVal - 8
        }
        
        if (pcmVal > 32635) pcmVal = 32635
        
        if (pcmVal >= 256) {
            seg = 1
            pcmVal = pcmVal shr 4
            if (pcmVal >= 256) {
                seg = 2
                pcmVal = pcmVal shr 4
                if (pcmVal >= 256) {
                    seg = 3
                    pcmVal = pcmVal shr 4
                    if (pcmVal >= 256) {
                        seg = 4
                        pcmVal = pcmVal shr 4
                        if (pcmVal >= 256) {
                            seg = 5
                            pcmVal = pcmVal shr 4
                            if (pcmVal >= 256) {
                                seg = 6
                                pcmVal = pcmVal shr 4
                                if (pcmVal >= 256) {
                                    seg = 7
                                    pcmVal = pcmVal shr 4
                                }
                            }
                        }
                    }
                }
            }
        }
        
        aval = (seg shl 4) or ((pcmVal shr 4) and 0x0F)
        return (aval xor mask).toShort()
    }
    
    /**
     * Convierte A-law a linear PCM
     */
    private fun alawToLinear(alaw: Byte): Short {
        val alawVal = alaw.toInt() and 0xFF
        var t = (alawVal xor 0x55) and 0xFF
        var seg = (t and 0x70) shr 4
        
        return if (seg != 0) {
            val f = (t and 0x0F) shl 4
            when (seg) {
                1 -> (f + 8).toShort()
                2 -> (f + 0x108).toShort()
                3 -> (f + 0x208).toShort()
                4 -> (f + 0x408).toShort()
                5 -> (f + 0x808).toShort()
                6 -> (f + 0x1008).toShort()
                7 -> (f + 0x2008).toShort()
                else -> (f + 8).toShort()
            }.let { result ->
                if ((alawVal and 0x80) != 0) result else (-result).toShort()
            }
        } else {
            val f = (t and 0x0F) shl 1
            val result = (f + 1).toShort()
            if ((alawVal and 0x80) != 0) result else (-result).toShort()
        }
    }
    
    /**
     * Mantiene el tamaño de las colas dentro de límites
     */
    private fun maintainQueueSizes() {
        while (incomingAudioQueue.size > MAX_QUEUE_SIZE) {
            incomingAudioQueue.poll()
        }
        while (outgoingAudioQueue.size > MAX_QUEUE_SIZE) {
            outgoingAudioQueue.poll()
        }
        while (customIncomingQueue.size > MAX_QUEUE_SIZE) {
            customIncomingQueue.poll()
        }
        while (customOutgoingQueue.size > MAX_QUEUE_SIZE) {
            customOutgoingQueue.poll()
        }
    }
    
    // === MÉTODOS PÚBLICOS PARA CONTROL ===
    
    /**
     * Inyecta audio personalizado para envío
     */
    fun injectOutgoingAudio(audioData: ByteArray) {
        if (customOutgoingQueue.size < MAX_QUEUE_SIZE) {
            customOutgoingQueue.offer(audioData)
        }
    }
    
    /**
     * Inyecta audio personalizado para reproducción
     */
    fun injectIncomingAudio(audioData: ByteArray) {
        if (customIncomingQueue.size < MAX_QUEUE_SIZE) {
            customIncomingQueue.offer(audioData)
        }
    }
    
    /**
     * Habilita/deshabilita audio personalizado saliente
     */
    fun setCustomOutgoingAudioEnabled(enabled: Boolean) {
        customOutgoingAudioEnabled = enabled
        log.d(tag = TAG) { "Custom outgoing audio enabled: $enabled" }
    }
    
    /**
     * Habilita/deshabilita audio personalizado entrante
     */
    fun setCustomIncomingAudioEnabled(enabled: Boolean) {
        customIncomingAudioEnabled = enabled
        log.d(tag = TAG) { "Custom incoming audio enabled: $enabled" }
    }
    
    /**
     * Obtiene audio procesado desde WebRTC (para envío)
     */
    fun getProcessedOutgoingAudio(): ByteArray? {
        return outgoingAudioQueue.poll()
    }
    
    /**
     * Añade audio recibido desde WebRTC (para procesamiento)
     */
    fun addIncomingAudioFromWebRTC(audioData: ByteArray) {
        if (incomingAudioQueue.size < MAX_QUEUE_SIZE) {
            incomingAudioQueue.offer(audioData)
        }
    }
    
    /**
     * Carga audio desde archivo para inyección
     */
    fun loadAudioFromFile(file: File): ByteArray? {
        return try {
            file.readBytes()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error loading audio from file: ${e.message}" }
            null
        }
    }
    
    /**
     * Convierte archivo de audio a formato compatible
     */
    fun convertAudioFile(inputFile: File, outputFile: File, inputFormat: AudioFileFormat): Boolean {
        return try {
            val inputData = inputFile.readBytes()
            val convertedData = when (inputFormat) {
                AudioFileFormat.WAV -> convertWavToPCM(inputData)
                AudioFileFormat.PCM_16BIT -> inputData
                AudioFileFormat.PCMA -> convertFromPCMA(inputData)
            }
            
            outputFile.writeBytes(convertedData)
            true
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error converting audio file: ${e.message}" }
            false
        }
    }
    
    /**
     * Convierte WAV a PCM raw
     */
    private fun convertWavToPCM(wavData: ByteArray): ByteArray {
        // Saltar header WAV (44 bytes típicamente)
        val headerSize = 44
        return if (wavData.size > headerSize) {
            wavData.copyOfRange(headerSize, wavData.size)
        } else {
            wavData
        }
    }
    
    // === CONFIGURACIÓN ===
    
    fun setListener(listener: AudioInterceptorListener?) {
        this.audioInterceptorListener = listener
    }
    
    fun setIncomingInterceptionEnabled(enabled: Boolean) {
        enableIncomingInterception = enabled
    }
    
    fun setOutgoingInterceptionEnabled(enabled: Boolean) {
        enableOutgoingInterception = enabled
    }
    
    fun setAudioRecordingEnabled(enabled: Boolean) {
        enableAudioRecording = enabled
    }
    
    // === LIMPIEZA ===
    
    private fun cleanupAudioComponents() {
        try {
            audioRecord?.release()
            audioTrack?.release()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error cleaning up audio components: ${e.message}" }
        }
        audioRecord = null
        audioTrack = null
    }
    
    private fun closeRecordingFiles() {
        try {
            incomingFileStream?.close()
            outgoingFileStream?.close()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error closing recording files: ${e.message}" }
        }
        incomingFileStream = null
        outgoingFileStream = null
    }
    
    private fun clearQueues() {
        incomingAudioQueue.clear()
        outgoingAudioQueue.clear()
        customIncomingQueue.clear()
        customOutgoingQueue.clear()
    }
    
    fun dispose() {
        stopInterception()
        interceptorScope.cancel()
    }
    
    // === INFORMACIÓN DE ESTADO ===
    
    fun isIntercepting(): Boolean = isIntercepting
    fun isRecording(): Boolean = isRecording
    fun isPlaying(): Boolean = isPlaying
    
    fun getQueueSizes(): Map<String, Int> {
        return mapOf(
            "incoming" to incomingAudioQueue.size,
            "outgoing" to outgoingAudioQueue.size,
            "customIncoming" to customIncomingQueue.size,
            "customOutgoing" to customOutgoingQueue.size
        )
    }
    
    fun getRecordingFiles(): Pair<File?, File?> {
        return Pair(incomingAudioFile, outgoingAudioFile)
    }
}

enum class AudioFileFormat {
    WAV,
    PCM_16BIT,
    PCMA
}