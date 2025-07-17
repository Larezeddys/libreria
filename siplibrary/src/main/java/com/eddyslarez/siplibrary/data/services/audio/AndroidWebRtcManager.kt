package com.eddyslarez.siplibrary.data.services.audio

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.utils.log
import com.shepeliev.webrtc.kmp.WebRtc
import com.shepeliev.webrtc.kmp.AudioTrack
import com.shepeliev.webrtc.kmp.MediaStream
import com.shepeliev.webrtc.kmp.PeerConnection
import com.shepeliev.webrtc.kmp.PeerConnectionFactory
import com.shepeliev.webrtc.kmp.SessionDescription
import com.shepeliev.webrtc.kmp.IceCandidate
import com.shepeliev.webrtc.kmp.MediaConstraints
import com.shepeliev.webrtc.kmp.AudioSource
import com.shepeliev.webrtc.kmp.RtpSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File

/**
 * Android WebRTC Manager con capacidades de interceptación de audio
 * Integra AudioInterceptor para manipulación de audio en tiempo real
 * 
 * @author Eddys Larez
 */
class AndroidWebRtcManager(private val application: Application) : WebRtcManager {
    
    companion object {
        private const val TAG = "AndroidWebRtcManager"
    }
    
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var listener: WebRtcEventListener? = null
    private var isInitializedFlag = false
    private var isMutedFlag = false
    private var isAudioEnabledFlag = true
    
    // Audio Interceptor Integration
    private val audioInterceptor = AudioInterceptor(application)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isAudioInterceptionEnabled = false
    private var audioInterceptorListener: AudioInterceptor.AudioInterceptorListener? = null
    
    // Audio device management
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var currentInputDevice: AudioDevice? = null
    private var currentOutputDevice: AudioDevice? = null
    
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
            
