# 🎵 Audio Interceptor para EddysSipLibrary

## 📋 Descripción General

El **Audio Interceptor** es una extensión avanzada de la EddysSipLibrary que permite interceptar, manipular y grabar el audio en tiempo real durante las llamadas SIP/VoIP. Esta funcionalidad te permite:

- **Interceptar audio entrante y saliente** en tiempo real
- **Inyectar audio personalizado** en lugar del micrófono o audio recibido
- **Convertir formatos de audio** automáticamente para compatibilidad con PCMA/8000
- **Grabar audio original** de ambas direcciones
- **Procesar audio** con efectos como ganancia, filtros, etc.

## 🎯 Características Principales

### ✅ **Interceptación de Audio**
- 🎤 **Audio Saliente**: Captura audio del micrófono antes de enviarlo por WebRTC
- 🔊 **Audio Entrante**: Intercepta audio recibido antes de reproducirlo
- 🔄 **Procesamiento en Tiempo Real**: Manipulación de audio con latencia mínima
- 📊 **Formato PCMA/8000**: Optimizado para el codec `a=rtpmap:8 PCMA/8000`

### ✅ **Inyección de Audio Personalizado**
- 📁 **Desde Archivos**: Reproduce archivos de audio en lugar del micrófono
- 🎵 **Audio Dinámico**: Inyecta audio generado programáticamente
- 🔀 **Mezcla de Audio**: Combina audio del micrófono con audio personalizado
- ⏱️ **Control de Timing**: Sincronización precisa con el flujo de la llamada

### ✅ **Conversión de Formatos**
- 🔄 **WAV a PCM**: Convierte archivos WAV a formato PCM raw
- 📈 **Resampling**: Convierte sample rates a 8000 Hz
- 🎚️ **Conversión de Canales**: Estéreo a mono automáticamente
- 🔢 **Profundidad de Bits**: Convierte entre 8-bit y 16-bit
- 📦 **PCMA/A-law**: Conversión optimizada entre PCM y PCMA

### ✅ **Grabación y Almacenamiento**
- 💾 **Grabación Automática**: Guarda audio original de ambas direcciones
- 📂 **Organización de Archivos**: Estructura de carpetas organizada por fecha
- 🗂️ **Gestión de Almacenamiento**: Limpieza automática de archivos antiguos
- 📊 **Metadatos**: Información detallada de cada grabación

## 🚀 Instalación y Configuración

### 1. Dependencias Adicionales

Agrega estas dependencias en tu `build.gradle.kts`:

```kotlin
dependencies {
    // Dependencias existentes de la librería...
    
    // Para procesamiento de audio avanzado (opcional)
    implementation("androidx.media3:media3-common:1.2.0")
    implementation("androidx.media3:media3-decoder:1.2.0")
}
```

### 2. Permisos Adicionales

Agrega estos permisos en tu `AndroidManifest.xml`:

```xml
<!-- Permisos existentes... -->

<!-- Permisos adicionales para interceptación de audio -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" 
    tools:ignore="ScopedStorage" />

<!-- Permisos para audio de alta calidad -->
<uses-permission android:name="android.permission.CAPTURE_AUDIO_OUTPUT" 
    tools:ignore="ProtectedPermissions" />
```

### 3. Inicialización

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Configuración con interceptación de audio habilitada
        val config = EddysSipLibrary.SipConfig(
            defaultDomain = "tu-dominio.com",
            webSocketUrl = "wss://tu-servidor:puerto/",
            userAgent = "MiApp/1.0.0",
            enableLogs = true,
            enableAutoReconnect = true,
            pingIntervalMs = 30000L
        )
        
        // Inicializar con WebRTC Manager mejorado
        EddysSipLibrary.getInstance().initialize(this, config)
    }
}
```

## 📖 Uso Básico

### 1. Configurar el Interceptor de Audio

```kotlin
class CallActivity : ComponentActivity() {
    private lateinit var sipLibrary: EddysSipLibrary
    private lateinit var enhancedWebRtcManager: EnhancedWebRtcManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        sipLibrary = EddysSipLibrary.getInstance()
        
