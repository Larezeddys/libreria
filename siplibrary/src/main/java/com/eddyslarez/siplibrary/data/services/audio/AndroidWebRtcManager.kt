package com.eddyslarez.siplibrary.data.services.audio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioFormat
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.models.CallState
import com.eddyslarez.siplibrary.data.services.realTime.OpenAIRealtimeManager
import com.eddyslarez.siplibrary.utils.CallStateManager
import com.eddyslarez.siplibrary.utils.log
import com.shepeliev.webrtckmp.AudioStreamTrack
import com.shepeliev.webrtckmp.PeerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.shepeliev.webrtckmp.IceCandidate
import com.shepeliev.webrtckmp.IceServer
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnectionState
import com.shepeliev.webrtckmp.RtcConfiguration
import com.shepeliev.webrtckmp.SessionDescription
import com.shepeliev.webrtckmp.SessionDescriptionType
import com.shepeliev.webrtckmp.audioTracks
import com.shepeliev.webrtckmp.onConnectionStateChange
import com.shepeliev.webrtckmp.onIceCandidate
import com.shepeliev.webrtckmp.onTrack
import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.MediaDeviceInfo
import com.shepeliev.webrtckmp.MediaStreamTrackKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WAV file recorder for audio recording
 */
open class WavRecorder(
    private val filePath: String,
    private val sampleRate: Int = 16000,
    private val channelCount: Int = 1,
    private val bitRate: Int = 16
) {
    private val TAG = "WavRecorder"
    private var audioRecord: AudioRecord? = null
    private var isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private var fileOutputStream: FileOutputStream? = null
    private var dataSize = 0L

    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    /**
    +     * NUEVO: Método virtual para interceptar datos de audio
    +     */
    protected open fun onAudioDataReceived(audioData: ByteArray) {
        // Método base vacío - las subclases pueden sobrescribirlo
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(coroutineScope: CoroutineScope): Boolean {
        if (isRecording.get()) {
            log.w(TAG) { "Recording already in progress" }
            return false
        }

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                log.e(TAG) { "AudioRecord initialization failed" }
                return false
            }

            fileOutputStream = FileOutputStream(filePath)
            writeWavHeader()

            audioRecord?.startRecording()
            isRecording.set(true)

            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                recordAudio()
            }

            log.d(TAG) { "WAV recording started: $filePath" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error starting recording: ${e.message}" }
            cleanup()
            false
        }
    }

    fun stopRecording() {
        if (!isRecording.get()) return

        isRecording.set(false)
        recordingJob?.cancel()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // Update WAV header with final size
        updateWavHeader()

        fileOutputStream?.close()
        fileOutputStream = null

        log.d(TAG) { "WAV recording stopped: $filePath" }
    }

    private fun recordAudio() {
        val buffer = ByteArray(bufferSize)

        while (isRecording.get()) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (bytesRead > 0) {
                    // NUEVO: Llamar al método de interceptación antes de escribir
                    val audioData = buffer.copyOfRange(0, bytesRead)
                    onAudioDataReceived(audioData)
                    fileOutputStream?.write(buffer, 0, bytesRead)
                    dataSize += bytesRead
                }
            } catch (e: Exception) {
                log.e(TAG) { "Error recording audio: ${e.message}" }
                break
            }
        }
    }

    private fun writeWavHeader() {
        val header = ByteArray(44)
        val byteBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        byteBuffer.put("RIFF".toByteArray())
        byteBuffer.putInt(36) // Placeholder for file size
        byteBuffer.put("WAVE".toByteArray())

        // fmt chunk
        byteBuffer.put("fmt ".toByteArray())
        byteBuffer.putInt(16) // fmt chunk size
        byteBuffer.putShort(1) // Audio format (PCM)
        byteBuffer.putShort(channelCount.toShort())
        byteBuffer.putInt(sampleRate)
        byteBuffer.putInt(sampleRate * channelCount * bitRate / 8) // Byte rate
        byteBuffer.putShort((channelCount * bitRate / 8).toShort()) // Block align
        byteBuffer.putShort(bitRate.toShort()) // Bits per sample

        // data chunk
        byteBuffer.put("data".toByteArray())
        byteBuffer.putInt(0) // Placeholder for data size

        fileOutputStream?.write(header)
    }

    private fun updateWavHeader() {
        try {
            val file = File(filePath)
            val randomAccessFile = java.io.RandomAccessFile(file, "rw")

            // Update file size
            randomAccessFile.seek(4)
            randomAccessFile.writeInt(Integer.reverseBytes((dataSize + 36).toInt()))

            // Update data size
            randomAccessFile.seek(40)
            randomAccessFile.writeInt(Integer.reverseBytes(dataSize.toInt()))

            randomAccessFile.close()
        } catch (e: Exception) {
            log.e(TAG) { "Error updating WAV header: ${e.message}" }
        }
    }

    private fun cleanup() {
        isRecording.set(false)
        recordingJob?.cancel()
        audioRecord?.release()
        audioRecord = null
        fileOutputStream?.close()
        fileOutputStream = null
    }
}

/**
 * Audio file player for playing files instead of microphone
 */
class AudioFilePlayer(
    private val filePath: String,
    private val sampleRate: Int = 16000
) {
    private val TAG = "AudioFilePlayer"
    private var audioTrack: AudioTrack? = null
    private var isPlaying = AtomicBoolean(false)
    private var playingJob: Job? = null
    private var fileInputStream: FileInputStream? = null
    private var isLooping = false

    fun startPlaying(coroutineScope: CoroutineScope, loop: Boolean = false): Boolean {
        if (isPlaying.get()) {
            log.w(TAG) { "Already playing" }
            return false
        }

        return try {
            val file = File(filePath)
            if (!file.exists()) {
                log.e(TAG) { "Audio file not found: $filePath" }
                return false
            }

            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            fileInputStream = FileInputStream(file)
            skipWavHeader()

            audioTrack?.play()
            isPlaying.set(true)
            isLooping = loop

            playingJob = coroutineScope.launch(Dispatchers.IO) {
                playAudio()
            }

            log.d(TAG) { "Audio file playing started: $filePath" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error starting audio playback: ${e.message}" }
            cleanup()
            false
        }
    }

    fun stopPlaying() {
        if (!isPlaying.get()) return

        isPlaying.set(false)
        playingJob?.cancel()

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        fileInputStream?.close()
        fileInputStream = null

        log.d(TAG) { "Audio file playing stopped: $filePath" }
    }

    private fun playAudio() {
        val buffer = ByteArray(4096)

        do {
            try {
                fileInputStream?.let { inputStream ->
                    var bytesRead = inputStream.read(buffer)
                    while (isPlaying.get() && bytesRead > 0) {
                        audioTrack?.write(buffer, 0, bytesRead)
                        bytesRead = inputStream.read(buffer)
                    }

                    if (isLooping && isPlaying.get()) {
                        inputStream.close()
                        fileInputStream = FileInputStream(filePath)
                        skipWavHeader()
                    }
                }
            } catch (e: Exception) {
                log.e(TAG) { "Error playing audio: ${e.message}" }
                break
            }
        } while (isLooping && isPlaying.get())
    }


    private fun skipWavHeader() {
        try {
            // Skip standard WAV header (44 bytes)
            fileInputStream?.skip(44)
        } catch (e: Exception) {
            log.w(TAG) { "Error skipping WAV header: ${e.message}" }
        }
    }

    private fun cleanup() {
        isPlaying.set(false)
        playingJob?.cancel()
        audioTrack?.release()
        audioTrack = null
        fileInputStream?.close()
        fileInputStream = null
    }
}

/**
 * Audio file manager for handling recordings and playback
 */
class AudioFileManager(private val context: Context) {
    private val TAG = "AudioFileManager"

    private val audioDirectory: File by lazy {
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "CallRecordings").apply {
            if (!exists()) mkdirs()
        }
    }

    fun createRecordingPath(prefix: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(audioDirectory, "${prefix}_${timestamp}.wav").absolutePath
    }

    fun getAudioFiles(): List<File> {
        return audioDirectory.listFiles { file ->
            file.isFile && file.extension.equals("wav", ignoreCase = true)
        }?.toList() ?: emptyList()
    }

    fun deleteAudioFile(filePath: String): Boolean {
        return try {
            File(filePath).delete()
        } catch (e: Exception) {
            log.e(TAG) { "Error deleting audio file: ${e.message}" }
            false
        }
    }

    fun getAudioDuration(filePath: String): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            duration?.toLong() ?: 0L
        } catch (e: Exception) {
            log.e(TAG) { "Error getting audio duration: ${e.message}" }
            0L
        }
    }
}

/**
 * Enhanced Android implementation of WebRtcManager interface with comprehensive audio device support
 * FIXED: Bluetooth audio routing issues
 * ADDED: WAV recording and audio file playback capabilities
 *
 * @author Eddys Larez
 */
class AndroidWebRtcManager(private val application: Application) : WebRtcManager {
    private val TAG = "AndroidWebRtcManager"
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioStreamTrack? = null
    private var remoteAudioTrack: AudioStreamTrack? = null
    private var webRtcEventListener: WebRtcEventListener? = null
    private var isInitialized = false
    private var isLocalAudioReady = false
    private var context: Context = application.applicationContext

    // NUEVO: Variables para traducción de IA
    // NUEVO: Variables para traducción de IA - solo audio remoto
    private var openAIManager: OpenAIRealtimeManager? = null
    private var isTranslationEnabled = false
    private var currentTargetLanguage: String? = null
    private var translationQuality: WebRtcManager.TranslationQuality =
        WebRtcManager.TranslationQuality.MEDIUM
    // NUEVO: Control de audio remoto
    private var isRemoteAudioMuted = false
    private var originalRemoteAudioVolume = 1.0f
    // NUEVO: Grabador especial para traducción (usa el mismo sistema que ya tienes)
    private var translationAudioRecorder: WavRecorder? = null
    private var isRecordingForTranslation = false
    private var currentTranslationRecordingPath: String? = null
    private var translationProcessingJob: Job? = null

    // MODIFICADO: Solo capturar audio remoto para traducción
    // MODIFICADO: Buffer para audio remoto interceptado
    private var remoteAudioBuffer = mutableListOf<ByteArray>()
    private var remoteAudioProcessingJob: Job? = null
    private var lastTranslationSent = 0L
    private val translationIntervalMs = 1000L

    // Buffer para acumular audio antes de enviar a traducción
    private val translationAudioBuffer = mutableListOf<ByteArray>()

    // MODIFICADO: Solo capturar audio remoto para traducción
    private var remoteAudioProcessor: Job? = null
    private var isProcessingRemoteAudio = false
    private var lastRemoteAudioProcessed = 0L
    private val remoteAudioProcessingInterval = 1500L // Reducido para menor latencia

    // Enhanced audio management fields
    private var audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothManager: BluetoothManager? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var currentInputDevice: AudioDevice? = null
    private var currentOutputDevice: AudioDevice? = null
    private var translatedAudioPlayer: AudioFilePlayer? = null
    private var isPlayingTranslatedAudio = false

    // FIXED: Add Bluetooth SCO state tracking
    private var isBluetoothScoRequested = false
    private var bluetoothScoReceiver: BroadcastReceiver? = null

    // Audio state management
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedIsSpeakerPhoneOn = false
    private var savedIsMicrophoneMute = false
    private var audioFocusRequest: AudioFocusRequest? = null

    // Device monitoring
    private var deviceChangeListeners = mutableListOf<(List<AudioDevice>) -> Unit>()

    // NUEVO: Audio recording and playback components
    private var audioFileManager: AudioFileManager = AudioFileManager(context)
    private var sentAudioRecorder: WavRecorder? = null
    private var receivedAudioRecorder: WavRecorder? = null
    private var inputAudioPlayer: AudioFilePlayer? = null
    private var outputAudioPlayer: AudioFilePlayer? = null

    // NUEVO: Recording state
    private var isRecordingSentAudio = false
    private var isRecordingReceivedAudio = false
    private var isPlayingInputFile = false
    private var isPlayingOutputFile = false
    private var currentSentRecordingPath: String? = null
    private var currentReceivedRecordingPath: String? = null

    // NUEVO: Audio file paths for playback
    private var inputAudioFilePath: String? = null
    private var outputAudioFilePath: String? = null

    init {
        initializeBluetoothComponents()
        setupAudioDeviceMonitoring()
        setupBluetoothScoReceiver() // FIXED: Add Bluetooth SCO monitoring
    }

    /**
     * NUEVO: Start recording sent audio (microphone input)
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun startRecordingSentAudio(): Boolean {
        if (isRecordingSentAudio) {
            log.w(TAG) { "Already recording sent audio" }
            return false
        }

        return try {
            currentSentRecordingPath = audioFileManager.createRecordingPath("sent_audio")
            sentAudioRecorder = WavRecorder(currentSentRecordingPath!!)

            if (sentAudioRecorder?.startRecording(coroutineScope) == true) {
                isRecordingSentAudio = true
                log.d(TAG) { "Started recording sent audio: $currentSentRecordingPath" }
                true
            } else {
                sentAudioRecorder = null
                currentSentRecordingPath = null
                false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error starting sent audio recording: ${e.message}" }
            false
        }
    }
// ========== IMPLEMENTACIÓN DE FUNCIONES DE IA ==========

    /**
     * Enable AI audio translation for incoming audio
     */
    /**
     * MODIFICADO: Enable AI translation with complete remote audio muting
     */
//    override fun enableAudioTranslation(
//        apiKey: String,
//        targetLanguage: String,
//        model: String
//    ): Boolean {
//        log.d(TAG) { "Enabling AI audio translation with remote audio muting to $targetLanguage" }
//
//        return try {
//            openAIManager = OpenAIRealtimeManager(apiKey, model)
//
//            openAIManager?.setTranslationListener(object : OpenAIRealtimeManager.TranslationListener {
//                override fun onTranslationCompleted(
//                    originalAudio: ByteArray,
//                    translatedAudio: ByteArray,
//                    latency: Long
//                ) {
//                    log.d(TAG) { "Translation completed with latency: ${latency}ms, audio size: ${translatedAudio.size} bytes" }
//
//                    // Reproducir SOLO el audio traducido
//                    playTranslatedAudioOnly(translatedAudio)
//                    webRtcEventListener?.onTranslationCompleted(true, latency, null, null)
//                }
//
//                override fun onTranslationFailed(error: String) {
//                    log.e(TAG) { "Translation failed: $error" }
//                    webRtcEventListener?.onTranslationCompleted(false, 0, null, error)
//                }
//
//                override fun onTranslationStateChanged(isEnabled: Boolean, targetLanguage: String?) {
//                    webRtcEventListener?.onTranslationStateChanged(isEnabled, targetLanguage)
//                }
//
//                override fun onProcessingStateChanged(isProcessing: Boolean) {
//                    webRtcEventListener?.onTranslationProcessingChanged(isProcessing)
//                }
//            })
//
//            if (openAIManager?.initialize() == true) {
//                isTranslationEnabled = openAIManager?.enable(targetLanguage) == true
//                currentTargetLanguage = if (isTranslationEnabled) targetLanguage else null
//
//                if (isTranslationEnabled) {
//                    // CRÍTICO: Silenciar completamente el audio remoto
//                    muteRemoteAudioCompletely()
//
//                    // Iniciar procesamiento de audio remoto interceptado
//                    startRemoteAudioTranslationProcessing()
//                }
//
//                log.d(TAG) { "AI translation enabled with remote audio muted: $isTranslationEnabled" }
//                isTranslationEnabled
//            } else {
//                log.e(TAG) { "Failed to initialize OpenAI manager" }
//                false
//            }
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error enabling AI translation: ${e.message}" }
//            false
//        }
//    }