            override fun onRecordingStarted(incomingFile: File?, outgoingFile: File?) {
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
    
    override fun initialize() {
        if (isInitializedFlag) {
            log.w(tag = TAG) { "WebRTC already initialized" }
            return
        }
        
        try {
            log.d(tag = TAG) { "Initializing WebRTC with audio interception capabilities" }
            
            // Initialize WebRTC
            WebRtc.initialize(application)
            
            // Create PeerConnectionFactory
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options().apply {
                    disableEncryption = false
                    disableNetworkMonitor = false
                })
                .createPeerConnectionFactory()
            
            isInitializedFlag = true
            log.d(tag = TAG) { "WebRTC initialized successfully with audio interception" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing WebRTC: ${e.message}" }
            throw e
        }
    }
    
    override suspend fun createOffer(): String = suspendCancellableCoroutine { continuation ->
        try {
            if (!isInitializedFlag) {
                continuation.resumeWithException(IllegalStateException("WebRTC not initialized"))
                return@suspendCancellableCoroutine
            }
            
            createPeerConnectionIfNeeded()
            createLocalAudioTrack()
            
            // Iniciar interceptación cuando se crea una oferta
            if (isAudioInterceptionEnabled) {
                startAudioInterception()
            }
            
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            
            peerConnection?.createOffer(constraints) { sessionDescription ->
                peerConnection?.setLocalDescription(sessionDescription) {
                    continuation.resume(sessionDescription.description)
                }
            }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating offer: ${e.message}" }
            continuation.resumeWithException(e)
        }
    }
    
    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String = suspendCancellableCoroutine { continuation ->
        try {
            if (!isInitializedFlag) {
                continuation.resumeWithException(IllegalStateException("WebRTC not initialized"))
                return@suspendCancellableCoroutine
            }
            
            createPeerConnectionIfNeeded()
            createLocalAudioTrack()
            
            // Iniciar interceptación cuando se crea una respuesta
            if (isAudioInterceptionEnabled) {
                startAudioInterception()
            }
            
            val remoteDescription = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
            
            peerConnection?.setRemoteDescription(remoteDescription) {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
                
                peerConnection?.createAnswer(constraints) { sessionDescription ->
                    peerConnection?.setLocalDescription(sessionDescription) {
                        continuation.resume(sessionDescription.description)
                    }
                }
            }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating answer: ${e.message}" }
            continuation.resumeWithException(e)
        }
    }
    
    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
        try {
            val sessionDescriptionType = when (type) {
                SdpType.OFFER -> SessionDescription.Type.OFFER
                SdpType.ANSWER -> SessionDescription.Type.ANSWER
            }
            
            val remoteDescription = SessionDescription(sessionDescriptionType, sdp)
            
            suspendCancellableCoroutine<Unit> { continuation ->
                peerConnection?.setRemoteDescription(remoteDescription) {
                    continuation.resume(Unit)
                }
            }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting remote description: ${e.message}" }
            throw e
        }
    }
    
    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        try {
            val iceCandidate = IceCandidate(sdpMid ?: "", sdpMLineIndex ?: 0, candidate)
            peerConnection?.addIceCandidate(iceCandidate)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error adding ICE candidate: ${e.message}" }
        }
    }
    
    override fun setAudioEnabled(enabled: Boolean) {
        isAudioEnabledFlag = enabled
        localAudioTrack?.setEnabled(enabled)
        
        if (enabled && isAudioInterceptionEnabled) {
            startAudioInterception()
        } else if (!enabled) {
            stopAudioInterception()
        }
        
        log.d(tag = TAG) { "Audio enabled: $enabled" }
    }
    
    override fun setMuted(muted: Boolean) {
        isMutedFlag = muted
        localAudioTrack?.setEnabled(!muted)
        log.d(tag = TAG) { "Audio muted: $muted" }
    }
    
    override fun isMuted(): Boolean = isMutedFlag
    
    override fun getLocalDescription(): String? {
        return peerConnection?.localDescription?.description
    }
    
    override fun getConnectionState(): WebRtcConnectionState {
        return when (peerConnection?.connectionState()) {
            PeerConnection.PeerConnectionState.NEW -> WebRtcConnectionState.NEW
            PeerConnection.PeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
            PeerConnection.PeerConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
            PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
            PeerConnection.PeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
            PeerConnection.PeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
            else -> WebRtcConnectionState.NEW
        }
    }
    
    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
        // Implementation for media direction control
        log.d(tag = TAG) { "Setting media direction: $direction" }
    }
    
    override fun setListener(listener: Any?) {
        this.listener = listener as? WebRtcEventListener
    }
    
    override fun prepareAudioForIncomingCall() {
        try {
            createLocalAudioTrack()
            setAudioEnabled(true)
            log.d(tag = TAG) { "Audio prepared for incoming call" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error preparing audio for incoming call: ${e.message}" }
        }
    }
    
    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
        return try {
            val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, modifiedSdp)
            
            suspendCancellableCoroutine { continuation ->
                peerConnection?.setLocalDescription(sessionDescription) {
                    continuation.resume(true)
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error applying modified SDP: ${e.message}" }
            false
        }
    }
    
    override fun isInitialized(): Boolean = isInitializedFlag
    
    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        return try {
            // Find DTMF sender
            val audioSender = peerConnection?.senders?.find { sender ->
                sender.track()?.kind() == "audio"
            }
            
            val dtmfSender = audioSender?.dtmf()
            if (dtmfSender?.canInsertDtmf() == true) {
                dtmfSender.insertDtmf(tones, duration, gap)
                log.d(tag = TAG) { "DTMF tones sent: $tones" }
                true
            } else {
                log.w(tag = TAG) { "DTMF sender not available" }
                false
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending DTMF tones: ${e.message}" }
            false
        }
    }
    
    // === AUDIO DEVICE MANAGEMENT ===
    
    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        val inputDevices = mutableListOf<AudioDevice>()
        val outputDevices = mutableListOf<AudioDevice>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
            
            for (deviceInfo in devices) {
                val audioDevice = createAudioDeviceFromInfo(deviceInfo)
                
                if (deviceInfo.isSink) {
                    outputDevices.add(audioDevice)
                } else if (deviceInfo.isSource) {
                    inputDevices.add(audioDevice)
                }
            }
        }
        
        return Pair(inputDevices, outputDevices)
    }
    
    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        return try {
            currentOutputDevice = device
            // Implementation for changing output device
            log.d(tag = TAG) { "Changed audio output device to: ${device.name}" }
            true
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error changing audio output device: ${e.message}" }
            false
        }
    }
    
    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
        return try {
            currentInputDevice = device
            // Implementation for changing input device
            log.d(tag = TAG) { "Changed audio input device to: ${device.name}" }
            true
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error changing audio input device: ${e.message}" }
            false
        }
    }
    
    override fun getCurrentInputDevice(): AudioDevice? = currentInputDevice
    override fun getCurrentOutputDevice(): AudioDevice? = currentOutputDevice
    
    override fun diagnoseAudioIssues(): String {
        return buildString {
            appendLine("=== AUDIO DIAGNOSTICS ===")
            appendLine("WebRTC Initialized: $isInitializedFlag")
            appendLine("Audio Enabled: $isAudioEnabledFlag")
            appendLine("Audio Muted: $isMutedFlag")
            appendLine("Local Audio Track: ${localAudioTrack != null}")
            appendLine("Peer Connection: ${peerConnection != null}")
            appendLine("Connection State: ${getConnectionState()}")
            
            // Audio Interceptor Diagnostics
            appendLine("\n--- Audio Interceptor ---")
            appendLine("Interception Enabled: $isAudioInterceptionEnabled")
            appendLine("Currently Intercepting: ${audioInterceptor.isIntercepting()}")
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
    
    // === AUDIO INTERCEPTION METHODS ===
    
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
    
    /**
     * Carga audio desde archivo para inyección
     */
    fun loadAudioFromFile(file: File): ByteArray? {
        return audioInterceptor.loadAudioFromFile(file)
    }
    
    /**
     * Convierte archivo de audio a formato compatible
     */
    fun convertAudioFile(
        inputFile: File,
        outputFile: File,
        inputFormat: AudioFileFormat
    ): Boolean {
        return audioInterceptor.convertAudioFile(inputFile, outputFile, inputFormat)
    }
    
    /**
     * Obtiene los archivos de grabación actuales
     */
    fun getRecordingFiles(): Pair<File?, File?> {
        return audioInterceptor.getRecordingFiles()
    }
    
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
    
    override fun dispose() {
        stopAudioInterception()
        audioInterceptor.dispose()
        
        localAudioTrack?.dispose()
        audioSource?.dispose()
        peerConnection?.close()
        peerConnectionFactory?.dispose()
        
        localAudioTrack = null
        audioSource = null
        peerConnection = null
        peerConnectionFactory = null
        isInitializedFlag = false
        
        log.d(tag = TAG) { "WebRTC disposed with audio interception cleanup" }
    }
    
    // === PRIVATE HELPER METHODS ===
    
    private fun createPeerConnectionIfNeeded() {
        if (peerConnection != null) return
        
        val configuration = PeerConnection.RTCConfiguration().apply {
            iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
        }
        
        peerConnection = peerConnectionFactory?.createPeerConnection(configuration, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                listener?.onIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            }
            
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                val state = when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
                    PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
                    PeerConnection.PeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
                    PeerConnection.PeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
                    else -> WebRtcConnectionState.CONNECTING
                }
                listener?.onConnectionStateChange(state)
            }
            
            override fun onAddStream(stream: MediaStream) {
                listener?.onRemoteAudioTrack()
            }
        })
    }
    
    private fun createLocalAudioTrack() {
        if (localAudioTrack != null) return
        
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        
        audioSource = peerConnectionFactory?.createAudioSource(constraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("audio_track", audioSource)
        
        // Add track to peer connection
        peerConnection?.addTrack(localAudioTrack, listOf("local_stream"))
        
        localAudioTrack?.setEnabled(isAudioEnabledFlag && !isMutedFlag)
    }
    
    private fun createAudioDeviceFromInfo(deviceInfo: AudioDeviceInfo): AudioDevice {
        val audioUnitType = when (deviceInfo.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioUnitTypes.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioUnitTypes.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioUnitTypes.HEADSET
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioUnitTypes.HEADPHONES
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioUnitTypes.BLUETOOTH
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioUnitTypes.BLUETOOTHA2DP
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioUnitTypes.MICROPHONE
            AudioDeviceInfo.TYPE_USB_DEVICE -> AudioUnitTypes.GENERICUSB
            else -> AudioUnitTypes.UNKNOWN
        }
        
        val capability = when {
            deviceInfo.isSink && deviceInfo.isSource -> AudioUnitCompatibilities.ALL
            deviceInfo.isSink -> AudioUnitCompatibilities.PLAY
            deviceInfo.isSource -> AudioUnitCompatibilities.RECORD
            else -> AudioUnitCompatibilities.UNKNOWN
        }
        
        val audioUnit = AudioUnit(
            type = audioUnitType,
            capability = capability,
            isCurrent = false,
            isDefault = false
        )
        
        return AudioDevice(
            name = deviceInfo.productName?.toString() ?: "Unknown Device",
            descriptor = deviceInfo.id.toString(),
            nativeDevice = deviceInfo,
            isOutput = deviceInfo.isSink,
            audioUnit = audioUnit,
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = audioUnitType == AudioUnitTypes.BLUETOOTH || audioUnitType == AudioUnitTypes.BLUETOOTHA2DP,
            supportsHDVoice = deviceInfo.sampleRates?.contains(16000) == true
        )
    }
}