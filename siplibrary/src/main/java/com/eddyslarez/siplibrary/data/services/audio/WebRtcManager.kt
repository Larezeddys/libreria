package com.eddyslarez.siplibrary.data.services.audio

import com.eddyslarez.siplibrary.data.models.AccountInfo
import java.io.File

/**
 * Interface for managing WebRTC functionality across platforms
 *
 * @author Eddys Larez
 */
interface WebRtcManager {
    /**
     * Initialize the WebRTC subsystem
     */
    fun initialize()

    /**
     * Clean up and release WebRTC resources
     */
    fun dispose()

    /**
     * Create an SDP offer for starting a call
     * @return The SDP offer string
     */
    suspend fun createOffer(): String

    /**
     * Create an SDP answer in response to an offer
     * @param accountInfo The current account information
     * @param offerSdp The SDP offer from the remote party
     * @return The SDP answer string
     */
    suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String

    /**
     * Set the remote description (offer or answer)
     * @param sdp The remote SDP string
     * @param type The SDP type (offer or answer)
     */
    suspend fun setRemoteDescription(sdp: String, type: SdpType)

    /**
     * Add an ICE candidate received from the remote party
     * @param candidate The ICE candidate string
     * @param sdpMid The media ID
     * @param sdpMLineIndex The media line index
     */
    suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?)

    fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>>

    fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean
    fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean
    fun getCurrentInputDevice(): AudioDevice?
    fun getCurrentOutputDevice(): AudioDevice?

    /**
     * Enable or disable the local audio track
     * @param enabled Whether audio should be enabled
     */
    fun setAudioEnabled(enabled: Boolean)

    fun setMuted(muted: Boolean)
    fun isMuted(): Boolean
    fun getLocalDescription(): String?
    fun diagnoseAudioIssues(): String

    /**
     * Get current connection state
     * @return The connection state
     */
    fun getConnectionState(): WebRtcConnectionState

    suspend fun setMediaDirection(direction: MediaDirection)
    enum class MediaDirection { SENDRECV, SENDONLY, RECVONLY, INACTIVE }

    /**
     * Set a listener for WebRTC events
     * @param listener The WebRTC event listener
     */
    fun setListener(listener: Any?)
    fun prepareAudioForIncomingCall()
    suspend fun applyModifiedSdp(modifiedSdp: String): Boolean
    fun isInitialized(): Boolean

    /**
     * Send DTMF tones via RTP (RFC 2833)
     * @param tones The DTMF tones to send (0-9, *, #, A-D)
     * @param duration Duration in milliseconds for each tone (optional, default 100ms)
     * @param gap Gap between tones in milliseconds (optional, default 70ms)
     * @return true if successfully started sending tones, false otherwise
     */
    fun sendDtmfTones(tones: String, duration: Int = 100, gap: Int = 70): Boolean

    // ========== FUNCIONES DE GRABACIÓN Y REPRODUCCIÓN ==========

    /**
     * Start recording sent audio (microphone input) to WAV file
     * @return true if recording started successfully, false otherwise
     */
    fun startRecordingSentAudio(): Boolean

    /**
     * Stop recording sent audio
     * @return The file path of the recorded audio, or null if not recording
     */
    fun stopRecordingSentAudio(): String?

    /**
     * Start recording received audio (remote party audio) to WAV file
     * @return true if recording started successfully, false otherwise
     */
    fun startRecordingReceivedAudio(): Boolean

    /**
     * Stop recording received audio
     * @return The file path of the recorded audio, or null if not recording
     */
    fun stopRecordingReceivedAudio(): String?

    /**
     * Start playing audio file instead of microphone input
     * @param filePath Path to the audio file to play
     * @param loop Whether to loop the audio file continuously
     * @return true if playback started successfully, false otherwise
     */
    fun startPlayingInputAudioFile(filePath: String, loop: Boolean = false): Boolean

    /**
     * Stop playing audio file and return to microphone input
     * @return true if stopped successfully, false otherwise
     */
    fun stopPlayingInputAudioFile(): Boolean

    /**
     * Start playing audio file instead of received audio
     * @param filePath Path to the audio file to play
     * @param loop Whether to loop the audio file continuously
     * @return true if playback started successfully, false otherwise
     */
    fun startPlayingOutputAudioFile(filePath: String, loop: Boolean = false): Boolean

    /**
     * Stop playing audio file and return to received audio
     * @return true if stopped successfully, false otherwise
     */
    fun stopPlayingOutputAudioFile(): Boolean

    /**
     * Get list of recorded audio files
     * @return List of recorded audio files
     */
    fun getRecordedAudioFiles(): List<File>

    /**
     * Delete a recorded audio file
     * @param filePath Path to the file to delete
     * @return true if deleted successfully, false otherwise
     */
    fun deleteRecordedAudioFile(filePath: String): Boolean

    /**
     * Get duration of an audio file in milliseconds
     * @param filePath Path to the audio file
     * @return Duration in milliseconds, or 0 if error
     */
    fun getAudioFileDuration(filePath: String): Long

    /**
     * Check if currently recording sent audio
     * @return true if recording, false otherwise
     */
    fun isRecordingSentAudio(): Boolean

    /**
     * Check if currently recording received audio
     * @return true if recording, false otherwise
     */
    fun isRecordingReceivedAudio(): Boolean

    /**
     * Check if currently playing input audio file
     * @return true if playing, false otherwise
     */
    fun isPlayingInputAudioFile(): Boolean

    /**
     * Check if currently playing output audio file
     * @return true if playing, false otherwise
     */
    fun isPlayingOutputAudioFile(): Boolean

    /**
     * Get current input audio file path being played
     * @return File path or null if not playing
     */
    fun getCurrentInputAudioFilePath(): String?

    /**
     * Get current output audio file path being played
     * @return File path or null if not playing
     */
    fun getCurrentOutputAudioFilePath(): String?

    // ========== FUNCIONES DE IA PARA TRADUCCIÓN DE AUDIO ==========

    /**
     * Enable AI audio translation for incoming audio
     * @param apiKey OpenAI API key
     * @param targetLanguage Target language for translation (e.g., "es" for Spanish)
     * @param model OpenAI model to use (default: "gpt-4o-realtime-preview-2024-12-17")
     * @return true if enabled successfully, false otherwise
     */
    fun enableAudioTranslation(apiKey: String, targetLanguage: String, model: String = "gpt-4o-realtime-preview-2024-12-17"): Boolean

    /**
     * Disable AI audio translation
     * @return true if disabled successfully, false otherwise
     */
    fun disableAudioTranslation(): Boolean

    /**
     * Check if AI audio translation is enabled
     * @return true if enabled, false otherwise
     */
    fun isAudioTranslationEnabled(): Boolean

    /**
     * Get current target language for translation
     * @return Target language code or null if not set
     */
    fun getCurrentTargetLanguage(): String?

    /**
     * Set target language for translation
     * @param targetLanguage Target language code (e.g., "es" for Spanish)
     * @return true if set successfully, false otherwise
     */
    fun setTargetLanguage(targetLanguage: String): Boolean

    /**
     * Get supported languages for translation
     * @return List of supported language codes
     */
    fun getSupportedLanguages(): List<String>

    /**
     * Check if AI translation is currently processing audio
     * @return true if processing, false otherwise
     */
    fun isTranslationProcessing(): Boolean

    /**
     * Get translation processing statistics
     * @return Translation statistics or null if not available
     */
    fun getTranslationStats(): TranslationStats?

    /**
     * Set translation quality (affects processing speed vs quality)
     * @param quality Translation quality (LOW, MEDIUM, HIGH)
     * @return true if set successfully, false otherwise
     */
    fun setTranslationQuality(quality: TranslationQuality): Boolean

    /**
     * Data class for translation statistics
     */
    data class TranslationStats(
        val totalTranslations: Int,
        val averageLatency: Long,
        val successRate: Float,
        val lastTranslationTime: Long
    )

    /**
     * Translation quality enum
     */
    enum class TranslationQuality {
        LOW,    // Faster processing, lower quality
        MEDIUM, // Balanced processing
        HIGH    // Slower processing, higher quality
    }
}