        // Obtener el manager mejorado (necesitarás modificar la librería para esto)
        enhancedWebRtcManager = sipLibrary.getEnhancedWebRtcManager()
        
        setupAudioInterceptor()
    }
    
    private fun setupAudioInterceptor() {
        // Configurar listener para eventos de audio
        enhancedWebRtcManager.setAudioInterceptorListener(object : AudioInterceptor.AudioInterceptorListener {
            override fun onIncomingAudioReceived(audioData: ByteArray, timestamp: Long) {
                // Audio recibido interceptado
                Log.d("AudioInterceptor", "Received ${audioData.size} bytes of incoming audio")
                
                // Aquí puedes procesar el audio recibido
                processIncomingAudio(audioData)
            }
            
            override fun onOutgoingAudioCaptured(audioData: ByteArray, timestamp: Long) {
                // Audio del micrófono interceptado
                Log.d("AudioInterceptor", "Captured ${audioData.size} bytes of outgoing audio")
                
                // Aquí puedes procesar el audio saliente
                processOutgoingAudio(audioData)
            }
            
            override fun onAudioProcessed(incomingProcessed: ByteArray?, outgoingProcessed: ByteArray?) {
                // Audio procesado listo para WebRTC
                Log.d("AudioInterceptor", "Audio processed and ready for transmission")
            }
            
            override fun onRecordingStarted(incomingFile: File?, outgoingFile: File?) {
                Log.d("AudioInterceptor", "Recording started:")
                Log.d("AudioInterceptor", "  Incoming: ${incomingFile?.absolutePath}")
                Log.d("AudioInterceptor", "  Outgoing: ${outgoingFile?.absolutePath}")
            }
            
            override fun onRecordingStopped() {
                Log.d("AudioInterceptor", "Recording stopped")
            }
            
            override fun onError(error: String) {
                Log.e("AudioInterceptor", "Error: $error")
            }
        })
        
        // Habilitar interceptación
        enhancedWebRtcManager.enableAudioInterception(true)
        
        // Habilitar grabación
        enhancedWebRtcManager.setAudioRecordingEnabled(true)
    }
}
```

### 2. Inyectar Audio Personalizado

```kotlin
class AudioInjectionExample {
    
    fun injectAudioFromFile() {
        // Cargar audio desde archivo
        val audioFile = File(context.filesDir, "mi_audio.wav")
        val audioData = enhancedWebRtcManager.loadAudioFromFile(audioFile)
        
        if (audioData != null) {
            // Habilitar audio personalizado para envío
            enhancedWebRtcManager.setCustomOutgoingAudioEnabled(true)
            
            // Inyectar audio (esto reemplazará el micrófono)
            enhancedWebRtcManager.injectOutgoingAudio(audioData)
            
            Log.d("AudioInjection", "Audio injected: ${audioData.size} bytes")
        }
    }
    
    fun injectCustomIncomingAudio() {
        // Generar audio sintético (ejemplo: tono de 440Hz)
        val sampleRate = 8000
        val duration = 1.0 // 1 segundo
        val frequency = 440.0 // La (A4)
        
        val audioData = generateSineWave(sampleRate, duration, frequency)
        
        // Habilitar audio personalizado para reproducción
        enhancedWebRtcManager.setCustomIncomingAudioEnabled(true)
        
        // Inyectar audio (esto reemplazará el audio recibido)
        enhancedWebRtcManager.injectIncomingAudio(audioData)
    }
    
