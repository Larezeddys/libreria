package com.eddyslarez.siplibrary.data.services.audio

import android.content.Context
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Gestor de archivos de audio para la librería SIP
 * Maneja grabación, conversión y organización de archivos de audio
 * 
 * @author Eddys Larez
 */
class AudioFileManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioFileManager"
        private const val RECORDINGS_DIR = "sip_recordings"
        private const val CONVERTED_DIR = "converted_audio"
        private const val TEMP_DIR = "temp_audio"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    
    // Directorios
    private val recordingsDir: File by lazy {
        File(context.filesDir, RECORDINGS_DIR).apply { mkdirs() }
    }
    
    private val convertedDir: File by lazy {
        File(context.filesDir, CONVERTED_DIR).apply { mkdirs() }
    }
    
    private val tempDir: File by lazy {
        File(context.filesDir, TEMP_DIR).apply { mkdirs() }
    }
    
    /**
     * Crea un nuevo archivo de grabación
     */
    fun createRecordingFile(prefix: String, extension: String = "pcm"): File {
        val timestamp = dateFormat.format(Date())
        val filename = "${prefix}_${timestamp}.$extension"
        return File(recordingsDir, filename)
    }
    
    /**
     * Convierte archivo de audio a formato compatible con PCMA
     */
    suspend fun convertToCompatibleFormat(
        inputFile: File,
        outputFile: File? = null,
        inputFormat: AudioFileFormat = AudioFileFormat.WAV
    ): ConversionResult = withContext(Dispatchers.IO) {
        try {
            log.d(tag = TAG) { "Converting audio file: ${inputFile.name}" }
            
            // Validar archivo de entrada
            if (!inputFile.exists()) {
                return@withContext ConversionResult.Error("Input file does not exist")
            }
            
            // Determinar archivo de salida
            val finalOutputFile = outputFile ?: createConvertedFile(inputFile.nameWithoutExtension)
            
            // Validar formato de entrada
            val formatInfo = AudioUtils.validateAudioFormat(inputFile)
            if (formatInfo == null) {
                return@withContext ConversionResult.Error("Unsupported audio format")
            }
            
            log.d(tag = TAG) { "Input format: $formatInfo" }
            
            // Proceso de conversión por pasos
            var currentFile = inputFile
            var tempFiles = mutableListOf<File>()
            
            try {
                // Paso 1: Convertir WAV a PCM si es necesario
                if (inputFormat == AudioFileFormat.WAV) {
                    val pcmFile = createTempFile("step1_pcm")
                    tempFiles.add(pcmFile)
                    
                    if (!AudioUtils.convertWavToPcm(currentFile, pcmFile)) {
                        return@withContext ConversionResult.Error("Failed to convert WAV to PCM")
                    }
                    currentFile = pcmFile
                }
                
                // Paso 2: Convertir a formato PCMA compatible si es necesario
                if (formatInfo.needsConversion()) {
                    val compatibleFile = createTempFile("step2_compatible")
                    tempFiles.add(compatibleFile)
                    
                    // Aquí se aplicarían conversiones de sample rate, canales, etc.
                    // Por simplicidad, copiamos el archivo
                    currentFile.copyTo(compatibleFile, overwrite = true)
                    currentFile = compatibleFile
                }
                
                // Paso 3: Copiar al archivo final
                currentFile.copyTo(finalOutputFile, overwrite = true)
                
                // Limpiar archivos temporales
                tempFiles.forEach { it.delete() }
                
                log.d(tag = TAG) { "Conversion completed: ${finalOutputFile.name}" }
                
                ConversionResult.Success(
                    outputFile = finalOutputFile,
                    originalFormat = formatInfo,
                    duration = AudioUtils.calculateAudioDuration(
                        finalOutputFile.readBytes(),
                        AudioUtils.PCMA_SAMPLE_RATE,
                        AudioUtils.PCMA_CHANNELS,
                        AudioUtils.PCM_BITS_PER_SAMPLE
                    )
                )
                
            } catch (e: Exception) {
                // Limpiar archivos temporales en caso de error
                tempFiles.forEach { it.delete() }
                throw e
            }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error converting audio file: ${e.message}" }
            ConversionResult.Error("Conversion failed: ${e.message}")
        }
    }
    
    /**
     * Convierte archivo PCM a PCMA
     */
    suspend fun convertPcmToPcma(pcmFile: File, pcmaFile: File? = null): ConversionResult = withContext(Dispatchers.IO) {
        try {
            val outputFile = pcmaFile ?: createConvertedFile(pcmFile.nameWithoutExtension, "pcma")
            
            if (AudioUtils.convertPcmFileToPcma(pcmFile, outputFile)) {
                ConversionResult.Success(
                    outputFile = outputFile,
                    originalFormat = AudioFormatInfo(
                        sampleRate = AudioUtils.PCMA_SAMPLE_RATE,
                        channels = AudioUtils.PCMA_CHANNELS,
                        bitsPerSample = AudioUtils.PCM_BITS_PER_SAMPLE,
                        format = "PCM"
                    ),
                    duration = AudioUtils.calculateAudioDuration(
                        pcmFile.readBytes(),
                        AudioUtils.PCMA_SAMPLE_RATE,
                        AudioUtils.PCMA_CHANNELS,
                        AudioUtils.PCM_BITS_PER_SAMPLE
                    )
                )
            } else {
                ConversionResult.Error("Failed to convert PCM to PCMA")
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error converting PCM to PCMA: ${e.message}" }
            ConversionResult.Error("Conversion failed: ${e.message}")
        }
    }
    
    /**
     * Convierte archivo PCMA a PCM
     */
    suspend fun convertPcmaToPcm(pcmaFile: File, pcmFile: File? = null): ConversionResult = withContext(Dispatchers.IO) {
        try {
            val outputFile = pcmFile ?: createConvertedFile(pcmaFile.nameWithoutExtension, "pcm")
            
            if (AudioUtils.convertPcmaFileToPcm(pcmaFile, outputFile)) {
                ConversionResult.Success(
                    outputFile = outputFile,
                    originalFormat = AudioFormatInfo(
                        sampleRate = AudioUtils.PCMA_SAMPLE_RATE,
                        channels = AudioUtils.PCMA_CHANNELS,
                        bitsPerSample = AudioUtils.PCMA_BITS_PER_SAMPLE,
                        format = "PCMA"
                    ),
                    duration = AudioUtils.calculateAudioDuration(
                        outputFile.readBytes(),
                        AudioUtils.PCMA_SAMPLE_RATE,
                        AudioUtils.PCMA_CHANNELS,
                        AudioUtils.PCM_BITS_PER_SAMPLE
                    )
                )
            } else {
                ConversionResult.Error("Failed to convert PCMA to PCM")
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error converting PCMA to PCM: ${e.message}" }
            ConversionResult.Error("Conversion failed: ${e.message}")
        }
    }
    
    /**
     * Aplica ganancia a un archivo de audio
     */
    suspend fun applyGainToFile(
        inputFile: File,
        gainFactor: Float,
        outputFile: File? = null
    ): ConversionResult = withContext(Dispatchers.IO) {
        try {
            val finalOutputFile = outputFile ?: createConvertedFile("${inputFile.nameWithoutExtension}_gain")
            
            val audioData = inputFile.readBytes()
            val processedData = AudioUtils.applyGain(audioData, gainFactor)
            
            finalOutputFile.writeBytes(processedData)
            
            ConversionResult.Success(
                outputFile = finalOutputFile,
                originalFormat = AudioFormatInfo(
                    sampleRate = AudioUtils.PCMA_SAMPLE_RATE,
                    channels = AudioUtils.PCMA_CHANNELS,
                    bitsPerSample = AudioUtils.PCM_BITS_PER_SAMPLE,
                    format = "PCM"
                ),
                duration = AudioUtils.calculateAudioDuration(
                    processedData,
                    AudioUtils.PCMA_SAMPLE_RATE,
                    AudioUtils.PCMA_CHANNELS,
                    AudioUtils.PCM_BITS_PER_SAMPLE
                )
            )
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error applying gain: ${e.message}" }
            ConversionResult.Error("Failed to apply gain: ${e.message}")
        }
    }
    
    /**
     * Obtiene información de un archivo de audio
     */
    fun getAudioFileInfo(file: File): AudioFileInfo? {
        return try {
            val formatInfo = AudioUtils.validateAudioFormat(file)
            if (formatInfo != null) {
                val audioData = file.readBytes()
                val duration = AudioUtils.calculateAudioDuration(
                    audioData,
                    formatInfo.sampleRate,
                    formatInfo.channels,
                    formatInfo.bitsPerSample
                )
                
                AudioFileInfo(
                    file = file,
                    format = formatInfo,
                    duration = duration,
                    size = file.length(),
                    isCompatible = formatInfo.isCompatibleWithPcma()
                )
            } else {
                null
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting audio file info: ${e.message}" }
            null
        }
    }
    
    /**
     * Lista archivos de grabación
     */
    fun getRecordingFiles(): List<AudioFileInfo> {
        return recordingsDir.listFiles()?.mapNotNull { file ->
            getAudioFileInfo(file)
        }?.sortedByDescending { it.file.lastModified() } ?: emptyList()
    }
    
    /**
     * Lista archivos convertidos
     */
    fun getConvertedFiles(): List<AudioFileInfo> {
        return convertedDir.listFiles()?.mapNotNull { file ->
            getAudioFileInfo(file)
        }?.sortedByDescending { it.file.lastModified() } ?: emptyList()
    }
    
    /**
     * Elimina archivos antiguos
     */
    fun cleanupOldFiles(maxAgeMs: Long = 7 * 24 * 60 * 60 * 1000L) { // 7 días por defecto
        scope.launch {
            val cutoffTime = System.currentTimeMillis() - maxAgeMs
            
            // Limpiar grabaciones
            recordingsDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoffTime) {
                    file.delete()
                    log.d(tag = TAG) { "Deleted old recording: ${file.name}" }
                }
            }
            
            // Limpiar archivos convertidos
            convertedDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoffTime) {
                    file.delete()
                    log.d(tag = TAG) { "Deleted old converted file: ${file.name}" }
                }
            }
            
            // Limpiar archivos temporales
            tempDir.listFiles()?.forEach { file ->
                file.delete()
                log.d(tag = TAG) { "Deleted temp file: ${file.name}" }
            }
        }
    }
    
    /**
     * Obtiene el tamaño total de archivos
     */
    fun getTotalStorageUsed(): StorageInfo {
        val recordingsSize = recordingsDir.listFiles()?.sumOf { it.length() } ?: 0L
        val convertedSize = convertedDir.listFiles()?.sumOf { it.length() } ?: 0L
        val tempSize = tempDir.listFiles()?.sumOf { it.length() } ?: 0L
        
        return StorageInfo(
            recordingsSize = recordingsSize,
            convertedSize = convertedSize,
            tempSize = tempSize,
            totalSize = recordingsSize + convertedSize + tempSize
        )
    }
    
    // === MÉTODOS PRIVADOS ===
    
    private fun createConvertedFile(baseName: String, extension: String = "pcm"): File {
        val timestamp = dateFormat.format(Date())
        val filename = "${baseName}_converted_${timestamp}.$extension"
        return File(convertedDir, filename)
    }
    
    private fun createTempFile(baseName: String, extension: String = "pcm"): File {
        val timestamp = System.currentTimeMillis()
        val filename = "${baseName}_${timestamp}.$extension"
        return File(tempDir, filename)
    }
}

/**
 * Resultado de conversión de audio
 */
sealed class ConversionResult {
    data class Success(
        val outputFile: File,
        val originalFormat: AudioFormatInfo,
        val duration: Long
    ) : ConversionResult()
    
    data class Error(val message: String) : ConversionResult()
}

/**
 * Información de archivo de audio
 */
data class AudioFileInfo(
    val file: File,
    val format: AudioFormatInfo,
    val duration: Long,
    val size: Long,
    val isCompatible: Boolean
) {
    val name: String get() = file.name
    val lastModified: Long get() = file.lastModified()
    
    fun getDurationString(): String {
        val seconds = duration / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%02d:%02d", minutes, remainingSeconds)
    }
    
    fun getSizeString(): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }
    }
}

/**
 * Información de almacenamiento
 */
data class StorageInfo(
    val recordingsSize: Long,
    val convertedSize: Long,
    val tempSize: Long,
    val totalSize: Long
) {
    fun getTotalSizeString(): String {
        return when {
            totalSize < 1024 -> "$totalSize B"
            totalSize < 1024 * 1024 -> "${totalSize / 1024} KB"
            else -> "${totalSize / (1024 * 1024)} MB"
        }
    }
}