package com.eddyslarez.siplibrary.data.services.sip

import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.utils.log

/**
 * Utility for parsing SIP messages
 * 
 * @author Eddys Larez
 */
object SipMessageParser {
    private const val TAG = "SipMessageParser"

    /**
     * Logs an incoming SIP message
     */
    fun logIncomingMessage(message: String) {
        log.d(tag = TAG) { "\n=== SIP MESSAGE RECEIVED ===" }
        log.d(tag = TAG) { message }
        log.d(tag = TAG) { "=== END OF MESSAGE ===" }
    }

    /**
     * Extracts the reason phrase from a SIP response status line, e.g. "SIP/2.0 403 Forbidden"
     */
    fun extractStatusReason(message: String): String {
        val statusLine = message.lines().firstOrNull { it.startsWith("SIP/2.0") }
        return statusLine?.substringAfter(" ", "")?.substringAfter(" ", "")?.trim() ?: "Unknown error"
    }

    /**
     * Extracts the branch parameter from a Via header
     */
    fun extractBranchFromVia(viaHeader: String): String {
        val branchPattern = ";branch=([^;\\s]+)".toRegex()
        val matchResult = branchPattern.find(viaHeader)
        return matchResult?.groupValues?.get(1) ?: ""
    }

    /**
     * Extracts and updates the sequence number (CSeq) if present
     */
    fun updateCSeqIfPresent(lines: List<String>, accountInfo: AccountInfo) {
        lines.find { it.startsWith("CSeq:", ignoreCase = true) }?.let { cseqLine ->
            val parts = cseqLine.split("\\s+".toRegex())
            if (parts.size >= 2) {
                parts[1].toIntOrNull()?.let { seqNum ->
                    accountInfo.cseq = seqNum
                    log.d(tag = TAG) { "⭐️ Updated accountInfo.cseq = $seqNum" }
                }
            }
        }
    }

    /**
     * Extracts the method from the CSeq header
     */
    fun extractMethodFromCSeq(message: String, lines: List<String>): String {
        val cseqLine = lines.find { it.startsWith("CSeq:") }
        val cseqParts = cseqLine?.substringAfter("CSeq:")?.trim()?.split(" ")
        return cseqParts?.getOrNull(1) ?: "REGISTER"
    }

    /**
     * Extracts a specific header from message lines
     */
    fun extractHeader(lines: List<String>, headerName: String): String {
        val header = lines.find { it.startsWith("$headerName:", ignoreCase = true) }
            ?.substring("$headerName:".length)?.trim() ?: ""
        return header
    }

    /**
     * Extracts the SDP content from a message
     */
    fun extractSdpContent(message: String): String {
        val parts = message.split("\r\n\r\n", limit = 2)
        val sdp = if (parts.size > 1) parts[1] else ""
        return sdp
    }

    /**
     * Extracts the tag from a From or To header
     */
    fun extractTag(header: String): String {
        val tagMatch = Regex("""tag=([^;]+)""").find(header)
        return tagMatch?.groupValues?.get(1) ?: ""
    }

    /**
     * Extracts the URI from a Contact header
     */
    fun extractUriFromContact(contact: String): String {
        val uriMatch = Regex("<([^>]+)>").find(contact)
        return uriMatch?.groupValues?.get(1) ?: ""
    }

    /**
     * Extracts the display name from a From or To header
     */
    fun extractDisplayName(header: String): String {
        val nameMatch = Regex("""\"([^\"]+)\"""").find(header)
        return nameMatch?.groupValues?.get(1) ?: ""
    }

    /**
     * Extracts the URI from a From or To header
     */
    fun extractUriFromHeader(header: String): String {
        val uriMatch = Regex("<([^>]+)>").find(header)
        return uriMatch?.groupValues?.get(1) ?: ""
    }

    /**
     * Extracts the user from a SIP URI
     */
    fun extractUserFromUri(uri: String): String {
        val userMatch = Regex("""sip:([^@]+)@""").find(uri)
        return userMatch?.groupValues?.get(1) ?: uri
    }

    /**
     * Extracts the expiration value from a response
     */
    fun extractExpiresValue(message: String): Int {
        // First look in the Expires header
        val expiresMatch = Regex("Expires:\\s*(\\d+)").find(message)
        val expiresHeader = expiresMatch?.groupValues?.get(1)?.toIntOrNull()

        if (expiresHeader != null) {
            return expiresHeader
        }

        // If no Expires header, look in Contact: <...>;expires=X
        val contactExpiresMatch = Regex("Contact:.*?expires=(\\d+)").find(message)
        return contactExpiresMatch?.groupValues?.get(1)?.toIntOrNull() ?: 3600 // Default 1 hour
    }
}