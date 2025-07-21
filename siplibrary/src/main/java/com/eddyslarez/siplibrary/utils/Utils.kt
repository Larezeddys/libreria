package com.eddyslarez.siplibrary.utils

import kotlinx.datetime.Clock
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Utilidades generales
 * 
 * @author Eddys Larez
 */

/**
 * Genera un identificador único para transacciones SIP
 */
fun generateId(): String {
    return "${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(100000)}"
}

fun generateSipTag(): String {
    return Clock.System.now().toEpochMilliseconds().toString() + "-" + (1000..9999).random()
}

///**
// * Computa hash MD5 de un string
// */
//fun md5(input: String): String {
//    val md = MessageDigest.getInstance("MD5")
//    val digest = md.digest(input.toByteArray())
//    return digest.joinToString("") { "%02x".format(it) }
//}

/**
 * Interface de logging simple
 */
interface Logger {
    fun d(tag: String = "", message: () -> String)
    fun i(tag: String = "", message: () -> String)
    fun w(tag: String = "", message: () -> String)
    fun e(tag: String = "", message: () -> String)
}

/**
 * Implementación de logger para Android
 */
class AndroidLogger : Logger {
    override fun d(tag: String, message: () -> String) {
        android.util.Log.d(tag, message())
    }

    override fun i(tag: String, message: () -> String) {
        android.util.Log.i(tag, message())
    }

    override fun w(tag: String, message: () -> String) {
        android.util.Log.w(tag, message())
    }

    override fun e(tag: String, message: () -> String) {
        android.util.Log.e(tag, message())
    }
}

// Instancia global del logger
val log: Logger = AndroidLogger()

data class LogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String
)

data class LogFilter(
    val selectedTags: Set<String> = emptySet(),
    val selectedLevels: Set<String> = setOf("DEBUG", "INFO", "WARN", "ERROR"),
    val searchText: String = ""
)