    private fun generateSineWave(sampleRate: Int, duration: Double, frequency: Double): ByteArray {
        val numSamples = (sampleRate * duration).toInt()
        val audioData = ByteArray(numSamples * 2) // 16-bit = 2 bytes por sample
        
        for (i in 0 until numSamples) {
            val sample = (32767 * sin(2.0 * PI * frequency * i / sampleRate)).toInt().toShort()
            
            // Little endian
            audioData[i * 2] = (sample.toInt() and 0xFF).toByte()
            audioData[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        
        return audioData
    }
}
```

### 3. Conversión de Formatos de Audio

```kotlin
class AudioConversionExample {
    private val audioFileManager = AudioFileManager(context)
    
    suspend fun convertWavToCompatibleFormat() {
        val wavFile = File(context.filesDir, "input.wav")
        
        // Convertir WAV a formato compatible con PCMA
        val result = audioFileManager.convertToCompatibleFormat(
            inputFile = wavFile,
            inputFormat = AudioFileFormat.WAV
        )
        
        when (result) {
            is ConversionResult.Success -> {
                Log.d("Conversion", "Converted successfully:")
                Log.d("Conversion", "  Output: ${result.outputFile.name}")
                Log.d("Conversion", "  Duration: ${result.duration}ms")
                Log.d("Conversion", "  Format: ${result.originalFormat}")
                
                // Usar el archivo convertido
                useConvertedAudio(result.outputFile)
            }
            is ConversionResult.Error -> {
                Log.e("Conversion", "Conversion failed: ${result.message}")
            }
        }
    }
    
    suspend fun convertPcmToPcma() {
        val pcmFile = File(context.filesDir, "audio.pcm")
        
        val result = audioFileManager.convertPcmToPcma(pcmFile)
        
        when (result) {
            is ConversionResult.Success -> {
                // El archivo PCMA está listo para usar con WebRTC
                val pcmaData = result.outputFile.readBytes()
                enhancedWebRtcManager.injectOutgoingAudio(pcmaData)
            }
            is ConversionResult.Error -> {
                Log.e("Conversion", "PCMA conversion failed: ${result.message}")
            }
        }
    }
    
    private fun useConvertedAudio(audioFile: File) {
        // Cargar y usar el audio convertido
        val audioData = audioFile.readBytes()
        enhancedWebRtcManager.injectOutgoingAudio(audioData)
    }
}
```

## 🔧 Configuración Avanzada

### 1. Procesamiento de Audio en Tiempo Real

```kotlin
class RealTimeAudioProcessor : AudioInterceptor.AudioInterceptorListener {
    
    override fun onIncomingAudioReceived(audioData: ByteArray, timestamp: Long) {
        // Aplicar filtros al audio entrante
        val processedAudio = applyAudioFilters(audioData)
        
        // Reinyectar el audio procesado
        enhancedWebRtcManager.injectIncomingAudio(processedAudio)
    }
    
    override fun onOutgoingAudioCaptured(audioData: ByteArray, timestamp: Long) {
        // Aplicar efectos al audio saliente
        val processedAudio = applyAudioEffects(audioData)
        
        // Reinyectar el audio procesado
        enhancedWebRtcManager.injectOutgoingAudio(processedAudio)
    }
    
    private fun applyAudioFilters(audioData: ByteArray): ByteArray {
        // Ejemplo: Aplicar ganancia
        return AudioUtils.applyGain(audioData, 1.5f) // Aumentar volumen 50%
    }
    
    private fun applyAudioEffects(audioData: ByteArray): ByteArray {
        // Ejemplo: Aplicar filtro de ruido (implementación simplificada)
        return applyNoiseReduction(audioData)
    }
    
    private fun applyNoiseReduction(audioData: ByteArray): ByteArray {
        // Implementación básica de reducción de ruido
        val processedData = audioData.copyOf()
        
        for (i in 0 until processedData.size step 2) {
            if (i + 1 < processedData.size) {
                val sample = ((processedData[i + 1].toInt() and 0xFF) shl 8) or 
                            (processedData[i].toInt() and 0xFF)
                
                // Filtro simple: reducir samples por debajo de un umbral
                val threshold = 1000
                val filteredSample = if (abs(sample) < threshold) {
                    (sample * 0.3).toInt() // Reducir ruido de fondo
                } else {
                    sample
                }
                
                val clampedSample = max(-32768, min(32767, filteredSample)).toShort()
                
                processedData[i] = (clampedSample.toInt() and 0xFF).toByte()
                processedData[i + 1] = ((clampedSample.toInt() shr 8) and 0xFF).toByte()
            }
        }
        
        return processedData
    }
}
```

### 2. Gestión Avanzada de Archivos

```kotlin
class AdvancedAudioFileManager {
    private val audioFileManager = AudioFileManager(context)
    
    fun setupAutomaticCleanup() {
        // Limpiar archivos más antiguos de 3 días
        val threeDaysMs = 3 * 24 * 60 * 60 * 1000L
        audioFileManager.cleanupOldFiles(threeDaysMs)
    }
    
    fun getStorageStatistics() {
        val storageInfo = audioFileManager.getTotalStorageUsed()
        
        Log.d("Storage", "Audio storage usage:")
        Log.d("Storage", "  Recordings: ${storageInfo.recordingsSize / 1024} KB")
        Log.d("Storage", "  Converted: ${storageInfo.convertedSize / 1024} KB")
        Log.d("Storage", "  Temp: ${storageInfo.tempSize / 1024} KB")
        Log.d("Storage", "  Total: ${storageInfo.getTotalSizeString()}")
    }
    
    fun listAllRecordings() {
        val recordings = audioFileManager.getRecordingFiles()
        
        Log.d("Recordings", "Found ${recordings.size} recordings:")
        recordings.forEach { recording ->
            Log.d("Recordings", "  ${recording.name}:")
            Log.d("Recordings", "    Duration: ${recording.getDurationString()}")
            Log.d("Recordings", "    Size: ${recording.getSizeString()}")
            Log.d("Recordings", "    Compatible: ${recording.isCompatible}")
            Log.d("Recordings", "    Format: ${recording.format}")
        }
    }
    
    suspend fun convertAllIncompatibleFiles() {
        val recordings = audioFileManager.getRecordingFiles()
        val incompatibleFiles = recordings.filter { !it.isCompatible }
        
        Log.d("Conversion", "Converting ${incompatibleFiles.size} incompatible files...")
        
        incompatibleFiles.forEach { fileInfo ->
            val result = audioFileManager.convertToCompatibleFormat(
                inputFile = fileInfo.file,
                inputFormat = when (fileInfo.format.format) {
                    "WAV" -> AudioFileFormat.WAV
                    "PCMA" -> AudioFileFormat.PCMA
                    else -> AudioFileFormat.PCM_16BIT
                }
            )
            
            when (result) {
                is ConversionResult.Success -> {
                    Log.d("Conversion", "✅ Converted: ${fileInfo.name}")
                }
                is ConversionResult.Error -> {
                    Log.e("Conversion", "❌ Failed to convert ${fileInfo.name}: ${result.message}")
                }
            }
        }
    }
}
```

### 3. Integración con la Librería Principal

Para integrar completamente el interceptor con tu librería existente, necesitarás modificar algunos archivos:

#### Modificar `WebRtcManagerFactory.kt`:

```kotlin
object WebRtcManagerFactory {
    fun createWebRtcManager(application: Application): WebRtcManager {
        return EnhancedWebRtcManager(application) // Usar la versión mejorada
    }
}
```

#### Agregar método en `EddysSipLibrary.kt`:

```kotlin
class EddysSipLibrary private constructor() {
    // ... código existente ...
    
    /**
     * Obtiene el WebRTC Manager mejorado con capacidades de interceptación
     */
    fun getEnhancedWebRtcManager(): EnhancedWebRtcManager {
        checkInitialized()
        return sipCoreManager?.webRtcManager as? EnhancedWebRtcManager 
            ?: throw SipLibraryException("Enhanced WebRTC Manager not available")
    }
    
    /**
     * Habilita la interceptación de audio
     */
    fun enableAudioInterception(enabled: Boolean) {
        checkInitialized()
        getEnhancedWebRtcManager().enableAudioInterception(enabled)
    }
    
    /**
     * Inyecta audio personalizado para envío
     */
    fun injectOutgoingAudio(audioData: ByteArray) {
        checkInitialized()
        getEnhancedWebRtcManager().injectOutgoingAudio(audioData)
    }
    
    /**
     * Inyecta audio personalizado para reproducción
     */
    fun injectIncomingAudio(audioData: ByteArray) {
        checkInitialized()
        getEnhancedWebRtcManager().injectIncomingAudio(audioData)
    }
    
    /**
     * Obtiene el gestor de archivos de audio
     */
    fun getAudioFileManager(): AudioFileManager {
        checkInitialized()
        return AudioFileManager(sipCoreManager?.application ?: throw SipLibraryException("Application context not available"))
    }
}
```

## 🎵 Formatos de Audio Soportados

### Entrada (Input)
- **WAV**: Archivos WAV estándar (cualquier sample rate, mono/estéreo, 8/16-bit)
- **PCM Raw**: Audio PCM sin comprimir (16-bit little endian)
- **PCMA**: Audio A-law comprimido (8-bit, 8000 Hz)

### Salida (Output)
- **PCM 16-bit**: 8000 Hz, mono, little endian (para WebRTC)
- **PCMA**: A-law comprimido, 8000 Hz, mono (formato nativo del codec)

### Conversiones Automáticas
- ✅ **Sample Rate**: Cualquier rate → 8000 Hz
- ✅ **Canales**: Estéreo → Mono
- ✅ **Bit Depth**: 8-bit ↔ 16-bit
- ✅ **Formato**: WAV → PCM → PCMA
- ✅ **Compresión**: PCM ↔ A-law

## 📊 Especificaciones Técnicas

### Configuración de Audio
```
Codec: PCMA (A-law)
Sample Rate: 8000 Hz
Channels: 1 (Mono)
Bit Depth: 8-bit (PCMA) / 16-bit (PCM interno)
Frame Size: 20ms (160 samples)
Bitrate: 64 kbps
```

### Rendimiento
- **Latencia**: < 20ms para interceptación
- **CPU Usage**: < 5% en dispositivos modernos
- **Memory**: ~2MB para buffers de audio
- **Storage**: ~480 KB/minuto para grabaciones PCM

### Limitaciones
- ⚠️ **Formato Fijo**: Solo soporta PCMA/8000 para WebRTC
- ⚠️ **Mono Only**: No soporta audio estéreo en llamadas
- ⚠️ **Android Only**: Implementación específica para Android
- ⚠️ **Permisos**: Requiere permisos de audio y almacenamiento

## 🔧 Solución de Problemas

### Problema: Audio Distorsionado

```kotlin
// Verificar formato de audio
val audioInfo = audioFileManager.getAudioFileInfo(audioFile)
if (audioInfo?.format?.needsConversion() == true) {
    // Convertir antes de usar
    val result = audioFileManager.convertToCompatibleFormat(audioFile)
    // Usar result.outputFile
}
```

### Problema: Latencia Alta

```kotlin
// Reducir tamaño de buffer
val interceptor = AudioInterceptor(context)
// Usar frames más pequeños (10ms en lugar de 20ms)
// Nota: Esto requiere modificar AudioInterceptor.FRAME_SIZE_MS
```

### Problema: Archivos No Se Graban

```kotlin
// Verificar permisos
if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
    != PackageManager.PERMISSION_GRANTED) {
    // Solicitar permisos
    ActivityCompat.requestPermissions(activity, 
        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 
        REQUEST_STORAGE_PERMISSION)
}

// Verificar que la grabación esté habilitada
enhancedWebRtcManager.setAudioRecordingEnabled(true)
```

### Problema: Conversión Falla

```kotlin
// Verificar formato de entrada
val formatInfo = AudioUtils.validateAudioFormat(inputFile)
if (formatInfo == null) {
    Log.e("Conversion", "Unsupported audio format")
    return
}

// Verificar espacio en disco
val storageInfo = audioFileManager.getTotalStorageUsed()
if (storageInfo.totalSize > MAX_STORAGE_SIZE) {
    audioFileManager.cleanupOldFiles()
}
```

## 📚 Ejemplos Completos

### Ejemplo 1: Bot de Respuesta Automática

```kotlin
class AutoResponseBot : AudioInterceptor.AudioInterceptorListener {
    private val responses = listOf(
        "response1.pcm",
        "response2.pcm",
        "response3.pcm"
    )
    
