package com.eddyslarez.siplibrary.data.services.realTime

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.eddyslarez.siplibrary.data.services.audio.WebRtcManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Manager for OpenAI Realtime API integration for audio translation
 * CORREGIDO: Buffer validation y manejo de estado para evitar errores
 *
 * @author Eddys Larez
 */
class OpenAIRealtimeManager(
    private val apiKey: String,
    private val model: String = "gpt-4o-realtime-preview-2025-06-03"
) {
    private val TAG = "OpenAIRealtimeManager"

    // WebSocket client
    private var webSocket: WebSocket? = null
    private var httpClient: OkHttpClient? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Configuration
    private var targetLanguage: String = "es" // Default to Spanish
    private var translationQuality: WebRtcManager.TranslationQuality =
        WebRtcManager.TranslationQuality.MEDIUM
    private var isEnabled = AtomicBoolean(false)
    private var isProcessing = AtomicBoolean(false)

    // CORREGIDO: Controlar estado de procesamiento para evitar requests concurrentes
    private var isWaitingForResponse = AtomicBoolean(false)
    private var lastRequestTime = AtomicLong(0)
    private val minRequestInterval = 2000L // Mínimo 2 segundos entre requests

    // Statistics
    private val totalTranslations = AtomicInteger(0)
    private val totalLatency = AtomicLong(0)
    private val successfulTranslations = AtomicInteger(0)
    private val lastTranslationTime = AtomicLong(0)
    private var onAudioCallback: ((ByteArray) -> Unit)? = null

    // Audio processing - CORREGIDO: Buffer más robusto
    private var audioQueue = mutableListOf<ByteArray>()
    private var processJob: Job? = null
    private var sessionId: String? = null
    private var pendingTranslations = mutableMapOf<String, TranslationRequest>()

    // NUEVO: Buffer de audio acumulativo con validación de tamaño
    private var audioBuffer = ByteArrayOutputStream()
    private val minAudioSizeBytes = 3200 // ~100ms de audio PCM 16-bit 16kHz mono
    private val maxAudioSizeBytes = 32000 // ~1 segundo máximo

    // Callbacks
    private var translationListener: TranslationListener? = null

    // Supported languages
    private val supportedLanguages = listOf(
        "es", "en", "fr", "de", "it", "pt", "ru", "ja", "ko", "zh",
        "ar", "hi", "th", "vi", "tr", "pl", "nl", "sv", "da", "no"
    )

    interface TranslationListener {
        fun onTranslationCompleted(
            originalAudio: ByteArray,
            translatedAudio: ByteArray,
            latency: Long
        )

        fun onTranslationFailed(error: String)
        fun onTranslationStateChanged(isEnabled: Boolean, targetLanguage: String?)
        fun onProcessingStateChanged(isProcessing: Boolean)
    }

    private data class TranslationRequest(
        val id: String,
        val originalAudio: ByteArray,
        val startTime: Long,
        val targetLanguage: String
    )

    /**
     * Initialize the OpenAI Realtime connection
     */
    fun initialize(): Boolean {
        return try {
            httpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            connectToOpenAI()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OpenAI Realtime Manager", e)
            false
        }
    }

    /**
     * Connect to OpenAI Realtime API
     */
    private fun connectToOpenAI(): Boolean {
        return try {
            val request = Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=$model")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build()

            webSocket = httpClient?.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Connected to OpenAI Realtime API")
                    initializeSession()
                }

                @RequiresApi(Build.VERSION_CODES.O)
                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleRealtimeMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleRealtimeAudio(bytes.toByteArray())
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "OpenAI Realtime connection closed: $reason")
                    isWaitingForResponse.set(false)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "OpenAI Realtime connection failed", t)
                    isWaitingForResponse.set(false)
                    translationListener?.onTranslationFailed("Connection failed: ${t.message}")
                }
            })

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to OpenAI", e)
            false
        }
    }

    /**
     * CORREGIDO: Initialize session con configuración optimizada
     */
    private fun initializeSession() {
        sessionId = "session_${System.currentTimeMillis()}_${Random.nextInt(1000)}"

        val sessionConfig = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("modalities", org.json.JSONArray().apply {
                    put("text")
                    put("audio")
                })
                put("instructions", """
                    You are a real-time audio translator. When you receive audio input, translate it to $targetLanguage and respond ONLY with the translation in audio format.
                    
                    CRITICAL RULES:
                    - ONLY translate what you hear
                    - Do NOT add explanations or comments
                    - Do NOT say "I will translate" or similar
                    - Respond immediately with just the translation
                    - If you hear nothing meaningful, stay silent
                    - Keep the same tone and emotion
                    
                    Target language: $targetLanguage
                """.trimIndent())
                put("voice", "alloy")
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")

                // CORREGIDO: Configuración más estricta para evitar false positives
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.7f) // Más estricto
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 1000) // Más tiempo para asegurar fin del habla
                })
                put("tool_choice", "none")
                put("temperature", 0.6)
                put("max_response_output_tokens", 1000)
            })
        }

        webSocket?.send(sessionConfig.toString())
    }

    /**
     * CORREGIDO: Handle realtime messages with better state management
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleRealtimeMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.getString("type")

            when (type) {
                "session.created" -> {
                    Log.d(TAG, "Session created successfully")
                    isEnabled.set(true)
                    isWaitingForResponse.set(false)
                    translationListener?.onTranslationStateChanged(true, targetLanguage)
                }

                "session.updated" -> {
                    Log.d(TAG, "Session updated successfully")
                }

                "conversation.item.created" -> {
                    Log.d(TAG, "Conversation item created")
                }

                "response.created" -> {
                    Log.d(TAG, "Response created")
                    isProcessing.set(true)
                    translationListener?.onProcessingStateChanged(true)
                }

                "response.done" -> {
                    Log.d(TAG, "Response completed")
                    isProcessing.set(false)
                    isWaitingForResponse.set(false) // CORREGIDO: Liberar lock
                    translationListener?.onProcessingStateChanged(false)
                }

                "response.audio.delta" -> {
                    if (json.has("delta")) {
                        val audioData = json.getString("delta")
                        handleTranslatedAudioChunk(audioData)
                    }
                }

                "response.audio.done" -> {
                    Log.d(TAG, "Audio response completed")
                    handleTranslationCompleted()
                }

                "error" -> {
                    val error = json.optJSONObject("error")
                    val errorMessage = error?.optString("message") ?: "Unknown error"
                    Log.e(TAG, "OpenAI API error: $errorMessage")
                    isWaitingForResponse.set(false) // CORREGIDO: Liberar lock en error
                    translationListener?.onTranslationFailed(errorMessage)
                }

                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "Speech detected in input audio")
                }

                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "Speech stopped in input audio")
                }

                else -> {
                    Log.d(TAG, "Received message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling realtime message", e)
        }
    }

    /**
     * Handle translated audio chunks
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleTranslatedAudioChunk(audioData: String) {
        try {
            val audioBytes = Base64.getDecoder().decode(audioData)
            Log.d(TAG, "Received translated audio chunk: ${audioBytes.size} bytes")

            synchronized(audioQueue) {
                audioQueue.add(audioBytes)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling translated audio chunk", e)
        }
    }

    /**
     * Handle translation completion
     */
    private fun handleTranslationCompleted() {
        try {
            // Combine all audio chunks
            val totalSize = audioQueue.sumOf { it.size }
            val combinedAudio = ByteArray(totalSize)
            var offset = 0

            synchronized(audioQueue) {
                audioQueue.forEach { chunk ->
                    System.arraycopy(chunk, 0, combinedAudio, offset, chunk.size)
                    offset += chunk.size
                }
                audioQueue.clear()
            }

            // Calculate latency and update statistics
            val currentTime = System.currentTimeMillis()
            val latency = currentTime - lastTranslationTime.get()

            totalTranslations.incrementAndGet()
            totalLatency.addAndGet(latency)
            successfulTranslations.incrementAndGet()
            lastTranslationTime.set(currentTime)

            // Notify listener
            translationListener?.onTranslationCompleted(ByteArray(0), combinedAudio, latency)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling translation completion", e)
            translationListener?.onTranslationFailed("Failed to process translated audio: ${e.message}")
        }
    }

    /**
     * Handle incoming audio data from WebRTC
     */
    private fun handleRealtimeAudio(audioData: ByteArray) {
        Log.d(TAG, "Received audio data for translation: ${audioData.size} bytes")
    }

    /**
     * CORREGIDO: Process audio with proper validation and state checking
     */
    fun processAudioForTranslation(audioData: ByteArray): Boolean {
        if (!isEnabled.get() || webSocket == null) {
            Log.w(TAG, "Translation not enabled or not connected")
            return false
        }

        if (audioData.isEmpty()) {
            Log.w(TAG, "Empty audio data received")
            return false
        }

        // CORREGIDO: Verificar si ya hay un request en progreso
        if (isWaitingForResponse.get()) {
            Log.d(TAG, "Already processing a translation request, buffering audio")
            synchronized(audioBuffer) {
                audioBuffer.write(audioData)
            }
            return true
        }

        // CORREGIDO: Verificar intervalo mínimo entre requests
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRequestTime.get() < minRequestInterval) {
            Log.d(TAG, "Too soon for next request, buffering audio")
            synchronized(audioBuffer) {
                audioBuffer.write(audioData)
            }
            return true
        }

        return try {
            // CORREGIDO: Combinar audio buffereado con el nuevo
            synchronized(audioBuffer) {
                audioBuffer.write(audioData)
                val combinedAudio = audioBuffer.toByteArray()

                // CORREGIDO: Validar tamaño mínimo de audio
                if (combinedAudio.size < minAudioSizeBytes) {
                    Log.d(TAG, "Audio buffer too small: ${combinedAudio.size} bytes, need at least $minAudioSizeBytes")
                    return true // Mantener en buffer
                }

                // CORREGIDO: Limitar tamaño máximo
                val finalAudio = if (combinedAudio.size > maxAudioSizeBytes) {
                    Log.d(TAG, "Audio buffer too large, trimming to ${maxAudioSizeBytes} bytes")
                    combinedAudio.copyOfRange(combinedAudio.size - maxAudioSizeBytes, combinedAudio.size)
                } else {
                    combinedAudio
                }

                // Limpiar buffer después de usar
                audioBuffer.reset()

                // Procesar el audio
                return sendAudioToOpenAI(finalAudio)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio for translation", e)
            synchronized(audioBuffer) {
                audioBuffer.reset()
            }
            false
        }
    }

    /**
     * NUEVO: Enviar audio a OpenAI con manejo de estado
     */
    private fun sendAudioToOpenAI(audioData: ByteArray): Boolean {
        return try {
            isWaitingForResponse.set(true)
            lastRequestTime.set(System.currentTimeMillis())
            lastTranslationTime.set(System.currentTimeMillis())

            Log.d(TAG, "Sending ${audioData.size} bytes of audio for translation")

            val audioBase64 = android.util.Base64.encodeToString(audioData, android.util.Base64.NO_WRAP)

            // Limpiar buffer de audio de entrada
            val clearBuffer = JSONObject().apply {
                put("type", "input_audio_buffer.clear")
            }
            webSocket?.send(clearBuffer.toString())

            // Enviar audio
            val audioInput = JSONObject().apply {
                put("type", "input_audio_buffer.append")
                put("audio", audioBase64)
            }
            webSocket?.send(audioInput.toString())

            // Commit audio
            val commitAudio = JSONObject().apply {
                put("type", "input_audio_buffer.commit")
            }
            webSocket?.send(commitAudio.toString())

            // Crear respuesta
            val responseRequest = JSONObject().apply {
                put("type", "response.create")
                put("response", JSONObject().apply {
                    put("modalities", org.json.JSONArray().apply {
                        put("text")
                        put("audio")
                    })
                    put("instructions", "Translate immediately to $targetLanguage")
                })
            }
            webSocket?.send(responseRequest.toString())

            Log.d(TAG, "Audio sent to OpenAI for translation successfully")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio to OpenAI", e)
            isWaitingForResponse.set(false)
            false
        }
    }

    /**
     * Enable translation
     */
    fun enable(targetLanguage: String): Boolean {
        if (!supportedLanguages.contains(targetLanguage)) {
            Log.e(TAG, "Unsupported language: $targetLanguage")
            return false
        }

        this.targetLanguage = targetLanguage

        if (webSocket == null) {
            return initialize()
        }

        updateSessionLanguage(targetLanguage)
        return true
    }

    /**
     * Disable translation
     */
    fun disable(): Boolean {
        isEnabled.set(false)
        isWaitingForResponse.set(false)

        webSocket?.close(1000, "Translation disabled")
        webSocket = null

        // Limpiar buffer
        synchronized(audioBuffer) {
            audioBuffer.reset()
        }

        translationListener?.onTranslationStateChanged(false, null)
        return true
    }

    /**
     * Update session language
     */
    private fun updateSessionLanguage(language: String) {
        val sessionUpdate = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("instructions", """
                    You are a professional real-time audio translator. 
                    Your ONLY job is to translate spoken audio to $targetLanguage.
                    
                    Rules:
                    1. Translate EXACTLY what is said, nothing more, nothing less
                    2. Do NOT add greetings, comments, or explanations
                    3. Do NOT say "I will translate" or similar phrases
                    4. Do NOT add context or interpretations
                    5. Maintain the original tone and emotion
                    6. Translate in real-time as fast as possible
                    7. Use natural, fluent speech in the target language
                    8. If you hear silence, respond with silence
                    9. If unclear, translate your best approximation
                    10. NEVER break character as a translator
                    
                    Simply translate the audio content directly to $targetLanguage.
                """.trimIndent())
            })
        }

        webSocket?.send(sessionUpdate.toString())
    }

    /**
     * Set translation listener
     */
    fun setTranslationListener(listener: TranslationListener?) {
        this.translationListener = listener
    }

    /**
     * Get translation statistics
     */
    fun getStats(): WebRtcManager.TranslationStats {
        val total = totalTranslations.get()
        val avgLatency = if (total > 0) totalLatency.get() / total else 0L
        val successRate = if (total > 0) successfulTranslations.get().toFloat() / total else 0f

        return WebRtcManager.TranslationStats(
            totalTranslations = total,
            averageLatency = avgLatency,
            successRate = successRate,
            lastTranslationTime = lastTranslationTime.get()
        )
    }

    /**
     * Check if translation is enabled
     */
    fun isEnabled(): Boolean = isEnabled.get()

    /**
     * Check if currently processing
     */
    fun isProcessing(): Boolean = isProcessing.get()

    /**
     * Get current target language
     */
    fun getCurrentTargetLanguage(): String = targetLanguage

    /**
     * Get supported languages
     */
    fun getSupportedLanguages(): List<String> = supportedLanguages.toList()

    /**
     * Set translation quality
     */
    fun setTranslationQuality(quality: WebRtcManager.TranslationQuality): Boolean {
        this.translationQuality = quality

        val temperature = when (quality) {
            WebRtcManager.TranslationQuality.LOW -> 0.6
            WebRtcManager.TranslationQuality.MEDIUM -> 0.8
            WebRtcManager.TranslationQuality.HIGH -> 1.0
        }

        val sessionUpdate = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("temperature", temperature)
            })
        }

        webSocket?.send(sessionUpdate.toString())
        return true
    }

    /**
     * Get current translation quality
     */
    fun getTranslationQuality(): WebRtcManager.TranslationQuality = translationQuality

    /**
     * Dispose resources
     */
    fun dispose() {
        processJob?.cancel()
        webSocket?.close(1000, "Manager disposed")
        webSocket = null
        httpClient = null
        scope.cancel()

        isEnabled.set(false)
        isProcessing.set(false)
        isWaitingForResponse.set(false)

        synchronized(audioQueue) {
            audioQueue.clear()
        }

        synchronized(audioBuffer) {
            audioBuffer.reset()
        }

        pendingTranslations.clear()
    }
}