    override fun enableAudioTranslation(
        apiKey: String,
        targetLanguage: String,
        model: String
    ): Boolean {
        log.d(TAG) { "Enabling AI audio translation to $targetLanguage" }

        return try {
            // Limpiar estado anterior
            disableAudioTranslation()

            openAIManager = OpenAIRealtimeManager(apiKey, model)

            openAIManager?.setTranslationListener(object : OpenAIRealtimeManager.TranslationListener {
                override fun onTranslationCompleted(
                    originalAudio: ByteArray,
                    translatedAudio: ByteArray,
                    latency: Long
                ) {
                    log.d(TAG) { "Translation completed with latency: ${latency}ms, audio size: ${translatedAudio.size} bytes" }

                    // CORREGIDO: Reproducir SOLO el audio traducido
                    if (translatedAudio.isNotEmpty()) {
                        playTranslatedAudioOnly(translatedAudio)
                        webRtcEventListener?.onTranslationCompleted(true, latency, null, null)
                    } else {
                        log.w(TAG) { "Received empty translated audio" }
                        webRtcEventListener?.onTranslationCompleted(false, latency, null, "Empty translation")
                    }
                }

                override fun onTranslationFailed(error: String) {
                    log.e(TAG) { "Translation failed: $error" }
                    webRtcEventListener?.onTranslationCompleted(false, 0, null, error)
                }

                override fun onTranslationStateChanged(isEnabled: Boolean, targetLanguage: String?) {
                    webRtcEventListener?.onTranslationStateChanged(isEnabled, targetLanguage)
                }

                override fun onProcessingStateChanged(isProcessing: Boolean) {
                    webRtcEventListener?.onTranslationProcessingChanged(isProcessing)
                }
            })

            if (openAIManager?.initialize() == true) {
                isTranslationEnabled = openAIManager?.enable(targetLanguage) == true
                currentTargetLanguage = if (isTranslationEnabled) targetLanguage else null

                if (isTranslationEnabled) {
                    // CRÍTICO: Silenciar completamente el audio remoto
                    muteRemoteAudioCompletely()

                    log.d(TAG) { "AI translation enabled with remote audio muted: $isTranslationEnabled" }
                }

                isTranslationEnabled
            } else {
                log.e(TAG) { "Failed to initialize OpenAI manager" }
                false
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error enabling AI translation: ${e.message}" }
            false
        }
    }
    private fun playTranslatedAudioOnly(translatedAudio: ByteArray) {
        try {
            // Crear archivo temporal para el audio traducido
            val tempFile = File.createTempFile("translated_audio", ".wav", context.cacheDir)

            // Crear header WAV para el audio traducido
            val wavFile = createWavFile(translatedAudio, tempFile)

            // Detener cualquier reproducción anterior
            stopTranslatedAudioPlayback()

            // Reproducir el audio traducido usando AudioTrack directamente
            playTranslatedAudioDirectly(translatedAudio)

            log.d(TAG) { "Playing translated audio only: ${translatedAudio.size} bytes" }

            // Limpiar archivo temporal después de un tiempo
            coroutineScope.launch {
                delay(calculateAudioDuration(translatedAudio) + 1000)
                tempFile.delete()
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error playing translated audio only: ${e.message}" }
        }
    }
    /**
     * MODIFICADO: Disable AI translation and restore remote audio
     */
    override fun disableAudioTranslation(): Boolean {
        log.d(TAG) { "Disabling AI audio translation and restoring remote audio" }

        return try {
            isTranslationEnabled = false
            currentTargetLanguage = null

            // Detener procesamiento de audio
            stopRemoteAudioTranslationProcessing()

            // CRÍTICO: Restaurar audio remoto
            unmuteRemoteAudio()

            // Detener traducción
            openAIManager?.disable()
            openAIManager = null

            // Detener reproducción de audio traducido
            stopTranslatedAudioPlayback()

            log.d(TAG) { "AI translation disabled and remote audio restored" }
            true

        } catch (e: Exception) {
            log.e(TAG) { "Error disabling AI translation: ${e.message}" }
            false
        }
    }
    /**
     * CORREGIDO: Silenciar completamente el audio remoto sin usar volume
     */
    private fun muteRemoteAudioCompletely() {
        try {
            // Método principal: Deshabilitar el track de audio remoto
            remoteAudioTrack?.enabled = false
            isRemoteAudioMuted = true

            // Configurar AudioManager para minimizar volumen de llamada
            audioManager?.let { manager ->
                try {
                    originalRemoteAudioVolume = manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL).toFloat()
                    manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0)
                } catch (e: Exception) {
                    log.e(TAG) { "Error setting stream volume: ${e.message}" }
                }
            }

            log.d(TAG) { "Remote audio completely muted for translation" }

        } catch (e: Exception) {
            log.e(TAG) { "Error muting remote audio: ${e.message}" }
        }
    }


    /**
     * CORREGIDO: Restaurar audio remoto sin usar volume
     */
    private fun unmuteRemoteAudio() {
        try {
            // Restaurar track de audio remoto
            remoteAudioTrack?.enabled = true
            isRemoteAudioMuted = false

            // Restaurar volumen original
            audioManager?.let { manager ->
                try {
                    manager.setStreamVolume(
                        AudioManager.STREAM_VOICE_CALL,
                        originalRemoteAudioVolume.toInt(),
                        0
                    )
                } catch (e: Exception) {
                    log.e(TAG) { "Error restoring stream volume: ${e.message}" }
                }
            }

            log.d(TAG) { "Remote audio restored" }

        } catch (e: Exception) {
            log.e(TAG) { "Error restoring remote audio: ${e.message}" }
        }
    }
    /**
     * CORREGIDO: Iniciar procesamiento de audio remoto con validaciones mejoradas
     */
    private fun startRemoteAudioTranslationProcessing() {
        if (remoteAudioProcessingJob?.isActive == true) return

        remoteAudioProcessingJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive && isTranslationEnabled) {
                try {
                    delay(translationIntervalMs)

                    synchronized(remoteAudioBuffer) {
                        if (remoteAudioBuffer.isNotEmpty()) {
                            // Combinar todo el audio acumulado
                            val totalSize = remoteAudioBuffer.sumOf { it.size }

                            // CORREGIDO: Validar tamaño mínimo más estricto
                            val minAudioSize = (16000 * 2 * 0.5).toInt() // 500ms de audio PCM 16-bit 16kHz

                            if (totalSize < minAudioSize) {
                                log.d(TAG) { "Audio buffer too small: $totalSize bytes, need at least $minAudioSize (500ms)" }
                                // No limpiar el buffer, mantener para próxima iteración
                                return@synchronized
                            }

                            val combinedAudio = ByteArray(totalSize)
                            var offset = 0

                            remoteAudioBuffer.forEach { chunk ->
                                System.arraycopy(chunk, 0, combinedAudio, offset, chunk.size)
                                offset += chunk.size
                            }

                            // Limpiar buffer después de procesar
                            remoteAudioBuffer.clear()

                            log.d(TAG) { "Sending ${combinedAudio.size} bytes of remote audio to translation" }

                            // CORREGIDO: Verificar que OpenAI no esté procesando antes de enviar
                            if (openAIManager?.isProcessing() != true) {
                                openAIManager?.processAudioForTranslation(combinedAudio)
                                lastTranslationSent = System.currentTimeMillis()
                            } else {
                                log.d(TAG) { "OpenAI is busy, queuing audio for next cycle" }
                                // Devolver el audio al buffer para próximo ciclo
                                remoteAudioBuffer.add(0, combinedAudio)
                            }
                        }
                    }

                } catch (e: Exception) {
                    log.e(TAG) { "Error in remote audio translation processing: ${e.message}" }
                    delay(2000) // Esperar más tiempo en caso de error
                }
            }
        }
    }

//    /**
//     * NUEVO: Iniciar procesamiento solo de audio remoto
//     */
//    private fun startRemoteAudioTranslationProcessing() {
//        if (remoteAudioProcessingJob?.isActive == true) return
//
//        remoteAudioProcessingJob = coroutineScope.launch(Dispatchers.IO) {
//            while (isActive && isTranslationEnabled) {
//                try {
//                    delay(translationIntervalMs)
//                    synchronized(remoteAudioBuffer) {
//                        if (remoteAudioBuffer.isNotEmpty()) {
//                            // Combinar todo el audio acumulado
//                            val totalSize = remoteAudioBuffer.sumOf { it.size }
//                            val combinedAudio = ByteArray(totalSize)
//                            var offset = 0
//
//                            remoteAudioBuffer.forEach { chunk ->
//                                System.arraycopy(chunk, 0, combinedAudio, offset, chunk.size)
//                                offset += chunk.size
//                            }
//
//                            remoteAudioBuffer.clear()
//                            // NUEVO: Verificar que tenemos suficiente audio (mínimo 100ms)
//                            val minAudioSize =
//                                (16000 * 2 * 0.1).toInt() // 100ms de audio PCM 16-bit 16kHz
//                            if (totalSize < minAudioSize) {
//                                log.d(TAG) { "Audio buffer too small: $totalSize bytes, need at least $minAudioSize" }
//                                return@synchronized
//                            }
//                            if (combinedAudio.isNotEmpty()) {
//                                log.d(TAG) { "Sending ${combinedAudio.size} bytes of remote audio to translation" }
//                                openAIManager?.processAudioForTranslation(combinedAudio)
//                                lastTranslationSent = System.currentTimeMillis()
//                            }
//                        }
//                    }
//
//                } catch (e: Exception) {
//                    log.e(TAG) { "Error in remote audio translation processing: ${e.message}" }
//
//
//                    delay(1000)
//                }
//            }
//        }
//    }

    /**
     * NUEVO: Detener procesamiento de audio remoto
     */
    private fun stopRemoteAudioTranslationProcessing() {
        remoteAudioProcessingJob?.cancel()
        remoteAudioProcessingJob = null

        synchronized(remoteAudioBuffer) {
            remoteAudioBuffer.clear()
        }

        log.d(TAG) { "Stopped remote audio translation processing" }
    }

//    private fun stopRemoteAudioTranslationProcessing() {
//        remoteAudioProcessingJob?.cancel()
//        remoteAudioProcessingJob = null
//
//        synchronized(remoteAudioBuffer) {
//            remoteAudioBuffer.clear()
//        }
//    }
    /**
     * CORREGIDO: Interceptar audio remoto con validación de datos
     */
    private fun interceptRemoteAudioForTranslation(audioData: ByteArray) {
        if (!isTranslationEnabled) return

        // CORREGIDO: Validar que los datos de audio no estén vacíos
        if (audioData.isEmpty()) {
            log.w(TAG) { "Received empty audio data for translation" }
            return
        }

        try {
            synchronized(remoteAudioBuffer) {
                // CORREGIDO: Limitar el tamaño total del buffer para evitar acumulación excesiva
                val maxBufferSize = 16000 * 2 * 10 // 10 segundos máximo
                val currentBufferSize = remoteAudioBuffer.sumOf { it.size }

                if (currentBufferSize + audioData.size > maxBufferSize) {
                    log.w(TAG) { "Audio buffer full, removing oldest chunks" }
                    // Remover chunks más antiguos
                    while (remoteAudioBuffer.isNotEmpty() &&
                        remoteAudioBuffer.sumOf { it.size } + audioData.size > maxBufferSize) {
                        remoteAudioBuffer.removeAt(0)
                    }
                }

                remoteAudioBuffer.add(audioData.copyOf())
            }

            log.d(TAG) { "Intercepted ${audioData.size} bytes of remote audio for translation" }
        } catch (e: Exception) {
            log.e(TAG) { "Error intercepting remote audio: ${e.message}" }
        }
    }
//    /**
//     * NUEVO: Combinar buffers de audio
//     */
//    private fun interceptRemoteAudioForTranslation(audioData: ByteArray) {
//        if (!isTranslationEnabled) return
//
//        try {
//            synchronized(remoteAudioBuffer) {
//                remoteAudioBuffer.add(audioData.copyOf())
//            }
//
//            log.d(TAG) { "Intercepted ${audioData.size} bytes of remote audio for translation" }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error intercepting remote audio: ${e.message}" }
//        }
//    }

    /**
     * NUEVO: Reemplazar audio remoto con traducción
     */
    private fun replaceRemoteAudioWithTranslation(translatedAudio: ByteArray) {
        try {
            // CRÍTICO: Detener temporalmente el track de audio remoto
            remoteAudioTrack?.enabled = false

            // Crear archivo temporal para audio traducido
            val tempFile = File.createTempFile("translated_", ".wav", context.cacheDir)
            createWavFileFromPcm(translatedAudio, tempFile)

            // Reproducir audio traducido en lugar del remoto
//            playTranslatedAudioDirectly(tempFile.absolutePath)

            // Re-habilitar audio remoto después de la traducción
            coroutineScope.launch {
                delay(calculateAudioDuration(translatedAudio))
                remoteAudioTrack?.enabled = true
                tempFile.delete()
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error replacing remote audio with translation: ${e.message}" }
            // Re-habilitar audio remoto en caso de error
            remoteAudioTrack?.enabled = true
        }
    }
    private fun playTranslatedAudioDirectly(translatedAudio: ByteArray) {
        try {
            stopTranslatedAudioPlayback()

            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val sampleRate = 16000
                    val channelConfig = AudioFormat.CHANNEL_OUT_MONO
                    val audioFormat = AudioFormat.ENCODING_PCM_16BIT

                    val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                    val audioTrack = AudioTrack(
                        AudioManager.STREAM_VOICE_CALL,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        maxOf(bufferSize, translatedAudio.size),
                        AudioTrack.MODE_STREAM
                    )

                    audioTrack.play()

                    // Escribir audio en chunks
                    val chunkSize = 1024
                    var offset = 0

                    while (offset < translatedAudio.size && isTranslationEnabled) {
                        val remainingBytes = translatedAudio.size - offset
                        val bytesToWrite = minOf(chunkSize, remainingBytes)

                        val written = audioTrack.write(translatedAudio, offset, bytesToWrite)
                        if (written > 0) {
                            offset += written
                        } else {
                            delay(10) // Esperar un poco si no se pudo escribir
                        }
                    }

                    // Esperar a que termine la reproducción
                    delay(calculateAudioDuration(translatedAudio))

                    audioTrack.stop()
                    audioTrack.release()

                    isPlayingTranslatedAudio = false

                    log.d(TAG) { "Finished playing translated audio directly" }

                } catch (e: Exception) {
                    log.e(TAG) { "Error in direct audio playback: ${e.message}" }
                }
            }

            isPlayingTranslatedAudio = true

        } catch (e: Exception) {
            log.e(TAG) { "Error starting direct audio playback: ${e.message}" }
        }
    }

//    /**
//     * NUEVO: Reproducir audio traducido directamente
//     */
//    private fun playTranslatedAudioDirectly(audioPath: String) {
//        try {
//            stopTranslatedAudioPlayback()
//
//            translatedAudioPlayer = AudioFilePlayer(audioPath, 16000)
//            isPlayingTranslatedAudio =
//                translatedAudioPlayer?.startPlaying(coroutineScope, false) == true
//
//            if (isPlayingTranslatedAudio) {
//                log.d(TAG) { "Playing translated audio directly" }
//            }
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error playing translated audio: ${e.message}" }
//        }
//    }

    /**
     * NUEVO: Detener reproducción de audio traducido
     */