/**
 * Session Description Protocol type
 */
enum class SdpType {
    OFFER,
    ANSWER
}

/**
 * WebRTC connection state
 */
enum class WebRtcConnectionState {
    NEW,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CLOSED
}

/**
 * Interface for WebRTC event callbacks
 */
interface WebRtcEventListener {
    /**
     * Called when a new ICE candidate is generated
     */
    fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int)

    /**
     * Called when the connection state changes
     */
    fun onConnectionStateChange(state: WebRtcConnectionState)

    /**
     * Called when an audio track is received from the remote peer
     */
    fun onRemoteAudioTrack()

    fun onAudioDeviceChanged(device: AudioDevice?)

    // ========== CALLBACKS DE GRABACIÓN Y REPRODUCCIÓN ==========

    /**
     * Called when recording starts/stops
     * @param isRecording true if started, false if stopped
     * @param filePath path of the recorded file (when stopped)
     * @param isSentAudio true for sent audio, false for received audio
     */
    fun onRecordingStateChanged(isRecording: Boolean, filePath: String?, isSentAudio: Boolean) {}

    /**
     * Called when audio file playback starts/stops
     * @param isPlaying true if started, false if stopped
     * @param filePath path of the audio file being played
     * @param isInputAudio true for input audio, false for output audio
     */
    fun onAudioFilePlaybackStateChanged(isPlaying: Boolean, filePath: String?, isInputAudio: Boolean) {}

    // ========== CALLBACKS DE IA PARA TRADUCCIÓN ==========

    /**
     * Called when AI translation state changes
     * @param isEnabled true if translation is enabled, false otherwise
     * @param targetLanguage current target language or null if disabled
     */
    fun onTranslationStateChanged(isEnabled: Boolean, targetLanguage: String?) {}

    /**
     * Called when translation processing starts/stops
     * @param isProcessing true if processing, false if idle
     * @param audioLength length of audio being processed in milliseconds
     */
    fun onTranslationProcessingChanged(isProcessing: Boolean, audioLength: Long = 0) {}

    /**
     * Called when translation is completed
     * @param success true if translation was successful, false otherwise
     * @param latency processing latency in milliseconds
     * @param originalLanguage detected original language
     * @param error error message if translation failed
     */
    fun onTranslationCompleted(success: Boolean, latency: Long, originalLanguage: String?, error: String?) {}

    /**
     * Called when translation quality changes
     * @param quality new translation quality setting
     */
    fun onTranslationQualityChanged(quality: WebRtcManager.TranslationQuality) {}
}