    override fun onIncomingAudioReceived(audioData: ByteArray, timestamp: Long) {
        // Analizar audio entrante (detección de voz simple)
        if (detectSpeechEnd(audioData)) {
            // Reproducir respuesta automática
            playRandomResponse()
        }
    }
    
    private fun detectSpeechEnd(audioData: ByteArray): Boolean {
        // Implementación simple: detectar silencio
        val volume = calculateVolume(audioData)
        return volume < SILENCE_THRESHOLD
    }
    
    private fun playRandomResponse() {
        val responseFile = responses.random()
        val audioFile = File(context.assets, responseFile)
        val audioData = enhancedWebRtcManager.loadAudioFromFile(audioFile)
        
        audioData?.let {
            enhancedWebRtcManager.setCustomOutgoingAudioEnabled(true)
            enhancedWebRtcManager.injectOutgoingAudio(it)
        }
    }
}
```

### Ejemplo 2: Grabadora de Llamadas Avanzada

```kotlin
class AdvancedCallRecorder {
    private var isRecording = false
    private val recordingFiles = mutableListOf<File>()
    
    fun startRecording() {
        enhancedWebRtcManager.setAudioInterceptorListener(object : AudioInterceptor.AudioInterceptorListener {
            override fun onRecordingStarted(incomingFile: File?, outgoingFile: File?) {
                isRecording = true
                incomingFile?.let { recordingFiles.add(it) }
                outgoingFile?.let { recordingFiles.add(it) }
                
                // Notificar UI
                onRecordingStateChanged(true)
            }
            
            override fun onRecordingStopped() {
                isRecording = false
                
                // Procesar grabaciones
                processRecordings()
                
                // Notificar UI
                onRecordingStateChanged(false)
            }
            
            override fun onError(error: String) {
                Log.e("Recorder", "Recording error: $error")
                onRecordingError(error)
            }
        })
        
        // Habilitar grabación
        enhancedWebRtcManager.setAudioRecordingEnabled(true)
        enhancedWebRtcManager.enableAudioInterception(true)
    }
    