//    private fun stopTranslatedAudioPlayback() {
//        if (isPlayingTranslatedAudio) {
//            translatedAudioPlayer?.stopPlaying()
//            translatedAudioPlayer = null
//            isPlayingTranslatedAudio = false
//        }
//    }
    private fun stopTranslatedAudioPlayback() {
        if (isPlayingTranslatedAudio) {
            translatedAudioPlayer?.stopPlaying()
            translatedAudioPlayer = null
            isPlayingTranslatedAudio = false
            log.d(TAG) { "Stopped translated audio playback" }
        }
    }
    /**
     * NUEVO: Calcular duración de audio PCM
     */
    private fun calculateAudioDuration(pcmData: ByteArray): Long {
        val sampleRate = 16000
        val bytesPerSample = 2 // 16-bit
        val channels = 1
        val samples = pcmData.size / (bytesPerSample * channels)
        return (samples * 1000L) / sampleRate
    }

    /**
     * NUEVO: Crear archivo WAV desde datos PCM
     */
    private fun createWavFileFromPcm(pcmData: ByteArray, outputFile: File) {
        try {
            FileOutputStream(outputFile).use { output ->
                val header = createWavHeader(pcmData.size, 16000, 1, 16)
                output.write(header)
                output.write(pcmData)
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error creating WAV file: ${e.message}" }
        }
    }
    // ========== MÉTODOS PARA GRABACIÓN Y TRADUCCIÓN ==========

    /**
     * Iniciar grabación específica para traducción (usa tu sistema existente)
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecordingForTranslation(): Boolean {
        if (isRecordingForTranslation) {
            log.w(TAG) { "Already recording for translation" }
            return false
        }

        return try {
            currentTranslationRecordingPath =
                audioFileManager.createRecordingPath("translation_audio")
            translationAudioRecorder = WavRecorder(currentTranslationRecordingPath!!)

            if (translationAudioRecorder?.startRecording(coroutineScope) == true) {
                isRecordingForTranslation = true
                log.d(TAG) { "Started recording for translation: $currentTranslationRecordingPath" }

                // Iniciar procesamiento periódico del audio grabado
                startTranslationProcessing()

                true
            } else {
                translationAudioRecorder = null
                currentTranslationRecordingPath = null
                false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error starting translation recording: ${e.message}" }
            false
        }
    }

    /**
     * Detener grabación para traducción
     */
    private fun stopRecordingForTranslation(): String? {
        if (!isRecordingForTranslation) {
            return null
        }

        return try {
            // Detener procesamiento
            translationProcessingJob?.cancel()
            translationProcessingJob = null

            // Detener grabación
            translationAudioRecorder?.stopRecording()
            translationAudioRecorder = null
            isRecordingForTranslation = false

            val recordingPath = currentTranslationRecordingPath
            currentTranslationRecordingPath = null

            log.d(TAG) { "Stopped recording for translation: $recordingPath" }
            recordingPath
        } catch (e: Exception) {
            log.e(TAG) { "Error stopping translation recording: ${e.message}" }
            null
        }
    }

    /**
     * Iniciar procesamiento periódico para traducción
     */
    private fun startTranslationProcessing() {
        translationProcessingJob?.cancel()
        translationProcessingJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive && isTranslationEnabled && isRecordingForTranslation) {
                try {
                    delay(translationIntervalMs) // Esperar intervalo de traducción

                    // Leer el audio grabado hasta ahora
                    currentTranslationRecordingPath?.let { path ->
                        val audioData = readAudioFileForTranslation(path)
                        if (audioData != null && audioData.isNotEmpty()) {
                            // Enviar a OpenAI para traducción
                            openAIManager?.processAudioForTranslation(audioData)
                        }
                    }

                } catch (e: Exception) {
                    log.e(TAG) { "Error in translation processing: ${e.message}" }
                    delay(1000) // Esperar antes de reintentar
                }
            }
        }
    }

    /**
     * Leer archivo de audio para traducción
     */
    private fun readAudioFileForTranslation(filePath: String): ByteArray? {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                log.w(TAG) { "Translation audio file does not exist: $filePath" }
                return null
            }

            // Leer solo los datos nuevos desde la última traducción
            val fileSize = file.length()
            if (fileSize <= 44) { // Solo header WAV
                return null
            }

            // Leer el archivo completo (en una implementación más sofisticada,
            // podrías leer solo la parte nueva)
            val audioBytes = file.readBytes()

            // Saltar el header WAV (44 bytes) y devolver solo los datos PCM
            if (audioBytes.size > 44) {
                audioBytes.copyOfRange(44, audioBytes.size)
            } else {
                null
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error reading audio file for translation: ${e.message}" }
            null
        }
    }

    /**
     * Reproducir audio traducido (modificado para usar tu sistema)
     */
    private fun playTranslatedAudio(translatedAudio: ByteArray) {
        try {
            // Crear archivo temporal para el audio traducido
            val tempFile = File.createTempFile("translated_audio", ".wav", context.cacheDir)

            // Crear header WAV para el audio traducido
            val wavFile = createWavFile(translatedAudio, tempFile)

            // CRÍTICO: Detener el audio original y reproducir el traducido
            if (isPlayingOutputFile) {
                stopPlayingOutputAudioFile()
            }

            // Reproducir el audio traducido usando tu sistema existente
            if (startPlayingOutputAudioFile(wavFile.absolutePath, false)) {
                log.d(TAG) { "Playing translated audio: ${translatedAudio.size} bytes" }
            } else {
                log.e(TAG) { "Failed to play translated audio" }
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error playing translated audio: ${e.message}" }
        }
    }

    /**
     * Crear archivo WAV con header correcto
     */
    private fun createWavFile(pcmData: ByteArray, outputFile: File): File {
        try {
            FileOutputStream(outputFile).use { output ->
                // Escribir header WAV
                val header = createWavHeader(pcmData.size, 16000, 1, 16)
                output.write(header)

                // Escribir datos PCM
                output.write(pcmData)
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error creating WAV file: ${e.message}" }
        }

        return outputFile
    }

    /**
     * Crear header WAV
     */
    private fun createWavHeader(
        dataSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val header = ByteArray(44)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        // RIFF header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        // File size
        val fileSize = dataSize + 36
        header[4] = (fileSize and 0xff).toByte()
        header[5] = ((fileSize shr 8) and 0xff).toByte()
        header[6] = ((fileSize shr 16) and 0xff).toByte()
        header[7] = ((fileSize shr 24) and 0xff).toByte()

        // WAVE header
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // fmt chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        // fmt chunk size (16)
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0

        // Audio format (PCM = 1)
        header[20] = 1
        header[21] = 0

        // Number of channels
        header[22] = channels.toByte()
        header[23] = 0

        // Sample rate
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()

        // Byte rate
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()

        // Block align
        header[32] = blockAlign.toByte()
        header[33] = 0

        // Bits per sample
        header[34] = bitsPerSample.toByte()
        header[35] = 0

        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        // Data size
        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()

        return header
    }

    /**
     * NUEVO: Stop recording sent audio
     */
    override fun stopRecordingSentAudio(): String? {
        if (!isRecordingSentAudio) {
            log.w(TAG) { "Not recording sent audio" }
            return null
        }

        return try {
            sentAudioRecorder?.stopRecording()
            sentAudioRecorder = null
            isRecordingSentAudio = false

            val recordingPath = currentSentRecordingPath
            currentSentRecordingPath = null

            log.d(TAG) { "Stopped recording sent audio: $recordingPath" }
            recordingPath
        } catch (e: Exception) {
            log.e(TAG) { "Error stopping sent audio recording: ${e.message}" }
            null
        }
    }

    /**
     * Modificar startRecordingReceivedAudio para también iniciar traducción si está habilitada
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun startRecordingReceivedAudio(): Boolean {
        if (isRecordingReceivedAudio) {
            log.w(TAG) { "Already recording received audio" }
            return false
        }

        return try {
            currentReceivedRecordingPath = audioFileManager.createRecordingPath("received_audio")

            // CORREGIDO: Crear grabador personalizado mejorado
            receivedAudioRecorder = object : WavRecorder(currentReceivedRecordingPath!!) {
                override fun onAudioDataReceived(audioData: ByteArray) {
                    // Llamar al método padre para grabación normal
                    super.onAudioDataReceived(audioData)

                    // CORREGIDO: Interceptar para traducción solo si está habilitada
                    // y los datos no están vacíos
                    if (isTranslationEnabled && audioData.isNotEmpty()) {
                        interceptRemoteAudioForTranslation(audioData)
                    }
                }
            }

            if (receivedAudioRecorder?.startRecording(coroutineScope) == true) {
                isRecordingReceivedAudio = true
                log.d(TAG) { "Started recording received audio: $currentReceivedRecordingPath" }

                // CORREGIDO: Iniciar procesamiento solo después de confirmar grabación
                if (isTranslationEnabled) {
                    // Esperar un poco para que se acumule audio antes de procesar
                    coroutineScope.launch {
                        delay(1000)
                        startRemoteAudioTranslationProcessing()
                    }
                }

                true
            } else {
                receivedAudioRecorder = null
                currentReceivedRecordingPath = null
                false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error starting received audio recording: ${e.message}" }
            false
        }
    }

//    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
//    override fun startRecordingReceivedAudio(): Boolean {
//        if (isRecordingReceivedAudio) {
//            log.w(TAG) { "Already recording received audio" }
//            return false
//        }
//
//        return try {
//            currentReceivedRecordingPath = audioFileManager.createRecordingPath("received_audio")
//            // MODIFICADO: Crear grabador personalizado que también intercepte para traducción
//            receivedAudioRecorder = object : WavRecorder(currentReceivedRecordingPath!!) {
//
//                override fun onAudioDataReceived(audioData: ByteArray) {
//                    // Llamar al método padre para grabación normal
//                    super.onAudioDataReceived(audioData)
//
//                    // NUEVO: También interceptar para traducción
//                    interceptRemoteAudioForTranslation(audioData)
//                }
//            }
//            if (receivedAudioRecorder?.startRecording(coroutineScope) == true) {
//                isRecordingReceivedAudio = true
//                log.d(TAG) { "Started recording received audio: $currentReceivedRecordingPath" }
//
//                // NUEVO: Si la traducción está habilitada, iniciar procesamiento
//                if (isTranslationEnabled) {
//                    startRemoteAudioTranslationProcessing()
//                }
//
//                true
//            } else {
//                receivedAudioRecorder = null
//                currentReceivedRecordingPath = null
//                false
//            }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error starting received audio recording: ${e.message}" }
//            false
//        }
//    }

    /**
     * Modificar stopRecordingReceivedAudio para también detener traducción
     */
    override fun stopRecordingReceivedAudio(): String? {
        if (!isRecordingReceivedAudio) {
            log.w(TAG) { "Not recording received audio" }
            return null
        }

        return try {
            receivedAudioRecorder?.stopRecording()
            receivedAudioRecorder = null
            isRecordingReceivedAudio = false

            val recordingPath = currentReceivedRecordingPath
            currentReceivedRecordingPath = null

            // NUEVO: Detener procesamiento de traducción
            stopRemoteAudioTranslationProcessing()

            log.d(TAG) { "Stopped recording received audio: $recordingPath" }
            recordingPath
        } catch (e: Exception) {
            log.e(TAG) { "Error stopping received audio recording: ${e.message}" }
            null
        }
    }

    /**
     * NUEVO: Start playing audio file instead of microphone input
     */
    override fun startPlayingInputAudioFile(filePath: String, loop: Boolean): Boolean {
        if (isPlayingInputFile) {
            log.w(TAG) { "Already playing input audio file" }
            return false
        }

        return try {
            inputAudioFilePath = filePath
            inputAudioPlayer = AudioFilePlayer(filePath)

            if (inputAudioPlayer?.startPlaying(coroutineScope, loop) == true) {
                isPlayingInputFile = true
                // Disable microphone when playing file
                localAudioTrack?.enabled = false
                log.d(TAG) { "Started playing input audio file: $filePath" }
                true
            } else {
                inputAudioPlayer = null
                inputAudioFilePath = null
                false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error starting input audio file playback: ${e.message}" }
            false
        }
    }

    /**
     * NUEVO: Stop playing audio file and return to microphone input
     */
    override fun stopPlayingInputAudioFile(): Boolean {
        if (!isPlayingInputFile) {
            log.w(TAG) { "Not playing input audio file" }
            return false
        }

        return try {
            inputAudioPlayer?.stopPlaying()
            inputAudioPlayer = null
            inputAudioFilePath = null
            isPlayingInputFile = false

            // Re-enable microphone
            localAudioTrack?.enabled = true
            log.d(TAG) { "Stopped playing input audio file, returned to microphone" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error stopping input audio file playback: ${e.message}" }
            false
        }
    }

    /**
     * NUEVO: Start playing audio file instead of received audio
     */
    override fun startPlayingOutputAudioFile(filePath: String, loop: Boolean): Boolean {
        if (isPlayingOutputFile) {
            log.w(TAG) { "Already playing output audio file" }
            return false
        }

        return try {
            outputAudioFilePath = filePath
            outputAudioPlayer = AudioFilePlayer(filePath)

            if (outputAudioPlayer?.startPlaying(coroutineScope, loop) == true) {
                isPlayingOutputFile = true
                // Disable remote audio when playing file
                remoteAudioTrack?.enabled = false
                log.d(TAG) { "Started playing output audio file: $filePath" }
                true
            } else {
                outputAudioPlayer = null
                outputAudioFilePath = null
                false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error starting output audio file playback: ${e.message}" }
            false
        }
    }

    /**
     * NUEVO: Stop playing audio file and return to received audio
     */
    override fun stopPlayingOutputAudioFile(): Boolean {
        if (!isPlayingOutputFile) {
            log.w(TAG) { "Not playing output audio file" }
            return false
        }

        return try {
            outputAudioPlayer?.stopPlaying()
            outputAudioPlayer = null
            outputAudioFilePath = null
            isPlayingOutputFile = false

            // Re-enable remote audio
            remoteAudioTrack?.enabled = true
            log.d(TAG) { "Stopped playing output audio file, returned to received audio" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error stopping output audio file playback: ${e.message}" }
            false
        }
    }

    /**
     * NUEVO: Get list of recorded audio files
     */
    override fun getRecordedAudioFiles(): List<File> {
        return audioFileManager.getAudioFiles()
    }

    /**
     * NUEVO: Delete recorded audio file
     */
    override fun deleteRecordedAudioFile(filePath: String): Boolean {
        return audioFileManager.deleteAudioFile(filePath)
    }

    /**
     * NUEVO: Get audio file duration
     */
    override fun getAudioFileDuration(filePath: String): Long {
        return audioFileManager.getAudioDuration(filePath)
    }

    /**
     * NUEVO: Check if currently recording sent audio
     */
    override fun isRecordingSentAudio(): Boolean = isRecordingSentAudio

    /**
     * NUEVO: Check if currently recording received audio
     */
    override fun isRecordingReceivedAudio(): Boolean = isRecordingReceivedAudio

    /**
     * NUEVO: Check if currently playing input audio file
     */
    override fun isPlayingInputAudioFile(): Boolean = isPlayingInputFile

    /**
     * NUEVO: Check if currently playing output audio file
     */
    override fun isPlayingOutputAudioFile(): Boolean = isPlayingOutputFile

    /**
     * NUEVO: Get current input audio file path
     */
    override fun getCurrentInputAudioFilePath(): String? = inputAudioFilePath

    /**
     * NUEVO: Get current output audio file path
     */
    override fun getCurrentOutputAudioFilePath(): String? = outputAudioFilePath

    /**
     * FIXED: Setup Bluetooth SCO state monitoring
     */
    private fun setupBluetoothScoReceiver() {
        bluetoothScoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                        val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                        handleBluetoothScoStateChange(state)
                    }
                }
            }
        }

        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        context.registerReceiver(bluetoothScoReceiver, filter)
        log.d(TAG) { "Bluetooth SCO receiver registered" }
    }

    /**
     * FIXED: Handle Bluetooth SCO state changes
     */
    private fun handleBluetoothScoStateChange(state: Int) {
        when (state) {
            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                log.d(TAG) { "Bluetooth SCO connected" }
                isBluetoothScoRequested = false
                // Notify success if we were trying to connect
                webRtcEventListener?.onAudioDeviceChanged(currentOutputDevice)
            }

            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                log.d(TAG) { "Bluetooth SCO disconnected" }
                isBluetoothScoRequested = false
            }

            AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                log.d(TAG) { "Bluetooth SCO connecting..." }
            }

            AudioManager.SCO_AUDIO_STATE_ERROR -> {
                log.e(TAG) { "Bluetooth SCO error" }
                isBluetoothScoRequested = false
            }
        }
    }

    /**
     * Initialize Bluetooth components for enhanced device detection
     */
    private fun initializeBluetoothComponents() {
        try {
            bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            log.d(TAG) { "Bluetooth components initialized" }
        } catch (e: Exception) {
            log.e(TAG) { "Error initializing Bluetooth: ${e.message}" }
        }
    }

    /**
     * Setup audio device monitoring for real-time changes
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun setupAudioDeviceMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioDeviceCallback = object : AudioDeviceCallback() {
                @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    log.d(TAG) { "Audio devices added: ${addedDevices.size}" }
                    notifyDeviceChange()
                }

                @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    log.d(TAG) { "Audio devices removed: ${removedDevices.size}" }
                    notifyDeviceChange()
                }
            }
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        }
    }

    /**
     * Notify listeners about device changes
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
    private fun notifyDeviceChange() {
        try {
            val (inputs, outputs) = getAllAudioDevices()
            val allDevices = inputs + outputs
            deviceChangeListeners.forEach { listener ->
                try {
                    listener(allDevices)
                } catch (e: Exception) {
                    log.e(TAG) { "Error in device change listener: ${e.message}" }
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error notifying device change: ${e.message}" }
        }
    }

    /**
     * Initialize the WebRTC subsystem
     */
    override fun initialize() {
        log.d(TAG) { "Initializing WebRTC Manager..." }
        if (!isInitialized) {
            initializeAudio()
            initializePeerConnection()
            coroutineScope.launch {
                getAudioInputDevices()
            }
            isInitialized = true
        } else {
            log.d(TAG) { "WebRTC already initialized" }
        }
    }

    /**
     * Initialize audio system for calls
     */
    private fun initializeAudio() {
        audioManager?.let { am ->
            // Save current audio state
            savedAudioMode = am.mode
            savedIsSpeakerPhoneOn = am.isSpeakerphoneOn
            savedIsMicrophoneMute = am.isMicrophoneMute

            log.d(TAG) { "Saved audio state - Mode: $savedAudioMode, Speaker: $savedIsSpeakerPhoneOn, Mic muted: $savedIsMicrophoneMute" }

            // Configure audio for communication
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = false
            am.isMicrophoneMute = false

            // Request audio focus
            requestAudioFocus()

            log.d(TAG) { "Audio configured for WebRTC communication" }
        } ?: run {
            log.d(TAG) { "AudioManager not available!" }
        }
    }

    /**
     * Request audio focus for the call
     */
    private fun requestAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    // Handle audio focus changes
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            log.d(TAG) { "Audio focus gained" }
                            setAudioEnabled(true)
                        }

                        AudioManager.AUDIOFOCUS_LOSS -> {
                            log.d(TAG) { "Audio focus lost" }
                        }
                    }
                }
                .build()

            audioFocusRequest = focusRequest
            val result = audioManager?.requestAudioFocus(focusRequest)
                ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
            log.d(TAG) { "Audio focus request result: $result" }

        } else {
            // Legacy audio focus request for older Android versions
            @Suppress("DEPRECATION")
            val result = audioManager?.requestAudioFocus(
                { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> setAudioEnabled(true)
                    }
                },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            ) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED

            log.d(TAG) { "Legacy audio focus request result: $result" }
        }
    }

    /**
     * Releases audio focus and restores previous audio settings
     */
    private fun releaseAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioManager?.abandonAudioFocusRequest(request)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }

        // Restore previous audio settings
        audioManager?.let { am ->
            am.mode = savedAudioMode
            am.isSpeakerphoneOn = savedIsSpeakerPhoneOn
            am.isMicrophoneMute = savedIsMicrophoneMute

            log.d(TAG) { "Restored audio state" }
        }
    }

    /**
     * Gets available audio input devices (microphones)
     */
    suspend fun getAudioInputDevices(): List<MediaDeviceInfo> {
        return MediaDevices.enumerateDevices()
    }

    /**
     * Enhanced device detection with comprehensive AudioDevice mapping
     */
    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RECORD_AUDIO
        ]
    )
    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        log.d(TAG) { "Getting all enhanced audio devices..." }

        val inputDevices = mutableListOf<AudioDevice>()
        val outputDevices = mutableListOf<AudioDevice>()

        try {
            val am = audioManager ?: return Pair(emptyList(), emptyList())

            // Get current device states for comparison
            val currentInputDescriptor = getCurrentAudioInputDescriptor()
            val currentOutputDescriptor = getCurrentAudioOutputDescriptor()
            val defaultInputDescriptor = getDefaultInputDescriptor()
            val defaultOutputDescriptor = getDefaultOutputDescriptor()

            // Built-in devices (always available)
            addBuiltInDevices(
                inputDevices, outputDevices,
                currentInputDescriptor, currentOutputDescriptor,
                defaultInputDescriptor, defaultOutputDescriptor
            )

            // Wired devices
            addWiredDevices(
                inputDevices, outputDevices,
                currentInputDescriptor, currentOutputDescriptor
            )

            // Bluetooth devices
            addBluetoothDevices(
                inputDevices, outputDevices,
                currentInputDescriptor, currentOutputDescriptor
            )

            // USB and other devices (API 23+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addUsbAndOtherDevices(
                    inputDevices, outputDevices,
                    currentInputDescriptor, currentOutputDescriptor
                )
            }

            log.d(TAG) { "Found ${inputDevices.size} input and ${outputDevices.size} output devices" }

        } catch (e: Exception) {
            log.e(TAG) { "Error getting audio devices: ${e.message}" }
            // Return basic fallback devices
            return getFallbackDevices()
        }

        return Pair(inputDevices, outputDevices)
    }

    /**
     * Add built-in audio devices
     */
    private fun addBuiltInDevices(
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?,
        defaultInputDescriptor: String?,
        defaultOutputDescriptor: String?
    ) {
        // Built-in microphone
        val micDescriptor = "builtin_mic"
        val micAudioUnit = AudioUnit(
            type = AudioUnitTypes.MICROPHONE,
            capability = AudioUnitCompatibilities.RECORD,
            isCurrent = currentInputDescriptor == micDescriptor,
            isDefault = defaultInputDescriptor == micDescriptor
        )

        inputDevices.add(
            AudioDevice(
                name = "Built-in Microphone",
                descriptor = micDescriptor,
                nativeDevice = null,
                isOutput = false,
                audioUnit = micAudioUnit,
                connectionState = DeviceConnectionState.AVAILABLE,
                isWireless = false,
                supportsHDVoice = true,
                latency = 10,
                vendorInfo = "Built-in"
            )
        )

        // Earpiece (if available)
        if (audioManager?.hasEarpiece() == true) {
            val earpieceDescriptor = "earpiece"
            val earpieceAudioUnit = AudioUnit(
                type = AudioUnitTypes.EARPIECE,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = currentOutputDescriptor == earpieceDescriptor,
                isDefault = defaultOutputDescriptor == earpieceDescriptor
            )

            outputDevices.add(
                AudioDevice(
                    name = "Earpiece",
                    descriptor = earpieceDescriptor,
                    nativeDevice = null,
                    isOutput = true,
                    audioUnit = earpieceAudioUnit,
                    connectionState = DeviceConnectionState.AVAILABLE,
                    isWireless = false,
                    supportsHDVoice = true,
                    latency = 5,
                    vendorInfo = "Built-in"
                )
            )
        }

        // Built-in speaker
        val speakerDescriptor = "speaker"
        val speakerAudioUnit = AudioUnit(
            type = AudioUnitTypes.SPEAKER,
            capability = AudioUnitCompatibilities.PLAY,
            isCurrent = currentOutputDescriptor == speakerDescriptor,
            isDefault = if (audioManager?.hasEarpiece() == true) false else defaultOutputDescriptor == speakerDescriptor
        )

        outputDevices.add(
            AudioDevice(
                name = "Speaker",
                descriptor = speakerDescriptor,
                nativeDevice = null,
                isOutput = true,
                audioUnit = speakerAudioUnit,
                connectionState = DeviceConnectionState.AVAILABLE,
                isWireless = false,
                supportsHDVoice = true,
                latency = 15,
                vendorInfo = "Built-in"
            )
        )
    }

    /**
     * Add wired audio devices
     */
    private fun addWiredDevices(
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?
    ) {
        if (audioManager?.isWiredHeadsetOn == true) {
            // Wired headset output
            val headsetOutDescriptor = "wired_headset"
            val headsetOutAudioUnit = AudioUnit(
                type = AudioUnitTypes.HEADSET,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = currentOutputDescriptor == headsetOutDescriptor,
                isDefault = false
            )

            outputDevices.add(
                AudioDevice(
                    name = "Wired Headset",
                    descriptor = headsetOutDescriptor,
                    nativeDevice = null,
                    isOutput = true,
                    audioUnit = headsetOutAudioUnit,
                    connectionState = DeviceConnectionState.CONNECTED,
                    isWireless = false,
                    supportsHDVoice = true,
                    latency = 20,
                    vendorInfo = extractVendorFromWiredDevice()
                )
            )

            // Wired headset microphone
            val headsetMicDescriptor = "wired_headset_mic"
            val headsetMicAudioUnit = AudioUnit(
                type = AudioUnitTypes.HEADSET,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = currentInputDescriptor == headsetMicDescriptor,
                isDefault = false
            )

            inputDevices.add(
                AudioDevice(
                    name = "Wired Headset Microphone",
                    descriptor = headsetMicDescriptor,
                    nativeDevice = null,
                    isOutput = false,
                    audioUnit = headsetMicAudioUnit,
                    connectionState = DeviceConnectionState.CONNECTED,
                    isWireless = false,
                    supportsHDVoice = true,
                    latency = 20,
                    vendorInfo = extractVendorFromWiredDevice()
                )
            )
        }
    }

    /**
     * Add Bluetooth audio devices
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun addBluetoothDevices(
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?
    ) {
        try {
            if (audioManager?.isBluetoothScoAvailableOffCall == true) {
                val connectedBluetoothDevices = getConnectedBluetoothDevices()

                connectedBluetoothDevices.forEach { bluetoothDevice ->
                    val deviceName = getBluetoothDeviceName(bluetoothDevice)
                    val deviceAddress = bluetoothDevice.address
                    val vendorInfo = extractVendorFromBluetoothDevice(bluetoothDevice)
                    val signalStrength = estimateBluetoothSignalStrength()
                    val batteryLevel = getBluetoothBatteryLevel(bluetoothDevice)

                    // Determine device type and capabilities
                    val (audioUnitType, supportsA2DP) = classifyBluetoothDevice(bluetoothDevice)

                    // Bluetooth output device
                    val btOutDescriptor = "bluetooth_${deviceAddress}"
                    val btOutAudioUnit = AudioUnit(
                        type = audioUnitType,
                        capability = AudioUnitCompatibilities.PLAY,
                        isCurrent = currentOutputDescriptor == btOutDescriptor,
                        isDefault = false
                    )

                    outputDevices.add(
                        AudioDevice(
                            name = deviceName,
                            descriptor = btOutDescriptor,
                            nativeDevice = bluetoothDevice,
                            isOutput = true,
                            audioUnit = btOutAudioUnit,
                            connectionState = getBluetoothConnectionState(bluetoothDevice),
                            signalStrength = signalStrength,
                            batteryLevel = batteryLevel,
                            isWireless = true,
                            supportsHDVoice = supportsA2DP,
                            latency = if (supportsA2DP) 200 else 150,
                            vendorInfo = vendorInfo
                        )
                    )

                    // Bluetooth input device (for HFP/HSP)
                    if (supportsHandsFreeProfile(bluetoothDevice)) {
                        val btMicDescriptor = "bluetooth_mic_${deviceAddress}"
                        val btMicAudioUnit = AudioUnit(
                            type = AudioUnitTypes.BLUETOOTH,
                            capability = AudioUnitCompatibilities.RECORD,
                            isCurrent = currentInputDescriptor == btMicDescriptor,
                            isDefault = false
                        )

                        inputDevices.add(
                            AudioDevice(
                                name = "$deviceName Microphone",
                                descriptor = btMicDescriptor,
                                nativeDevice = bluetoothDevice,
                                isOutput = false,
                                audioUnit = btMicAudioUnit,
                                connectionState = getBluetoothConnectionState(bluetoothDevice),
                                signalStrength = signalStrength,
                                batteryLevel = batteryLevel,
                                isWireless = true,
                                supportsHDVoice = false, // HFP typically doesn't support HD Voice
                                latency = 150,
                                vendorInfo = vendorInfo
                            )
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            log.w(TAG) { "Bluetooth permission not granted: ${e.message}" }
        } catch (e: Exception) {
            log.e(TAG) { "Error adding Bluetooth devices: ${e.message}" }
        }
    }

    /**
     * Add USB and other audio devices (API 23+)
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun addUsbAndOtherDevices(
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?
    ) {
        try {
            val audioDevices = audioManager?.getDevices(
                AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS
            ) ?: return

            audioDevices.forEach { deviceInfo ->
                when (deviceInfo.type) {
                    AudioDeviceInfo.TYPE_USB_ACCESSORY,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> {
                        addUsbDevice(
                            deviceInfo, inputDevices, outputDevices,
                            currentInputDescriptor, currentOutputDescriptor
                        )
                    }

                    AudioDeviceInfo.TYPE_DOCK -> {
                        addDockDevice(
                            deviceInfo, inputDevices, outputDevices,
                            currentInputDescriptor, currentOutputDescriptor
                        )
                    }

                    AudioDeviceInfo.TYPE_AUX_LINE -> {
                        addAuxDevice(
                            deviceInfo, inputDevices, outputDevices,
                            currentInputDescriptor, currentOutputDescriptor
                        )
                    }

                    AudioDeviceInfo.TYPE_HEARING_AID -> {
                        addHearingAidDevice(
                            deviceInfo, inputDevices, outputDevices,
                            currentInputDescriptor, currentOutputDescriptor
                        )
                    }
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error adding USB/other devices: ${e.message}" }
        }
    }

    /**
     * Add USB audio device
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun addUsbDevice(
        deviceInfo: AudioDeviceInfo,
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?
    ) {
        val deviceName = deviceInfo.productName?.toString() ?: "USB Audio Device"
        val deviceId = deviceInfo.id.toString()
        val isSource = deviceInfo.isSource
        val isSink = deviceInfo.isSink

        if (isSink) {
            val usbOutDescriptor = "usb_out_$deviceId"
            val usbOutAudioUnit = AudioUnit(
                type = AudioUnitTypes.GENERICUSB,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = currentOutputDescriptor == usbOutDescriptor,
                isDefault = false
            )

            outputDevices.add(
                AudioDevice(
                    name = deviceName,
                    descriptor = usbOutDescriptor,
                    nativeDevice = deviceInfo,
                    isOutput = true,
                    audioUnit = usbOutAudioUnit,
                    connectionState = DeviceConnectionState.CONNECTED,
                    isWireless = false,
                    supportsHDVoice = true,
                    latency = 30,
                    vendorInfo = extractVendorFromDeviceName(deviceName)
                )
            )
        }

        if (isSource) {
            val usbInDescriptor = "usb_in_$deviceId"
            val usbInAudioUnit = AudioUnit(
                type = AudioUnitTypes.GENERICUSB,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = currentInputDescriptor == usbInDescriptor,
                isDefault = false
            )

            inputDevices.add(
                AudioDevice(
                    name = "$deviceName Microphone",
                    descriptor = usbInDescriptor,
                    nativeDevice = deviceInfo,
                    isOutput = false,
                    audioUnit = usbInAudioUnit,
                    connectionState = DeviceConnectionState.CONNECTED,
                    isWireless = false,
                    supportsHDVoice = true,
                    latency = 30,
                    vendorInfo = extractVendorFromDeviceName(deviceName)
                )
            )
        }
    }

    /**
     * Add dock audio device
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun addDockDevice(
        deviceInfo: AudioDeviceInfo,
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?
    ) {
        val deviceName = "Dock Audio"
        val deviceId = deviceInfo.id.toString()

        if (deviceInfo.isSink) {
            val dockDescriptor = "dock_$deviceId"
            val dockAudioUnit = AudioUnit(
                type = AudioUnitTypes.AUXLINE,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = currentOutputDescriptor == dockDescriptor,
                isDefault = false
            )

            outputDevices.add(
                AudioDevice(
                    name = deviceName,
                    descriptor = dockDescriptor,
                    nativeDevice = deviceInfo,
                    isOutput = true,
                    audioUnit = dockAudioUnit,
                    connectionState = DeviceConnectionState.CONNECTED,
                    isWireless = false,
                    supportsHDVoice = false,
                    latency = 25,
                    vendorInfo = null
                )
            )
        }
    }

    /**
     * Add auxiliary line device
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun addAuxDevice(
        deviceInfo: AudioDeviceInfo,
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?
    ) {
        val deviceName = "Auxiliary Audio"
        val deviceId = deviceInfo.id.toString()

        if (deviceInfo.isSink) {
            val auxDescriptor = "aux_$deviceId"
            val auxAudioUnit = AudioUnit(
                type = AudioUnitTypes.AUXLINE,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = currentOutputDescriptor == auxDescriptor,
                isDefault = false
            )

            outputDevices.add(
                AudioDevice(
                    name = deviceName,
                    descriptor = auxDescriptor,
                    nativeDevice = deviceInfo,
                    isOutput = true,
                    audioUnit = auxAudioUnit,
                    connectionState = DeviceConnectionState.CONNECTED,
                    isWireless = false,
                    supportsHDVoice = false,
                    latency = 15,
                    vendorInfo = null
                )
            )
        }
    }

    /**
     * Add hearing aid device
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun addHearingAidDevice(
        deviceInfo: AudioDeviceInfo,
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?
    ) {
        val deviceName = "Hearing Aid"
        val deviceId = deviceInfo.id.toString()

        if (deviceInfo.isSink) {
            val hearingAidDescriptor = "hearing_aid_$deviceId"
            val hearingAidAudioUnit = AudioUnit(
                type = AudioUnitTypes.HEARINGAID,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = currentOutputDescriptor == hearingAidDescriptor,
                isDefault = false
            )

            outputDevices.add(
                AudioDevice(
                    name = deviceName,
                    descriptor = hearingAidDescriptor,
                    nativeDevice = deviceInfo,
                    isOutput = true,
                    audioUnit = hearingAidAudioUnit,
                    connectionState = DeviceConnectionState.CONNECTED,
                    isWireless = true,
                    supportsHDVoice = true,
                    latency = 50,
                    vendorInfo = extractVendorFromDeviceName(
                        deviceInfo.productName?.toString() ?: ""
                    )
                )
            )
        }
    }

    /**
     * Get fallback devices when detection fails
     */
    private fun getFallbackDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        val inputDevices = listOf(
            AudioDevice(
                name = "Built-in Microphone",
                descriptor = "builtin_mic",
                nativeDevice = null,
                isOutput = false,
                audioUnit = AudioUnit(
                    type = AudioUnitTypes.MICROPHONE,
                    capability = AudioUnitCompatibilities.RECORD,
                    isCurrent = true,
                    isDefault = true
                ),
                connectionState = DeviceConnectionState.AVAILABLE,
                isWireless = false,
                supportsHDVoice = true,
                latency = 10,
                vendorInfo = "Built-in"
            )
        )

        val outputDevices = mutableListOf<AudioDevice>()

        // Add earpiece if available
        if (audioManager?.hasEarpiece() == true) {
            outputDevices.add(
                AudioDevice(
                    name = "Earpiece",
                    descriptor = "earpiece",
                    nativeDevice = null,
                    isOutput = true,
                    audioUnit = AudioUnit(
                        type = AudioUnitTypes.EARPIECE,
                        capability = AudioUnitCompatibilities.PLAY,
                        isCurrent = true,
                        isDefault = true
                    ),
                    connectionState = DeviceConnectionState.AVAILABLE,
                    isWireless = false,
                    supportsHDVoice = true,
                    latency = 5,
                    vendorInfo = "Built-in"
                )
            )
        }

        // Always add speaker
        outputDevices.add(
            AudioDevice(
                name = "Speaker",
                descriptor = "speaker",
                nativeDevice = null,
                isOutput = true,
                audioUnit = AudioUnit(
                    type = AudioUnitTypes.SPEAKER,
                    capability = AudioUnitCompatibilities.PLAY,
                    isCurrent = !audioManager?.hasEarpiece()!!,
                    isDefault = !audioManager?.hasEarpiece()!!
                ),
                connectionState = DeviceConnectionState.AVAILABLE,
                isWireless = false,
                supportsHDVoice = true,
                latency = 15,
                vendorInfo = "Built-in"
            )
        )

        return Pair(inputDevices, outputDevices)
    }

    // Helper methods for device detection and classification

    /**
     * Get current audio input descriptor
     */
    private fun getCurrentAudioInputDescriptor(): String? {
        return when {
            audioManager?.isBluetoothScoOn == true -> "bluetooth_mic_active"
            audioManager?.isWiredHeadsetOn == true -> "wired_headset_mic"
            else -> "builtin_mic"
        }
    }

    /**
     * Get current audio output descriptor
     */
    private fun getCurrentAudioOutputDescriptor(): String? {
        return when {
            audioManager?.isBluetoothScoOn == true -> "bluetooth_active"
            audioManager?.isSpeakerphoneOn == true -> "speaker"
            audioManager?.isWiredHeadsetOn == true -> "wired_headset"
            else -> if (audioManager?.hasEarpiece() == true) "earpiece" else "speaker"
        }
    }

    /**
     * Get default input descriptor
     */
    private fun getDefaultInputDescriptor(): String {
        return "builtin_mic"
    }

    /**
     * Get default output descriptor
     */
    private fun getDefaultOutputDescriptor(): String {
        return if (audioManager?.hasEarpiece() == true) "earpiece" else "speaker"
    }

    /**
     * Get connected Bluetooth devices
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getConnectedBluetoothDevices(): List<BluetoothDevice> {
        return try {
            bluetoothAdapter?.bondedDevices?.filter { device ->
                isBluetoothDeviceConnected(device)
            } ?: emptyList()
        } catch (e: SecurityException) {
            log.w(TAG) { "Bluetooth permission not granted for getting connected devices" }
            emptyList()
        } catch (e: Exception) {
            log.e(TAG) { "Error getting connected Bluetooth devices: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Check if Bluetooth device is connected - FIXED VERSION
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            // Method 1: Using BluetoothManager (API 18+)
            val bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val connectionState =
                bluetoothManager?.getConnectionState(device, BluetoothProfile.HEADSET)

            when (connectionState) {
                BluetoothProfile.STATE_CONNECTED -> return true
                BluetoothProfile.STATE_CONNECTING -> return false
                BluetoothProfile.STATE_DISCONNECTED -> return false
                BluetoothProfile.STATE_DISCONNECTING -> return false
                else -> {
                    // Method 2: Alternative check using reflection (fallback)
                    return checkBluetoothConnectionViaReflection(device)
                }
            }
        } catch (e: Exception) {
            log.w(TAG) { "Error checking Bluetooth connection state: ${e.message}" }
            // Method 3: Fallback - assume bonded devices are connected if Bluetooth audio is available
            device.bondState == BluetoothDevice.BOND_BONDED &&
                    audioManager?.isBluetoothScoAvailableOffCall == true
        }
    }

    /**
     * Alternative method using BluetoothProfile service listener (more reliable)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun checkBluetoothConnectionWithProfile(
        device: BluetoothDevice,
        callback: (Boolean) -> Unit
    ) {
        val profileListener = object : BluetoothProfile.ServiceListener {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                try {
                    val isConnected = when (profile) {
                        BluetoothProfile.HEADSET -> {
                            val headsetProxy = proxy as? BluetoothHeadset
                            headsetProxy?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        }

                        BluetoothProfile.A2DP -> {
                            val a2dpProxy = proxy as? BluetoothA2dp
                            a2dpProxy?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        }

                        else -> false
                    }
                    callback(isConnected)
                    bluetoothAdapter?.closeProfileProxy(profile, proxy)
                } catch (e: Exception) {
                    log.e(TAG) { "Error in profile service connected: ${e.message}" }
                    callback(false)
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                callback(false)
            }
        }

        // Try to get headset profile first, then A2DP
        val headsetConnected = bluetoothAdapter?.getProfileProxy(
            context,
            profileListener,
            BluetoothProfile.HEADSET
        ) ?: false

        if (!headsetConnected) {
            bluetoothAdapter?.getProfileProxy(
                context,
                profileListener,
                BluetoothProfile.A2DP
            )
        }
    }

    /**
     * Fallback method using reflection (use with caution)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun checkBluetoothConnectionViaReflection(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("isConnected")
            method.invoke(device) as Boolean
        } catch (e: Exception) {
            log.w(TAG) { "Reflection method failed: ${e.message}" }
            // Final fallback
            device.bondState == BluetoothDevice.BOND_BONDED
        }
    }

    /**
     * Enhanced method to get all connected Bluetooth devices with proper state checking
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getConnectedBluetoothDevicesEnhanced(): List<BluetoothDevice> {
        val connectedDevices = mutableListOf<BluetoothDevice>()

        try {
            // Get bonded devices first
            val bondedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()

            // Filter for audio-capable devices
            val audioDevices = bondedDevices.filter { device ->
                device.bluetoothClass?.hasService(BluetoothClass.Service.AUDIO) == true ||
                        device.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
                        device.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
                        device.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ||
                        device.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
            }

            // Check connection state for each audio device
            audioDevices.forEach { device ->
                if (isBluetoothDeviceConnected(device)) {
                    connectedDevices.add(device)
                }
            }

            log.d(TAG) { "Found ${connectedDevices.size} connected Bluetooth audio devices" }

        } catch (e: SecurityException) {
            log.w(TAG) { "Bluetooth permission not granted for getting connected devices" }
        } catch (e: Exception) {
            log.e(TAG) { "Error getting connected Bluetooth devices: ${e.message}" }
        }

        return connectedDevices
    }

    /**
     * Simplified synchronous check for immediate use
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun isBluetoothAudioDeviceConnected(): Boolean {
        return try {
            // Quick check using AudioManager
            audioManager?.isBluetoothScoOn == true ||
                    audioManager?.isBluetoothA2dpOn == true ||
                    (audioManager?.isBluetoothScoAvailableOffCall == true && hasConnectedBluetoothAudioDevice())
        } catch (e: Exception) {
            log.w(TAG) { "Error checking Bluetooth audio connection: ${e.message}" }
            false
        }
    }

    /**
     * Check if there's any connected Bluetooth audio device
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun hasConnectedBluetoothAudioDevice(): Boolean {
        return try {
            bluetoothAdapter?.bondedDevices?.any { device ->
                device.bluetoothClass?.hasService(BluetoothClass.Service.AUDIO) == true &&
                        device.bondState == BluetoothDevice.BOND_BONDED
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get Bluetooth device name safely
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getBluetoothDeviceName(device: BluetoothDevice): String {
        return try {
            device.name ?: "Bluetooth Device"
        } catch (e: SecurityException) {
            "Bluetooth Device"
        }
    }

    /**
     * Classify Bluetooth device type and capabilities
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun classifyBluetoothDevice(device: BluetoothDevice): Pair<AudioUnitTypes, Boolean> {
        return try {
            val deviceClass = device.bluetoothClass
            val majorClass = deviceClass?.majorDeviceClass
            val deviceType = deviceClass?.deviceClass

            val supportsA2DP = hasBluetoothProfile(device, BluetoothProfile.A2DP)
            val supportsHFP = hasBluetoothProfile(device, BluetoothProfile.HEADSET)

            val audioUnitType = when {
                supportsA2DP && supportsHFP -> AudioUnitTypes.BLUETOOTH
                supportsA2DP -> AudioUnitTypes.BLUETOOTHA2DP
                supportsHFP -> AudioUnitTypes.BLUETOOTH
                majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO -> {
                    if (supportsA2DP) AudioUnitTypes.BLUETOOTHA2DP else AudioUnitTypes.BLUETOOTH
                }

                else -> AudioUnitTypes.BLUETOOTH
            }

            Pair(audioUnitType, supportsA2DP)
        } catch (e: Exception) {
            log.w(TAG) { "Error classifying Bluetooth device: ${e.message}" }
            Pair(AudioUnitTypes.BLUETOOTH, false)
        }
    }

    /**
     * Check if device supports hands-free profile
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun supportsHandsFreeProfile(device: BluetoothDevice): Boolean {
        return hasBluetoothProfile(device, BluetoothProfile.HEADSET) ||
                hasBluetoothProfile(device, BluetoothProfile.HID_DEVICE)
    }

    /**
     * Check if device supports specific Bluetooth profile
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun hasBluetoothProfile(device: BluetoothDevice, profile: Int): Boolean {
        return try {
            bluetoothAdapter?.getProfileConnectionState(profile) == BluetoothAdapter.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get Bluetooth connection state
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getBluetoothConnectionState(device: BluetoothDevice): DeviceConnectionState {
        return if (isBluetoothDeviceConnected(device)) {
            DeviceConnectionState.CONNECTED
        } else {
            DeviceConnectionState.AVAILABLE
        }
    }

    /**
     * Estimate Bluetooth signal strength
     */
    private fun estimateBluetoothSignalStrength(): Int {
        // In a real implementation, you might use RSSI values if available
        return (70..95).random()
    }

    /**
     * Get Bluetooth device battery level using reflection
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getBluetoothBatteryLevel(device: BluetoothDevice): Int? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ - Try to use reflection to access hidden getBatteryLevel method
                val getBatteryLevelMethod =
                    BluetoothDevice::class.java.getMethod("getBatteryLevel")
                val batteryLevel = getBatteryLevelMethod.invoke(device) as? Int

                // Check if battery level is valid (not -1 which typically means unknown)
                batteryLevel?.takeIf { it >= 0 && it <= 100 }
            } else {
                null
            }
        } catch (e: Exception) {
            log.w(TAG) { "Could not get battery level via reflection: ${e.message}" }
            null
        }
    }

    /**
     * Extract vendor information from wired device
     */
    private fun extractVendorFromWiredDevice(): String? {
        // This could be enhanced with more sophisticated detection
        return null
    }

    /**
     * Extract vendor from Bluetooth device
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun extractVendorFromBluetoothDevice(device: BluetoothDevice): String? {
        return try {
            val deviceName = device.name?.lowercase() ?: return null
            extractVendorFromDeviceName(deviceName)
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Extract vendor information from device name
     */
    private fun extractVendorFromDeviceName(deviceName: String): String? {
        val name = deviceName.lowercase()
        return when {
            name.contains("apple") || name.contains("airpods") -> "Apple"
            name.contains("samsung") -> "Samsung"
            name.contains("sony") -> "Sony"
            name.contains("bose") -> "Bose"
            name.contains("jabra") -> "Jabra"
            name.contains("plantronics") -> "Plantronics"
            name.contains("logitech") -> "Logitech"
            name.contains("sennheiser") -> "Sennheiser"
            name.contains("jbl") -> "JBL"
            name.contains("beats") -> "Beats"
            name.contains("google") -> "Google"
            name.contains("microsoft") -> "Microsoft"
            name.contains("razer") -> "Razer"
            name.contains("steelseries") -> "SteelSeries"
            else -> null
        }
    }

    /**
     * FIXED: Enhanced device change during call with improved error handling
     */
    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        log.d(TAG) { "Changing audio output to: ${device.name} (${device.descriptor})" }

        if (!isInitialized || peerConnection == null) {
            log.w(TAG) { "Cannot change audio output: WebRTC not initialized" }
            return false
        }

        return try {
            val am = audioManager ?: return false

            // FIXED: Stop any previous Bluetooth SCO if switching to non-Bluetooth device
            if (!device.descriptor.startsWith("bluetooth_") && am.isBluetoothScoOn) {
                log.d(TAG) { "Stopping Bluetooth SCO for non-Bluetooth device" }
                am.stopBluetoothSco()
                isBluetoothScoRequested = false
            }

            val success = when {
                device.descriptor.startsWith("bluetooth_") -> {
                    switchToBluetoothOutput(device, am)
                }

                device.descriptor == "speaker" -> {
                    switchToSpeaker(am)
                }

                device.descriptor == "wired_headset" -> {
                    switchToWiredHeadset(am)
                }

                device.descriptor == "earpiece" -> {
                    switchToEarpiece(am)
                }

                device.descriptor.startsWith("usb_out_") -> {
                    switchToUsbOutput(device, am)
                }

                else -> {
                    log.w(TAG) { "Unknown audio device type: ${device.descriptor}" }
                    false
                }
            }

            if (success) {
                currentOutputDevice = device
                log.d(TAG) { "Successfully changed audio output to: ${device.name}" }

                // FIXED: Notify only after connection is established
                if (device.descriptor.startsWith("bluetooth_")) {
                    // For Bluetooth, wait for SCO connection to be established
                    coroutineScope.launch {
                        var attempts = 0
                        while (attempts < 10 && !am.isBluetoothScoOn) {
                            delay(200)
                            attempts++
                        }
                        if (am.isBluetoothScoOn) {
                            webRtcEventListener?.onAudioDeviceChanged(device)
                        }
                    }
                } else {
                    webRtcEventListener?.onAudioDeviceChanged(device)
                }
            }

            success
        } catch (e: Exception) {
            log.e(TAG) { "Error changing audio output: ${e.message}" }
            false
        }
    }

    /**
     * Enhanced device change for input during call
     */
    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
        log.d(TAG) { "Changing audio input to: ${device.name} (${device.descriptor})" }

        if (!isInitialized || peerConnection == null) {
            log.w(TAG) { "Cannot change audio input: WebRTC not initialized" }
            return false
        }

        return try {
            val am = audioManager ?: return false
            val success = when {
                device.descriptor.startsWith("bluetooth_mic_") -> {
                    switchToBluetoothInput(device, am)
                }

                device.descriptor == "wired_headset_mic" -> {
                    switchToWiredHeadsetMic(am)
                }

                device.descriptor == "builtin_mic" -> {
                    switchToBuiltinMic(am)
                }

                device.descriptor.startsWith("usb_in_") -> {
                    switchToUsbInput(device, am)
                }

                else -> {
                    log.w(TAG) { "Unknown audio input device: ${device.descriptor}" }
                    false
                }
            }

            if (success) {
                currentInputDevice = device
                webRtcEventListener?.onAudioDeviceChanged(device)
                log.d(TAG) { "Successfully changed audio input to: ${device.name}" }
            }

            success
        } catch (e: Exception) {
            log.e(TAG) { "Error changing audio input: ${e.message}" }
            false
        }
    }

    // Audio switching methods

    /**
     * FIXED: Enhanced Bluetooth output switching with proper SCO handling
     */
    private fun switchToBluetoothOutput(device: AudioDevice, am: AudioManager): Boolean {
        return try {
            log.d(TAG) { "Switching to Bluetooth output: ${device.name}" }

            // Verify if Bluetooth SCO is available
            if (!am.isBluetoothScoAvailableOffCall) {
                log.w(TAG) { "Bluetooth SCO not available for off-call use" }
                return false
            }

            // Check permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    log.w(TAG) { "BLUETOOTH_CONNECT permission not granted" }
                    return false
                }
            }

            // FIXED: Proper sequence for Bluetooth audio routing

            // 1. First, stop other audio modes
            am.isSpeakerphoneOn = false

            // 2. Ensure we're in communication mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            // 3. Check if SCO is already connected
            if (am.isBluetoothScoOn) {
                log.d(TAG) { "Bluetooth SCO already connected" }
                return true
            }

            // 4. Verify that the specific Bluetooth device is connected
            val bluetoothDevice = device.nativeDevice as? BluetoothDevice
            if (bluetoothDevice != null && !isBluetoothDeviceConnected(bluetoothDevice)) {
                log.w(TAG) { "Bluetooth device not connected: ${device.name}" }
                return false
            }

            // 5. Start Bluetooth SCO if not already requested
            if (!isBluetoothScoRequested && !am.isBluetoothScoOn) {
                log.d(TAG) { "Starting Bluetooth SCO..." }
                isBluetoothScoRequested = true
                am.startBluetoothSco()

                // FIXED: Wait a bit for SCO connection to establish
                // In a real environment, this should be handled asynchronously
                coroutineScope.launch {
                    delay(1000) // Wait 1 second
                    if (!am.isBluetoothScoOn) {
                        log.w(TAG) { "Bluetooth SCO failed to connect after timeout" }
                        isBluetoothScoRequested = false
                    }
                }
            }

            true
        } catch (e: SecurityException) {
            log.e(TAG) { "Security error switching to Bluetooth: ${e.message}" }
            false
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to Bluetooth output: ${e.message}" }
            false
        }
    }

    /**
     * FIXED: Enhanced speaker switching
     */
    private fun switchToSpeaker(am: AudioManager): Boolean {
        return try {
            // FIXED: Stop Bluetooth SCO if active
            if (am.isBluetoothScoOn) {
                log.d(TAG) { "Stopping Bluetooth SCO for speaker" }
                am.stopBluetoothSco()
                isBluetoothScoRequested = false
            }

            // Enable speaker
            am.isSpeakerphoneOn = true
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            log.d(TAG) { "Switched to speaker successfully" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to speaker: ${e.message}" }
            false
        }
    }

    /**
     * FIXED: Enhanced wired headset switching
     */
    private fun switchToWiredHeadset(am: AudioManager): Boolean {
        return try {
            // FIXED: Stop other audio modes
            am.isSpeakerphoneOn = false
            if (am.isBluetoothScoOn) {
                log.d(TAG) { "Stopping Bluetooth SCO for wired headset" }
                am.stopBluetoothSco()
                isBluetoothScoRequested = false
            }

            am.mode = AudioManager.MODE_IN_COMMUNICATION
            log.d(TAG) { "Switched to wired headset successfully" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to wired headset: ${e.message}" }
            false
        }
    }

    /**
     * FIXED: Enhanced earpiece switching
     */
    private fun switchToEarpiece(am: AudioManager): Boolean {
        return try {
            // FIXED: Reset all other output modes
            am.isSpeakerphoneOn = false
            if (am.isBluetoothScoOn) {
                log.d(TAG) { "Stopping Bluetooth SCO for earpiece" }
                am.stopBluetoothSco()
                isBluetoothScoRequested = false
            }

            am.mode = AudioManager.MODE_IN_COMMUNICATION
            log.d(TAG) { "Switched to earpiece successfully" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to earpiece: ${e.message}" }
            false
        }
    }

    private fun switchToUsbOutput(device: AudioDevice, am: AudioManager): Boolean {
        return try {
            // For USB devices, we might need to use AudioDeviceInfo routing (API 23+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && device.nativeDevice is AudioDeviceInfo) {
                // Reset other outputs first
                am.isSpeakerphoneOn = false
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                }
                // USB routing is typically handled automatically by the system
                true
            } else {
                false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to USB output: ${e.message}" }
            false
        }
    }

    /**
     * FIXED: Enhanced Bluetooth input switching
     */
    private fun switchToBluetoothInput(device: AudioDevice, am: AudioManager): Boolean {
        return try {
            log.d(TAG) { "Switching to Bluetooth input: ${device.name}" }

            // Verify Bluetooth SCO availability
            if (!am.isBluetoothScoAvailableOffCall) {
                log.w(TAG) { "Bluetooth SCO not available for input" }
                return false
            }

            // Check permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    log.w(TAG) { "BLUETOOTH_CONNECT permission not granted" }
                    return false
                }
            }

            // FIXED: Proper Bluetooth input routing
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            if (!am.isBluetoothScoOn && !isBluetoothScoRequested) {
                log.d(TAG) { "Starting Bluetooth SCO for input..." }
                isBluetoothScoRequested = true
                am.startBluetoothSco()

                coroutineScope.launch {
                    delay(1000)
                    if (!am.isBluetoothScoOn) {
                        log.w(TAG) { "Bluetooth SCO failed to connect for input" }
                        isBluetoothScoRequested = false
                    }
                }
            }

            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to Bluetooth input: ${e.message}" }
            false
        }
    }

    private fun switchToWiredHeadsetMic(am: AudioManager): Boolean {
        return try {
            if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to wired headset mic: ${e.message}" }
            false
        }
    }

    private fun switchToBuiltinMic(am: AudioManager): Boolean {
        return try {
            if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to builtin mic: ${e.message}" }
            false
        }
    }

    private fun switchToUsbInput(device: AudioDevice, am: AudioManager): Boolean {
        return try {
            // Similar to USB output, routing is typically automatic
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && device.nativeDevice is AudioDeviceInfo) {
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to USB input: ${e.message}" }
            false
        }
    }

    /**
     * Enhanced current device detection
     */
    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RECORD_AUDIO
        ]
    )
    override fun getCurrentInputDevice(): AudioDevice? {
        if (currentInputDevice != null) {
            return currentInputDevice
        }

        return try {
            val am = audioManager ?: return null
            val descriptor = getCurrentAudioInputDescriptor()

            when {
                descriptor?.startsWith("bluetooth_mic_") == true -> {
                    findBluetoothInputDevice()
                }

                descriptor == "wired_headset_mic" -> {
                    createWiredHeadsetMicDevice()
                }

                else -> {
                    createBuiltinMicDevice()
                }
            }.also {
                currentInputDevice = it
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error getting current input device: ${e.message}" }
            createBuiltinMicDevice()
        }
    }

    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RECORD_AUDIO
        ]
    )
    override fun getCurrentOutputDevice(): AudioDevice? {
        if (currentOutputDevice != null) {
            return currentOutputDevice
        }

        return try {
            val am = audioManager ?: return null
            val descriptor = getCurrentAudioOutputDescriptor()

            when {
                descriptor?.startsWith("bluetooth_") == true -> {
                    findBluetoothOutputDevice()
                }

                descriptor == "speaker" -> {
                    createSpeakerDevice()
                }

                descriptor == "wired_headset" -> {
                    createWiredHeadsetDevice()
                }

                descriptor == "earpiece" -> {
                    createEarpieceDevice()
                }

                else -> {
                    if (am.hasEarpiece()) createEarpieceDevice() else createSpeakerDevice()
                }
            }.also {
                currentOutputDevice = it
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error getting current output device: ${e.message}" }
            if (audioManager?.hasEarpiece() == true) createEarpieceDevice() else createSpeakerDevice()
        }
    }

    // Helper methods for creating device objects

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun findBluetoothInputDevice(): AudioDevice? {
        val connectedDevices = getConnectedBluetoothDevices()
        val firstDevice = connectedDevices.firstOrNull() ?: return null

        return AudioDevice(
            name = "${getBluetoothDeviceName(firstDevice)} Microphone",
            descriptor = "bluetooth_mic_${firstDevice.address}",
            nativeDevice = firstDevice,
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.BLUETOOTH,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = true,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            signalStrength = estimateBluetoothSignalStrength(),
            batteryLevel = getBluetoothBatteryLevel(firstDevice),
            isWireless = true,
            supportsHDVoice = false,
            latency = 150,
            vendorInfo = extractVendorFromBluetoothDevice(firstDevice)
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun findBluetoothOutputDevice(): AudioDevice? {
        val connectedDevices = getConnectedBluetoothDevices()
        val firstDevice = connectedDevices.firstOrNull() ?: return null

        val (audioUnitType, supportsA2DP) = classifyBluetoothDevice(firstDevice)

        return AudioDevice(
            name = getBluetoothDeviceName(firstDevice),
            descriptor = "bluetooth_${firstDevice.address}",
            nativeDevice = firstDevice,
            isOutput = true,
            audioUnit = AudioUnit(
                type = audioUnitType,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = true,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            signalStrength = estimateBluetoothSignalStrength(),
            batteryLevel = getBluetoothBatteryLevel(firstDevice),
            isWireless = true,
            supportsHDVoice = supportsA2DP,
            latency = if (supportsA2DP) 200 else 150,
            vendorInfo = extractVendorFromBluetoothDevice(firstDevice)
        )
    }

    private fun createWiredHeadsetMicDevice(): AudioDevice {
        return AudioDevice(
            name = "Wired Headset Microphone",
            descriptor = "wired_headset_mic",
            nativeDevice = null,
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.HEADSET,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = true,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = false,
            supportsHDVoice = true,
            latency = 20,
            vendorInfo = extractVendorFromWiredDevice()
        )
    }

    private fun createBuiltinMicDevice(): AudioDevice {
        return AudioDevice(
            name = "Built-in Microphone",
            descriptor = "builtin_mic",
            nativeDevice = null,
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.MICROPHONE,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = true,
                isDefault = true
            ),
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = false,
            supportsHDVoice = true,
            latency = 10,
            vendorInfo = "Built-in"
        )
    }

    private fun createWiredHeadsetDevice(): AudioDevice {
        return AudioDevice(
            name = "Wired Headset",
            descriptor = "wired_headset",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.HEADSET,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = true,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = false,
            supportsHDVoice = true,
            latency = 20,
            vendorInfo = extractVendorFromWiredDevice()
        )
    }

    private fun createSpeakerDevice(): AudioDevice {
        return AudioDevice(
            name = "Speaker",
            descriptor = "speaker",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.SPEAKER,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = true,
                isDefault = !audioManager?.hasEarpiece()!!
            ),
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = false,
            supportsHDVoice = true,
            latency = 15,
            vendorInfo = "Built-in"
        )
    }

    private fun createEarpieceDevice(): AudioDevice {
        return AudioDevice(
            name = "Earpiece",
            descriptor = "earpiece",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.EARPIECE,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = true,
                isDefault = true
            ),
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = false,
            supportsHDVoice = true,
            latency = 5,
            vendorInfo = "Built-in"
        )
    }

    /**
     * FIXED: Enhanced cleanup with Bluetooth SCO handling
     */
    override fun dispose() {
        log.d(TAG) { "Disposing Enhanced WebRtcManager resources..." }

        try {
            // Limpiar recursos de traducción
            if (isTranslationEnabled) {
                disableAudioTranslation()
            }

            // Detener grabación para traducción
            if (isRecordingForTranslation) {
                stopRecordingForTranslation()
            }

            // Limpiar buffers
            translationAudioBuffer.clear()

            if (isRecordingSentAudio) {
                stopRecordingSentAudio()
            }
            if (isRecordingReceivedAudio) {
                stopRecordingReceivedAudio()
            }
            if (isPlayingInputFile) {
                stopPlayingInputAudioFile()
            }
            if (isPlayingOutputFile) {
                stopPlayingOutputAudioFile()
            }

            // Clear listeners
            deviceChangeListeners.clear()

            // FIXED: Unregister Bluetooth SCO receiver
            bluetoothScoReceiver?.let { receiver ->
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: Exception) {
                    log.w(TAG) { "Error unregistering Bluetooth SCO receiver: ${e.message}" }
                }
            }

            // FIXED: Stop Bluetooth SCO if active
            audioManager?.let { am ->
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                }
            }
            isBluetoothScoRequested = false

            // Unregister audio device callback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioDeviceCallback?.let { callback ->
                    audioManager?.unregisterAudioDeviceCallback(callback)
                }
            }

            // Release audio focus and restore settings
            releaseAudioFocus()

            // Clean up WebRTC resources
            cleanupCall()

            // Reset state
            isInitialized = false
            isLocalAudioReady = false
            currentInputDevice = null
            currentOutputDevice = null

        } catch (e: Exception) {
            log.e(TAG) { "Error during disposal: ${e.message}" }
        }
    }

    /**
     * Create an SDP offer for starting a call
     * @return The SDP offer string
     */
    override suspend fun createOffer(): String {
        log.d(TAG) { "Creating SDP offer..." }

        // Make sure WebRTC and audio is initialized
        if (!isInitialized) {
            log.d(TAG) { "WebRTC not initialized, initializing now" }
            initialize()
        } else {
            // Reinitialize audio settings for outgoing call
            initializeAudio()
        }

        val peerConn = peerConnection ?: run {
            log.d(TAG) { "PeerConnection not initialized, reinitializing" }
            initializePeerConnection()
            peerConnection
                ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        // Make sure local audio track is added and enabled before creating an offer
        if (!isLocalAudioReady) {
            log.d(TAG) { "Ensuring local audio track is ready..." }
            isLocalAudioReady = ensureLocalAudioTrack()
            if (!isLocalAudioReady) {
                log.d(TAG) { "Failed to prepare local audio track!" }
                // Continue anyway to create the offer, but log the issue
            }
        }

        val options = OfferAnswerOptions(
            voiceActivityDetection = true
        )

        val sessionDescription = peerConn.createOffer(options)
        peerConn.setLocalDescription(sessionDescription)

        // Ensure microphone is unmuted for outgoing call
        audioManager?.isMicrophoneMute = false

        log.d(TAG) { "Created offer SDP: ${sessionDescription.sdp}" }
        return sessionDescription.sdp
    }

    /**
     * Create an SDP answer in response to an offer
     * @param accountInfo The current account information
     * @param offerSdp The SDP offer from the remote party
     * @return The SDP answer string
     */
    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String {
        log.d(TAG) { "Creating SDP answer..." }

        // Make sure WebRTC and audio is initialized
        if (!isInitialized) {
            log.d(TAG) { "WebRTC not initialized, initializing now" }
            initialize()
        } else {
            // Reinitialize audio settings for incoming call
            initializeAudio()
        }

        val peerConn = peerConnection ?: run {
            log.d(TAG) { "PeerConnection not initialized, reinitializing" }
            initializePeerConnection()
            peerConnection
                ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        // IMPORTANT: Make sure local audio track is added BEFORE setting remote description
        // This is a critical fix for incoming calls
        if (!isLocalAudioReady) {
            log.d(TAG) { "Ensuring local audio track is ready before answering..." }
            isLocalAudioReady = ensureLocalAudioTrack()
            if (!isLocalAudioReady) {
                log.d(TAG) { "Failed to prepare local audio track for answering!" }
                // Continue anyway to create the answer, but log the issue
            }
        }

        // Set the remote offer
        val remoteOffer = SessionDescription(
            type = SessionDescriptionType.Offer,
            sdp = offerSdp
        )
        peerConn.setRemoteDescription(remoteOffer)

        // Create answer
        val options = OfferAnswerOptions(
            voiceActivityDetection = true
        )

        val sessionDescription = peerConn.createAnswer(options)
        peerConn.setLocalDescription(sessionDescription)

        // Ensure audio is enabled for answering the call
        setAudioEnabled(true)

        // Explicitly ensure microphone is not muted for incoming call
        audioManager?.isMicrophoneMute = false

        log.d(TAG) { "Created answer SDP: ${sessionDescription.sdp}" }
        return sessionDescription.sdp
    }

    /**
     * Set the remote description (offer or answer)
     * @param sdp The remote SDP string
     * @param type The SDP type (offer or answer)
     */
    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
        log.d(TAG) { "Setting remote description type: $type" }

        // Make sure WebRTC is initialized
        if (!isInitialized) {
            log.d(TAG) { "WebRTC not initialized, initializing now" }
            initialize()
        }

        val peerConn = peerConnection ?: run {
            log.d(TAG) { "PeerConnection not initialized, reinitializing" }
            initializePeerConnection()
            peerConnection
                ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        // If this is an offer, ensure we have local audio ready before proceeding
        if (type == SdpType.OFFER && !isLocalAudioReady) {
            log.d(TAG) { "Ensuring local audio track is ready before processing offer..." }
            isLocalAudioReady = ensureLocalAudioTrack()
        }

        val sdpType = when (type) {
            SdpType.OFFER -> SessionDescriptionType.Offer
            SdpType.ANSWER -> SessionDescriptionType.Answer
        }

        val sessionDescription = SessionDescription(
            type = sdpType,
            sdp = sdp
        )

        peerConn.setRemoteDescription(sessionDescription)

        // If this was an answer to our offer, make sure audio is enabled
        if (type == SdpType.ANSWER) {
            setAudioEnabled(true)
            audioManager?.isMicrophoneMute = false
        }
    }

    /**
     * Add an ICE candidate received from the remote party
     * @param candidate The ICE candidate string
     * @param sdpMid The media ID
     * @param sdpMLineIndex The media line index
     */
    override suspend fun addIceCandidate(
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int?
    ) {
        log.d(TAG) { "Adding ICE candidate: $candidate" }

        // Make sure WebRTC is initialized
        if (!isInitialized) {
            log.d(TAG) { "WebRTC not initialized, initializing now" }
            initialize()
            // If still no peer connection after initialize, return
            if (peerConnection == null) {
                log.d(TAG) { "Failed to initialize PeerConnection, cannot add ICE candidate" }
                return
            }
        }

        val peerConn = peerConnection ?: run {
            log.d(TAG) { "PeerConnection not available, cannot add ICE candidate" }
            return
        }

        val iceCandidate = IceCandidate(
            sdpMid = sdpMid ?: "",
            sdpMLineIndex = sdpMLineIndex ?: 0,
            candidate = candidate
        )

        peerConn.addIceCandidate(iceCandidate)
    }

    /**
     * Sets the mute state for the local microphone
     * @param muted Whether the microphone should be muted
     */
    override fun setMuted(muted: Boolean) {
        log.d(TAG) { "Setting microphone mute: $muted" }

        try {
            // Use AudioManager to mute microphone
            audioManager?.isMicrophoneMute = muted

            // Also disable the audio track if we have one
            localAudioTrack?.enabled = !muted
        } catch (e: Exception) {
            log.d(TAG) { "Error setting mute state: ${e.message}" }
        }
    }

    /**
     * Gets the current mute state of the microphone
     * @return true if muted, false otherwise
     */
    override fun isMuted(): Boolean {
        val isAudioManagerMuted = audioManager?.isMicrophoneMute ?: false
        val isTrackDisabled = localAudioTrack?.enabled?.not() ?: false

        // If either is muted/disabled, consider it muted
        return isAudioManagerMuted || isTrackDisabled
    }

    /**
     * Gets the local SDP description
     * @return The local SDP string, or null if not set
     */
    override fun getLocalDescription(): String? {
        return peerConnection?.localDescription?.sdp
    }

    /**
     * Sets the media direction (sendrecv, sendonly, recvonly, inactive)
     * @param direction The desired media direction
     */
    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
        log.d(TAG) { "Setting media direction: $direction" }

        if (!isInitialized || peerConnection == null) {
            log.d(TAG) { "Cannot set media direction: WebRTC not initialized" }
            return
        }

        val peerConn = peerConnection ?: return

        try {
            // Get current description
            val currentDesc = peerConn.localDescription ?: return

            // Change direction in SDP
            val modifiedSdp = updateSdpDirection(currentDesc.sdp, direction)

            // Create and set the modified local description
            val newDesc = SessionDescription(
                type = currentDesc.type,
                sdp = modifiedSdp
            )

            peerConn.setLocalDescription(newDesc)

            // If we have an answer/offer from remote side, we need to renegotiate
            if (peerConn.remoteDescription != null) {
                // Create new offer/answer to apply the changes
                val options = OfferAnswerOptions(
                    voiceActivityDetection = true
                )

                val sessionDesc = if (currentDesc.type == SessionDescriptionType.Offer) {
                    peerConn.createOffer(options)
                } else {
                    peerConn.createAnswer(options)
                }

                // Modify the new SDP to ensure our direction is applied
                val finalSdp = updateSdpDirection(sessionDesc.sdp, direction)
                val finalDesc = SessionDescription(
                    type = sessionDesc.type,
                    sdp = finalSdp
                )

                peerConn.setLocalDescription(finalDesc)
            }
        } catch (e: Exception) {
            log.d(TAG) { "Error setting media direction: ${e.message}" }
        }
    }

    /**
     * Modifies the SDP to update the media direction attribute
     */
    private fun updateSdpDirection(
        sdp: String,
        direction: WebRtcManager.MediaDirection
    ): String {
        val directionStr = when (direction) {
            WebRtcManager.MediaDirection.SENDRECV -> "sendrecv"
            WebRtcManager.MediaDirection.SENDONLY -> "sendonly"
            WebRtcManager.MediaDirection.RECVONLY -> "recvonly"
            WebRtcManager.MediaDirection.INACTIVE -> "inactive"
        }

        val lines = sdp.lines().toMutableList()
        var inMediaSection = false
        var inAudioSection = false

        for (i in lines.indices) {
            val line = lines[i]

            // Track media sections
            if (line.startsWith("m=")) {
                inMediaSection = true
                inAudioSection = line.startsWith("m=audio")
            }

            // Update direction in audio section
            if (inMediaSection && inAudioSection) {
                if (line.startsWith("a=sendrecv") ||
                    line.startsWith("a=sendonly") ||
                    line.startsWith("a=recvonly") ||
                    line.startsWith("a=inactive")
                ) {
                    lines[i] = "a=$directionStr"
                }
            }

            // End of section
            if (inMediaSection && line.trim().isEmpty()) {
                inMediaSection = false
                inAudioSection = false
            }
        }

        return lines.joinToString("\r\n")
    }

    /**
     * Applies modified SDP to the peer connection
     * @param modifiedSdp The modified SDP string
     * @return true if successful, false otherwise
     */
    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
        return try {
            val description = SessionDescription(SessionDescriptionType.Offer, modifiedSdp)
            peerConnection?.setLocalDescription(description)
            true
        } catch (e: Exception) {
            log.d(TAG) { "Error applying modified SDP: ${e.message}" }
            false
        }
    }

    /**
     * Extension function to check if a device has an earpiece
     * Most phones have an earpiece, but tablets and some devices don't
     */
    private fun AudioManager.hasEarpiece(): Boolean {
        // This is a heuristic approach - we can't directly detect earpiece
        // Most phones support MODE_IN_COMMUNICATION, tablets typically don't
        return context.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
            ?: false
    }

    /**
     * Extension function to check if a Bluetooth device is connected (Android S+ only)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @RequiresApi(Build.VERSION_CODES.S)
    private fun BluetoothDevice.isConnected(): Boolean {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        return connectedDevices.contains(this)
    }

    /**
     * Enable or disable the local audio track
     * @param enabled Whether audio should be enabled
     */
    override fun setAudioEnabled(enabled: Boolean) {
        log.d(TAG) { "Setting audio enabled: $enabled" }

        // Use AudioManager to ensure microphone state
        audioManager?.isMicrophoneMute = !enabled

        if (localAudioTrack == null && isInitialized) {
            log.d(TAG) { "No local audio track but WebRTC is initialized, trying to add audio track" }
            coroutineScope.launch {
                ensureLocalAudioTrack()
                localAudioTrack?.enabled = enabled
            }
        } else {
            localAudioTrack?.enabled = enabled
        }
    }

    /**
     * Get current connection state
     * @return The connection state
     */
    override fun getConnectionState(): WebRtcConnectionState {
        if (!isInitialized || peerConnection == null) {
            return WebRtcConnectionState.NEW
        }

        val state = peerConnection?.connectionState ?: return WebRtcConnectionState.NEW
        return mapConnectionState(state)
    }

    /**
     * Set a listener for WebRTC events
     * @param listener The WebRTC event listener
     */
    override fun setListener(listener: Any?) {
        if (listener is WebRtcEventListener) {
            webRtcEventListener = listener
            log.d(TAG) { "WebRTC event listener set" }
        } else {
            log.d(TAG) { "Invalid listener type provided" }
        }
    }

    override fun prepareAudioForIncomingCall() {
        log.d(TAG) { "Preparing audio for incoming call" }
        initializeAudio()
    }

    /**
     * Enhanced audio diagnostics
     */
    @SuppressLint("MissingPermission")
    override fun diagnoseAudioIssues(): String {
        return buildString {
            appendLine("=== ENHANCED AUDIO DIAGNOSIS ===")

            // Basic WebRTC state
            appendLine("WebRTC Initialized: $isInitialized")
            appendLine("Local Audio Ready: $isLocalAudioReady")
            appendLine("Local Audio Track: ${localAudioTrack != null}")
            appendLine("Local Audio Enabled: ${localAudioTrack?.enabled}")
            appendLine("Remote Audio Track: ${remoteAudioTrack != null}")
            appendLine("Remote Audio Enabled: ${remoteAudioTrack?.enabled}")

            // NUEVO: Audio recording and playback state
            appendLine("\n--- Audio Recording/Playback ---")
            appendLine("Recording Sent Audio: $isRecordingSentAudio")
            appendLine("Recording Received Audio: $isRecordingReceivedAudio")
            appendLine("Playing Input File: $isPlayingInputFile")
            appendLine("Playing Output File: $isPlayingOutputFile")
            appendLine("Current Input File: $inputAudioFilePath")
            appendLine("Current Output File: $outputAudioFilePath")

            // AudioManager state
            audioManager?.let { am ->
                appendLine("\n--- AudioManager State ---")
                appendLine("Audio Mode: ${am.mode}")
                appendLine("Speaker On: ${am.isSpeakerphoneOn}")
                appendLine("Mic Muted: ${am.isMicrophoneMute}")
                appendLine("Bluetooth SCO On: ${am.isBluetoothScoOn}")
                appendLine("Bluetooth SCO Available: ${am.isBluetoothScoAvailableOffCall}")
                appendLine("Wired Headset On: ${am.isWiredHeadsetOn}")
                appendLine("Music Active: ${am.isMusicActive}")
                appendLine("Has Earpiece: ${am.hasEarpiece()}")
            }

            // Current devices
            appendLine("\n--- Current Devices ---")
            appendLine("Current Input: ${currentInputDevice?.name ?: "Not set"}")
            appendLine("Current Output: ${currentOutputDevice?.name ?: "Not set"}")

            // Device counts
            try {
                val (inputs, outputs) = getAllAudioDevices()
                appendLine("\n--- Available Devices ---")
                appendLine("Input Devices: ${inputs.size}")
                inputs.forEach { device ->
                    appendLine("  - ${device.name} (${device.audioUnit.type}, ${device.connectionState})")
                }
                appendLine("Output Devices: ${outputs.size}")
                outputs.forEach { device ->
                    appendLine("  - ${device.name} (${device.audioUnit.type}, ${device.connectionState})")
                }
            } catch (e: Exception) {
                appendLine("Error getting devices: ${e.message}")
            }

            // Bluetooth state
            appendLine("\n--- Bluetooth State ---")
            appendLine("Bluetooth Adapter: ${bluetoothAdapter != null}")
            appendLine("Bluetooth Enabled: ${bluetoothAdapter?.isEnabled}")
            try {
                val connectedBt = getConnectedBluetoothDevices()
                appendLine("Connected BT Devices: ${connectedBt.size}")
            } catch (e: Exception) {
                appendLine("BT Device Check Error: ${e.message}")
            }

            // NUEVO: Recorded files info
            appendLine("\n--- Recorded Files ---")
            try {
                val recordedFiles = getRecordedAudioFiles()
                appendLine("Total Recorded Files: ${recordedFiles.size}")
                recordedFiles.take(5).forEach { file ->
                    val duration = getAudioFileDuration(file.absolutePath)
                    appendLine("  - ${file.name} (${duration}ms)")
                }
            } catch (e: Exception) {
                appendLine("Error getting recorded files: ${e.message}")
            }

            // Permission status
            appendLine("\n--- Permissions ---")
            appendLine("Record Audio: ${hasPermission(Manifest.permission.RECORD_AUDIO)}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appendLine("Bluetooth Connect: ${hasPermission(Manifest.permission.BLUETOOTH_CONNECT)}")
            }
        }
    }

    override fun isInitialized(): Boolean = isInitialized

    /**
     * Send DTMF tones via RTP (RFC 4733)
     * @param tones The DTMF tones to send (0-9, *, #, A-D)
     * @param duration Duration in milliseconds for each tone (optional, default 100ms)
     * @param gap Gap between tones in milliseconds (optional, default 70ms)
     * @return true if successfully started sending tones, false otherwise
     */
    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        log.d(TAG) { "Sending DTMF tones: $tones (duration: $duration, gap: $gap)" }

        // Check if WebRTC is initialized and connection is established
        if (!isInitialized || peerConnection == null) {
            log.d(TAG) { "Cannot send DTMF: WebRTC not initialized" }
            return false
        }

        try {
            // Get audio sender
            val audioSender = peerConnection?.getSenders()?.find { sender ->
                sender.track?.kind == MediaStreamTrackKind.Audio
            }

            if (audioSender == null) {
                log.d(TAG) { "Cannot send DTMF: No audio sender found" }
                return false
            }

            // Get the DTMF sender for this audio track
            val dtmfSender = audioSender.dtmf ?: run {
                log.d(TAG) { "Cannot send DTMF: DtmfSender not available" }
                return false
            }

            // Send the DTMF tones
            val sanitizedTones = sanitizeDtmfTones(tones)
            if (sanitizedTones.isEmpty()) {
                log.d(TAG) { "Cannot send DTMF: No valid tones to send" }
                return false
            }

            val result = dtmfSender.insertDtmf(
                tones = sanitizedTones,
                durationMs = duration,
                interToneGapMs = gap
            )

            log.d(TAG) { "DTMF tone sending result: $result" }
            return result
        } catch (e: Exception) {
            log.d(TAG) { "Error sending DTMF tones: ${e.message}" }
            return false
        }
    }

    /**
     * Sanitizes DTMF tones to ensure only valid characters are sent
     * Valid DTMF characters are 0-9, *, #, A-D, and comma (,) for pause
     */
    private fun sanitizeDtmfTones(tones: String): String {
        // WebRTC supports 0-9, *, #, A-D and comma (,) for pause
        val validDtmfPattern = Regex("[0-9A-D*#,]", RegexOption.IGNORE_CASE)

        return tones.filter { tone ->
            validDtmfPattern.matches(tone.toString())
        }
    }

    /**
     * Check if permission is granted
     */
    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Add device change listener
     */
    fun addDeviceChangeListener(listener: (List<AudioDevice>) -> Unit) {
        deviceChangeListeners.add(listener)
    }

    /**
     * Remove device change listener
     */
    fun removeDeviceChangeListener(listener: (List<AudioDevice>) -> Unit) {
        deviceChangeListeners.remove(listener)
    }

    /**
     * Get devices by quality score
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
    fun getAudioDevicesByQuality(): Pair<List<AudioDevice>, List<AudioDevice>> {
        val (inputs, outputs) = getAllAudioDevices()
        return Pair(
            inputs.sortedByDescending { it.qualityScore },
            outputs.sortedByDescending { it.qualityScore }
        )
    }

    /**
     * Get recommended device for optimal call quality
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
    fun getRecommendedAudioDevice(isOutput: Boolean): AudioDevice? {
        val (inputs, outputs) = getAudioDevicesByQuality()
        val devices = if (isOutput) outputs else inputs

        // Prefer current device if it has good quality
        val currentDevice = if (isOutput) currentOutputDevice else currentInputDevice
        if (currentDevice != null && currentDevice.qualityScore >= 70) {
            return currentDevice
        }

        // Otherwise, return the highest quality available device
        return devices.firstOrNull {
            it.connectionState == DeviceConnectionState.AVAILABLE ||
                    it.connectionState == DeviceConnectionState.CONNECTED
        }
    }

    /**
     * Maps WebRTC's PeerConnectionState to our WebRtcConnectionState enum
     */
    private fun mapConnectionState(state: PeerConnectionState): WebRtcConnectionState {
        return when (state) {
            PeerConnectionState.New -> WebRtcConnectionState.NEW
            PeerConnectionState.Connecting -> WebRtcConnectionState.CONNECTING
            PeerConnectionState.Connected -> WebRtcConnectionState.CONNECTED
            PeerConnectionState.Disconnected -> WebRtcConnectionState.DISCONNECTED
            PeerConnectionState.Failed -> WebRtcConnectionState.FAILED
            PeerConnectionState.Closed -> WebRtcConnectionState.CLOSED
        }
    }

    /**
     * Initializes the PeerConnection with ICE configuration and sets up event observers.
     */
    private fun initializePeerConnection() {
        log.d(TAG) { "Initializing PeerConnection..." }
        cleanupCall()

        try {
            val rtcConfig = RtcConfiguration(
                iceServers = listOf(
                    IceServer(
                        urls = listOf(
                            "stun:stun.l.google.com:19302",
                            "stun:stun1.l.google.com:19302"
                        )
                    )
                )
            )

            log.d(TAG) { "RTC Configuration: $rtcConfig" }

            peerConnection = PeerConnection(rtcConfig).apply {
                setupPeerConnectionObservers()
            }

            log.d(TAG) { "PeerConnection created: ${peerConnection != null}" }

            // Don't add local audio track here - will be done when needed
            // This prevents requesting microphone permission unnecessarily
            isLocalAudioReady = false
        } catch (e: Exception) {
            log.d(TAG) { "Error initializing PeerConnection: ${e.message}" }
            peerConnection = null
            isInitialized = false
            isLocalAudioReady = false
        }
    }

    /**
     * NUEVO: Iniciar captura de audio remoto para traducción
     */
    private fun startCapturingRemoteAudio(audioTrack: AudioStreamTrack) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Aquí necesitarías interceptar el audio del track remoto
                // Esto es una aproximación - la implementación exacta depende de la librería WebRTC
                log.d(TAG) { "Started capturing remote audio for translation" }

                // NOTA: La captura real del audio remoto requiere acceso a los datos del AudioTrack
                // Esto podría requerir modificaciones en la capa nativa de WebRTC
                // Por ahora, usamos un enfoque alternativo capturando desde el output

            } catch (e: Exception) {
                log.e(TAG) { "Error starting remote audio capture: ${e.message}" }
            }
        }
    }

    /**
     * Modificar setupPeerConnectionObservers para iniciar grabación automáticamente
     */
    @SuppressLint("MissingPermission")
    private fun PeerConnection.setupPeerConnectionObservers() {

        onTrack.onEach { event ->
            log.d(TAG) { "Remote track received: $event" }
            val track = event.receiver.track

            if (track is AudioStreamTrack) {
                log.d(TAG) { "Remote audio track established" }
                remoteAudioTrack = track
                remoteAudioTrack?.enabled = true

                // NUEVO: Si la traducción está habilitada, iniciar captura de audio remoto
                if (isTranslationEnabled) {
                    startCapturingRemoteAudio(track)
                }

                webRtcEventListener?.onRemoteAudioTrack()
            }
        }.launchIn(coroutineScope)

        onIceCandidate.onEach { candidate ->
            log.d(TAG) { "New ICE Candidate: ${candidate.candidate}" }
            webRtcEventListener?.onIceCandidate(
                candidate.candidate,
                candidate.sdpMid,
                candidate.sdpMLineIndex
            )
        }.launchIn(coroutineScope)

        onConnectionStateChange.onEach { state ->
            log.d(TAG) { "Connection state changed: $state" }
            when (state) {
                PeerConnectionState.Connected -> {
                    log.d(TAG) { "Call active: Connected" }
                    CallStateManager.updateCallState(CallState.CONNECTED)
                    setAudioEnabled(true)
                    audioManager?.isMicrophoneMute = false

                    // NUEVO: Iniciar grabación automáticamente si la traducción está habilitada
                    if (isTranslationEnabled && !isRecordingReceivedAudio) {
                        log.d(TAG) { "Auto-starting audio recording for translation" }
                        startRecordingReceivedAudio()
                    }
                }

                PeerConnectionState.Disconnected,
                PeerConnectionState.Failed,
                PeerConnectionState.Closed -> {
                    CallStateManager.updateCallState(CallState.ENDED)
                    log.d(TAG) { "Call ended" }
                    releaseAudioFocus()

                    // Detener todas las grabaciones y reproducciones
                    if (isRecordingSentAudio) stopRecordingSentAudio()
                    if (isRecordingReceivedAudio) stopRecordingReceivedAudio()
                    if (isPlayingInputFile) stopPlayingInputAudioFile()
                    if (isPlayingOutputFile) stopPlayingOutputAudioFile()

                    // NUEVO: Detener traducción
                    if (isRecordingForTranslation) {
                        stopRecordingForTranslation()
                    }
                }

                else -> {
                    log.d(TAG) { "Other connection state: $state" }
                }
            }
            webRtcEventListener?.onConnectionStateChange(mapConnectionState(state))
        }.launchIn(coroutineScope)

        onTrack.onEach { event ->
            log.d(TAG) { "Remote track received: $event" }
            val track = event.receiver.track

            if (track is AudioStreamTrack) {
                log.d(TAG) { "Remote audio track established" }
                remoteAudioTrack = track
                remoteAudioTrack?.enabled = true

                webRtcEventListener?.onRemoteAudioTrack()
            }
        }.launchIn(coroutineScope)
    }

    override fun isAudioTranslationEnabled(): Boolean = isTranslationEnabled

    override fun getCurrentTargetLanguage(): String? = currentTargetLanguage

    override fun setTargetLanguage(targetLanguage: String): Boolean {
        if (!isTranslationEnabled || openAIManager == null) {
            log.w(TAG) { "Translation not enabled, cannot set target language" }
            return false
        }

        return try {
            if (openAIManager?.enable(targetLanguage) == true) {
                currentTargetLanguage = targetLanguage
                log.d(TAG) { "Target language set to: $targetLanguage" }
                true
            } else {
                log.e(TAG) { "Failed to set target language: $targetLanguage" }
                false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error setting target language: ${e.message}" }
            false
        }
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

    override fun getTranslationStats(): WebRtcManager.TranslationStats? {
        return openAIManager?.getStats()
    }

    override fun setTranslationQuality(quality: WebRtcManager.TranslationQuality): Boolean {
        translationQuality = quality

        return if (openAIManager != null) {
            val result = openAIManager?.setTranslationQuality(quality) == true
            if (result) {
                webRtcEventListener?.onTranslationQualityChanged(quality)
            }
            result
        } else {
            true // Guardar para cuando se habilite la traducción
        }
    }

    /**
     * Ensures the local audio track is created and added to the PeerConnection.
     * Returns true if successful, false otherwise.
     */
    private suspend fun ensureLocalAudioTrack(): Boolean {
        return try {
            val peerConn = peerConnection ?: run {
                log.d(TAG) { "PeerConnection not initialized" }
                return false
            }

            // Check if we already have a track
            if (localAudioTrack != null) {
                log.d(TAG) { "Local audio track already exists" }
                return true
            }

            // Make sure audio mode is set for communication
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isMicrophoneMute = false

            log.d(TAG) { "Getting local audio stream..." }

            val mediaStream = MediaDevices.getUserMedia(
                audio = true,
                video = false
            )

            val audioTrack = mediaStream.audioTracks.firstOrNull()
            if (audioTrack != null) {
                log.d(TAG) { "Audio track obtained successfully!" }

                localAudioTrack = audioTrack
                localAudioTrack?.enabled = true

                peerConn.addTrack(audioTrack, mediaStream)

                log.d(TAG) { "Audio track added successfully: ${audioTrack.label}" }

                // Additional troubleshooting for audio routing
                val outputDevice = when {
                    audioManager?.isBluetoothScoOn == true -> "Bluetooth SCO"
                    audioManager?.isBluetoothA2dpOn == true -> "Bluetooth A2DP"
                    audioManager?.isSpeakerphoneOn == true -> "Speakerphone"
                    audioManager?.isWiredHeadsetOn == true -> "Wired Headset"
                    else -> "Earpiece/Default"
                }

                log.d(TAG) { "Current audio output device: $outputDevice" }

                true
            } else {
                log.d(TAG) { "Error: No audio track found" }
                false
            }
        } catch (e: Exception) {
            log.d(TAG) { "Error getting audio: ${e.message}" }
            false
        }
    }

    /**
     * Cleans up call resources
     */
    private fun cleanupCall() {
        try {
            // First, stop any active media operations
            localAudioTrack?.enabled = false

            // Remove tracks from peer connection
            peerConnection?.let { pc ->
                pc.getSenders().forEach { sender ->
                    try {
                        pc.removeTrack(sender)
                    } catch (e: Exception) {
                        log.d(TAG) { "Error removing track: ${e.message}" }
                    }
                }
            }

            // Close peer connection first
            peerConnection?.close()
            peerConnection = null

            // Wait a short moment to ensure connections are closed
            Thread.sleep(100)

            // Dispose of media resources
            localAudioTrack = null
            remoteAudioTrack = null
            isLocalAudioReady = false

            // Force garbage collection to ensure native objects are released
            System.gc()

        } catch (e: Exception) {
            log.d(TAG) { "Error in cleanupCall: ${e.message}" }
        }
    }
}
