package com.eddyslarez.siplibrary.data.services.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.eddyslarez.siplibrary.utils.log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Utilidades para manejo de audio en formato PCMA/8000
 * 
 * @author Eddys Larez
 */
object AudioUtils {
    
    private const val TAG = "AudioUtils"
    
    // Configuración para PCMA/8000
    const val PCMA_SAMPLE_RATE = 8000
    const val PCMA_CHANNELS = 1
    const val PCMA_BITS_PER_SAMPLE = 8
    const val PCM_BITS_PER_SAMPLE = 16
    
    // Tablas de conversión A-law optimizadas
    private val alawToLinearTable = IntArray(256)
    private val linearToAlawTable = ByteArray(65536)
    
    init {
        initializeConversionTables()
    }
    
    /**
     * Inicializa las tablas de conversión para optimizar rendimiento
     */
    private fun initializeConversionTables() {
        // Tabla A-law to Linear
        for (i in 0..255) {
            alawToLinearTable[i] = alawToLinearSingle(i.toByte()).toInt()
        }
        
        // Tabla Linear to A-law (solo para valores positivos, negativo se calcula)
        for (i in 0..32767) {
            linearToAlawTable[i] = linearToAlawSingle(i.toShort()).toInt() and 0xFF
        }
    }
    
    /**
     * Convierte un sample A-law a linear PCM
     */
    private fun alawToLinearSingle(alaw: Byte): Short {
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
     * Convierte un sample linear PCM a A-law
     */
    private fun linearToAlawSingle(pcm: Short): Byte {
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
        return (aval xor mask).toByte()
    }
    
    /**
     * Convierte array de PCM 16-bit a PCMA usando tabla optimizada
     */
    fun convertPcmToPcma(pcmData: ByteArray): ByteArray {
        val pcmaData = ByteArray(pcmData.size / 2)
        
        for (i in pcmaData.indices) {
            val sampleIndex = i * 2
            if (sampleIndex + 1 < pcmData.size) {
                // Leer sample de 16-bit (little endian)
                val sample = ((pcmData[sampleIndex + 1].toInt() and 0xFF) shl 8) or 
                           (pcmData[sampleIndex].toInt() and 0xFF)
                
                // Usar tabla de conversión
                val absValue = abs(sample)
                val tableIndex = min(absValue, 32767)
                var alawValue = linearToAlawTable[tableIndex].toByte()
                
                // Ajustar signo
                if (sample < 0) {
                    alawValue = (alawValue.toInt() xor 0x80).toByte()
                }
                
                pcmaData[i] = alawValue
            }
        }
        
        return pcmaData
    }
    
    /**
     * Convierte array de PCMA a PCM 16-bit usando tabla optimizada
     */
    fun convertPcmaToPcm(pcmaData: ByteArray): ByteArray {
        val pcmData = ByteArray(pcmaData.size * 2)
        
        for (i in pcmaData.indices) {
            val alawSample = pcmaData[i].toInt() and 0xFF
            val pcmSample = alawToLinearTable[alawSample].toShort()
            
            val pcmIndex = i * 2
            // Escribir sample de 16-bit (little endian)
            pcmData[pcmIndex] = (pcmSample.toInt() and 0xFF).toByte()
            pcmData[pcmIndex + 1] = ((pcmSample.toInt() shr 8) and 0xFF).toByte()
        }
        
        return pcmData
    }
    
    /**
     * Crea un AudioRecord configurado para PCMA/8000
     */
    fun createAudioRecord(): AudioRecord? {
        return try {
            val bufferSize = AudioRecord.getMinBufferSize(
                PCMA_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 4
            
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                PCMA_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating AudioRecord: ${e.message}" }
            null
        }
    }
    
    /**
     * Crea un AudioTrack configurado para PCMA/8000
     */
    fun createAudioTrack(): AudioTrack? {
        return try {
            val bufferSize = AudioTrack.getMinBufferSize(
                PCMA_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 4
            
            AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                PCMA_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating AudioTrack: ${e.message}" }
            null
        }
    }
    
    /**
     * Convierte archivo WAV a PCM raw
     */
    fun convertWavToPcm(wavFile: File, pcmFile: File): Boolean {
        return try {
            val wavData = wavFile.readBytes()
            
            // Verificar header WAV
            if (wavData.size < 44 || 
                !wavData.sliceArray(0..3).contentEquals("RIFF".toByteArray()) ||
                !wavData.sliceArray(8..11).contentEquals("WAVE".toByteArray())) {
                log.e(tag = TAG) { "Invalid WAV file format" }
                return false
            }
            
            // Extraer información del header
            val sampleRate = ByteBuffer.wrap(wavData, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val channels = ByteBuffer.wrap(wavData, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short
            val bitsPerSample = ByteBuffer.wrap(wavData, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short
            
            log.d(tag = TAG) { "WAV info - Sample rate: $sampleRate, Channels: $channels, Bits: $bitsPerSample" }
            
            // Encontrar chunk de datos
            var dataOffset = 36
            while (dataOffset < wavData.size - 8) {
                val chunkId = String(wavData.sliceArray(dataOffset..dataOffset+3))
                val chunkSize = ByteBuffer.wrap(wavData, dataOffset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                
                if (chunkId == "data") {
                    dataOffset += 8
                    break
                }
                dataOffset += 8 + chunkSize
            }
            
            if (dataOffset >= wavData.size) {
                log.e(tag = TAG) { "Data chunk not found in WAV file" }
                return false
            }
            
            // Extraer datos PCM
            val pcmData = wavData.sliceArray(dataOffset until wavData.size)
            
            // Convertir si es necesario
            val finalPcmData = when {
                sampleRate != PCMA_SAMPLE_RATE -> resampleAudio(pcmData, sampleRate, PCMA_SAMPLE_RATE, channels.toInt(), bitsPerSample.toInt())
                channels.toInt() != PCMA_CHANNELS -> convertToMono(pcmData, bitsPerSample.toInt())
                bitsPerSample.toInt() != PCM_BITS_PER_SAMPLE -> convertBitDepth(pcmData, bitsPerSample.toInt(), PCM_BITS_PER_SAMPLE)
                else -> pcmData
            }
            
            pcmFile.writeBytes(finalPcmData)
            true
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error converting WAV to PCM: ${e.message}" }
            false
        }
    }
    
    /**
     * Convierte archivo PCM a formato PCMA
     */
    fun convertPcmFileToPcma(pcmFile: File, pcmaFile: File): Boolean {
        return try {
            val pcmData = pcmFile.readBytes()
            val pcmaData = convertPcmToPcma(pcmData)
            pcmaFile.writeBytes(pcmaData)
            true
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error converting PCM to PCMA: ${e.message}" }
            false
        }
    }
    
    /**
     * Convierte archivo PCMA a formato PCM
     */
    fun convertPcmaFileToPcm(pcmaFile: File, pcmFile: File): Boolean {
        return try {
            val pcmaData = pcmaFile.readBytes()
            val pcmData = convertPcmaToPcm(pcmaData)
            pcmFile.writeBytes(pcmData)
            true
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error converting PCMA to PCM: ${e.message}" }
            false
        }
    }
    
    /**
     * Resamplea audio (implementación básica)
     */
    private fun resampleAudio(audioData: ByteArray, fromRate: Int, toRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        if (fromRate == toRate) return audioData
        
        val bytesPerSample = bitsPerSample / 8
        val frameSize = channels * bytesPerSample
        val inputFrames = audioData.size / frameSize
        val outputFrames = (inputFrames * toRate) / fromRate
        val outputData = ByteArray(outputFrames * frameSize)
        
        // Resampling simple usando interpolación lineal
        for (i in 0 until outputFrames) {
            val inputIndex = (i * fromRate) / toRate
            val inputOffset = inputIndex * frameSize
            
            if (inputOffset + frameSize <= audioData.size) {
                System.arraycopy(audioData, inputOffset, outputData, i * frameSize, frameSize)
            }
        }
        
        return outputData
    }
    
    /**
     * Convierte audio estéreo a mono
     */
    private fun convertToMono(audioData: ByteArray, bitsPerSample: Int): ByteArray {
        val bytesPerSample = bitsPerSample / 8
        val inputFrameSize = 2 * bytesPerSample // Estéreo
        val outputFrameSize = bytesPerSample   // Mono
        val frames = audioData.size / inputFrameSize
        val monoData = ByteArray(frames * outputFrameSize)
        
        for (i in 0 until frames) {
            val inputOffset = i * inputFrameSize
            val outputOffset = i * outputFrameSize
            
            if (bitsPerSample == 16) {
                // Promediar canales izquierdo y derecho
                val left = ((audioData[inputOffset + 1].toInt() and 0xFF) shl 8) or 
                          (audioData[inputOffset].toInt() and 0xFF)
                val right = ((audioData[inputOffset + 3].toInt() and 0xFF) shl 8) or 
                           (audioData[inputOffset + 2].toInt() and 0xFF)
                
                val mono = ((left + right) / 2).toShort()
                
                monoData[outputOffset] = (mono.toInt() and 0xFF).toByte()
                monoData[outputOffset + 1] = ((mono.toInt() shr 8) and 0xFF).toByte()
            }
        }
        
        return monoData
    }
    
    /**
     * Convierte profundidad de bits
     */
    private fun convertBitDepth(audioData: ByteArray, fromBits: Int, toBits: Int): ByteArray {
        if (fromBits == toBits) return audioData
        
        return when {
            fromBits == 8 && toBits == 16 -> {
                // 8-bit a 16-bit
                val output = ByteArray(audioData.size * 2)
                for (i in audioData.indices) {
                    val sample8 = audioData[i].toInt() and 0xFF
                    val sample16 = ((sample8 - 128) * 256).toShort()
                    
                    output[i * 2] = (sample16.toInt() and 0xFF).toByte()
                    output[i * 2 + 1] = ((sample16.toInt() shr 8) and 0xFF).toByte()
                }
                output
            }
            fromBits == 16 && toBits == 8 -> {
                // 16-bit a 8-bit
                val output = ByteArray(audioData.size / 2)
                for (i in output.indices) {
                    val sample16 = ((audioData[i * 2 + 1].toInt() and 0xFF) shl 8) or 
                                  (audioData[i * 2].toInt() and 0xFF)
                    val sample8 = ((sample16 / 256) + 128).toByte()
                    output[i] = sample8
                }
                output
            }
            else -> audioData
        }
    }
    
    /**
     * Valida formato de audio para compatibilidad con PCMA
     */
    fun validateAudioFormat(file: File): AudioFormatInfo? {
        return try {
            if (file.extension.lowercase() == "wav") {
                validateWavFormat(file)
            } else {
                // Asumir PCM raw
                AudioFormatInfo(
                    sampleRate = PCMA_SAMPLE_RATE,
                    channels = PCMA_CHANNELS,
                    bitsPerSample = PCM_BITS_PER_SAMPLE,
                    format = "PCM"
                )
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error validating audio format: ${e.message}" }
            null
        }
    }
    
    /**
     * Valida formato WAV
     */
    private fun validateWavFormat(file: File): AudioFormatInfo? {
        return try {
            val header = ByteArray(44)
            FileInputStream(file).use { it.read(header) }
            
            if (!header.sliceArray(0..3).contentEquals("RIFF".toByteArray()) ||
                !header.sliceArray(8..11).contentEquals("WAVE".toByteArray())) {
                return null
            }
            
            val sampleRate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val channels = ByteBuffer.wrap(header, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short
            val bitsPerSample = ByteBuffer.wrap(header, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short
            
            AudioFormatInfo(
                sampleRate = sampleRate,
                channels = channels.toInt(),
                bitsPerSample = bitsPerSample.toInt(),
                format = "WAV"
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Calcula duración de audio en milisegundos
     */
    fun calculateAudioDuration(audioData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): Long {
        val bytesPerSample = bitsPerSample / 8
        val totalSamples = audioData.size / (channels * bytesPerSample)
        return (totalSamples * 1000L) / sampleRate
    }
    
    /**
     * Aplica ganancia a audio PCM
     */
    fun applyGain(audioData: ByteArray, gainFactor: Float): ByteArray {
        val processedData = audioData.copyOf()
        
        for (i in 0 until processedData.size step 2) {
            if (i + 1 < processedData.size) {
                val sample = ((processedData[i + 1].toInt() and 0xFF) shl 8) or 
                            (processedData[i].toInt() and 0xFF)
                
                val amplifiedSample = (sample * gainFactor).toInt()
                val clampedSample = max(-32768, min(32767, amplifiedSample)).toShort()
                
                processedData[i] = (clampedSample.toInt() and 0xFF).toByte()
                processedData[i + 1] = ((clampedSample.toInt() shr 8) and 0xFF).toByte()
            }
        }
        
        return processedData
    }
}

/**
 * Información de formato de audio
 */
data class AudioFormatInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val format: String
) {
    fun isCompatibleWithPcma(): Boolean {
        return sampleRate == AudioUtils.PCMA_SAMPLE_RATE &&
               channels == AudioUtils.PCMA_CHANNELS
    }
    
    fun needsConversion(): Boolean {
        return !isCompatibleWithPcma() || bitsPerSample != AudioUtils.PCM_BITS_PER_SAMPLE
    }
}