    private fun processRecordings() {
        CoroutineScope(Dispatchers.IO).launch {
            recordingFiles.forEach { file ->
                // Convertir a formato estándar
                val result = audioFileManager.convertToCompatibleFormat(file)
                
                when (result) {
                    is ConversionResult.Success -> {
                        // Agregar metadatos
                        addMetadataToRecording(result.outputFile)
                    }
                    is ConversionResult.Error -> {
                        Log.e("Recorder", "Failed to process ${file.name}: ${result.message}")
                    }
                }
            }
            
            recordingFiles.clear()
        }
    }
    
    private fun addMetadataToRecording(file: File) {
        // Crear archivo de metadatos
        val metadataFile = File(file.parent, "${file.nameWithoutExtension}.json")
        val metadata = mapOf(
            "filename" to file.name,
            "timestamp" to System.currentTimeMillis(),
            "duration" to AudioUtils.calculateAudioDuration(
                file.readBytes(),
                AudioUtils.PCMA_SAMPLE_RATE,
                AudioUtils.PCMA_CHANNELS,
                AudioUtils.PCM_BITS_PER_SAMPLE
            ),
            "format" to "PCMA/8000",
            "size" to file.length()
        )
        
        metadataFile.writeText(Json.encodeToString(metadata))
    }
}
```

## 🎯 Próximas Características

### En Desarrollo
- 🎛️ **Ecualizador en Tiempo Real**: Ajuste de frecuencias durante la llamada
- 🔊 **Efectos de Audio**: Reverb, echo, distorsión
- 🎵 **Mezclador Multi-Canal**: Combinar múltiples fuentes de audio
- 📡 **Streaming de Audio**: Transmitir audio a servicios externos

### Planificado
- 🤖 **IA de Procesamiento**: Reducción de ruido con machine learning
- 🎙️ **Reconocimiento de Voz**: Transcripción en tiempo real
- 🔄 **Codecs Adicionales**: Soporte para G.711, G.722, Opus
- 📱 **Interfaz Visual**: Visualizador de forma de onda en tiempo real

---

## 📞 Soporte y Contribución

Para soporte técnico, reportar bugs o contribuir al desarrollo:

- **GitHub Issues**: [Reportar problemas](https://github.com/eddyslarez/sip-library/issues)
- **Documentación**: [Wiki completa](https://github.com/eddyslarez/sip-library/wiki)
- **Email**: eddyslarez@example.com

**Desarrollado con ❤️ por Eddys Larez**

*¿Te resulta útil el Audio Interceptor? ¡Dale una ⭐ en GitHub!*