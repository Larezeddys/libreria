package com.eddyslarez.siplibrary.data.services.sip

import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.models.CallDirections
import com.eddyslarez.siplibrary.utils.generateId
import com.eddyslarez.siplibrary.utils.log
import com.eddyslarez.siplibrary.utils.md5

/**
 * Handles SIP authentication
 * 
 * @author Eddys Larez
 */
object AuthenticationHandler {
    private const val TAG = "AuthenticationHandler"

    /**
     * Class to store authentication data
     */
    data class AuthData(
        val realm: String,
        val nonce: String,
        val algorithm: String,
        val callId: String,
        val fromTag: String
    )

    /**
     * Extracts authentication data from the message
     */
    fun extractAuthenticationData(lines: List<String>): AuthData? {
        val authHeader = lines.find {
            it.startsWith("WWW-Authenticate:", ignoreCase = true) ||
                    it.startsWith("Proxy-Authenticate:", ignoreCase = true)
        } ?: run {
            log.d(tag = TAG) { "ERROR: Authentication header not found" }
            return null
        }

        val realmMatch = Regex("""realm\s*=\s*"([^"]+)""").find(authHeader)
        val nonceMatch = Regex("""nonce\s*=\s*"([^"]+)""").find(authHeader)
        val algorithmMatch = Regex("""algorithm\s*=\s*([^,]+)""").find(authHeader)

        val realm = realmMatch?.groupValues?.get(1) ?: run {
            log.d(tag = TAG) { "ERROR: Realm not found in challenge" }
            return null
        }

        val nonce = nonceMatch?.groupValues?.get(1) ?: run {
            log.d(tag = TAG) { "ERROR: Nonce not found in challenge" }
            return null
        }

        val algorithm = algorithmMatch?.groupValues?.get(1)?.trim('"') ?: "MD5"

        if (algorithm != "MD5") {
            log.d(tag = TAG) { "ERROR: Unsupported algorithm: $algorithm" }
            return null
        }

        val callId = lines.find { it.startsWith("Call-ID:", ignoreCase = true) }
            ?.substring("Call-ID:".length)?.trim() ?: generateId()

        val fromLine = lines.find { it.startsWith("From:", ignoreCase = true) }
        val fromTag = fromLine?.let {
            val tagMatch = Regex("""tag=([^;]+)""").find(it)
            tagMatch?.groupValues?.get(1)
        } ?: generateId()

        return AuthData(realm, nonce, algorithm, callId, fromTag)
    }

    /**
     * Calculates the authentication response
     */
    fun calculateAuthResponse(
        accountInfo: AccountInfo,
        authData: AuthData,
        method: String
    ): String {
        val username = accountInfo.username
        val password = accountInfo.password
        val realm = authData.realm
        val nonce = authData.nonce

        // Calculate MD5 hashes according to digest scheme
        val ha1Input = "$username:$realm:$password"
        val ha1 = md5(ha1Input)

        val uri = getUriForMethod(accountInfo, method)
        val ha2Input = "$method:$uri"
        val ha2 = md5(ha2Input)

        val responseInput = "$ha1:$nonce:$ha2"
        return md5(responseInput)
    }

    /**
     * Updates authentication information in the account
     */
    fun updateAccountAuthInfo(
        accountInfo: AccountInfo,
        authData: AuthData,
        response: String,
        method: String
    ) {
        val username = accountInfo.username
        val realm = authData.realm
        val nonce = authData.nonce
        val algorithm = authData.algorithm
        val uri = getUriForMethod(accountInfo, method)

        // Build authorization header
        val authHeaderValue = buildString {
            append("Digest ")
            append("username=\"$username\", ")
            append("realm=\"$realm\", ")
            append("nonce=\"$nonce\", ")
            append("uri=\"$uri\", ")
            append("response=\"$response\", ")
            append("algorithm=$algorithm")
        }

        // Update the account
        accountInfo.apply {
            this.callId = authData.callId
            this.fromTag = authData.fromTag
            this.challengeNonce = nonce
            this.realm = realm
            this.authorizationHeader = authHeaderValue
        }
    }

    /**
     * Determines the URI to use based on the SIP method
     */
    private fun getUriForMethod(accountInfo: AccountInfo, method: String): String {
        return if (method == "INVITE") {
            // For INVITE, use the destination URI based on call data
            val callData = accountInfo.currentCallData
            if (callData != null && callData.direction == CallDirections.OUTGOING) {
                "sip:${callData.to}@${accountInfo.domain}"
            } else {
                "sip:${accountInfo.domain}"
            }
        } else {
            // For REGISTER, use the domain URI
            "sip:${accountInfo.domain}"
        }
    }
}