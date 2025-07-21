package com.eddyslarez.siplibrary.data.services.sip

import com.eddyslarez.siplibrary.data.models.*
import com.eddyslarez.siplibrary.utils.generateId
import com.eddyslarez.siplibrary.utils.log

/**
 * Builder for creating different types of SIP messages
 * Optimized version with reduced code duplication
 * 
 * @author Eddys Larez
 */
object SipMessageBuilder {
    // Constants
    private const val SIP_VERSION = "SIP/2.0"
    private const val SIP_TRANSPORT = "WS"
    private const val MAX_FORWARDS = 70
    private const val DEFAULT_EXPIRES = 604800
    private const val UNREGISTER_EXPIRES = 0

    /**
     * Build REGISTER message with optional push notification support
     */
    fun buildRegisterMessage(
        accountInfo: AccountInfo,
        callId: String,
        fromTag: String,
        isAppInBackground: Boolean,
        isAuthenticated: Boolean = false,
    ): String {
        val uri = "sip:${accountInfo.domain}"
        val builder = StringBuilder()

        builder.append("REGISTER $uri $SIP_VERSION\r\n")
        builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.domain};branch=z9hG4bK${generateId()}\r\n")
        builder.append("Max-Forwards: $MAX_FORWARDS\r\n")
        builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=$fromTag\r\n")
        builder.append("To: <sip:${accountInfo.username}@${accountInfo.domain}>\r\n")
        builder.append("Call-ID: $callId\r\n")
        builder.append("CSeq: ${++accountInfo.cseq} REGISTER\r\n")
        builder.append("User-Agent: ${accountInfo.userAgent}\r\n")

        // Contact header based on mode
        builder.append("Contact: <sip:${accountInfo.username}@${accountInfo.domain}")
        if (isAppInBackground) {
            builder.append(";pn-prid=${accountInfo.token};pn-provider=${accountInfo.provider}")
        }
        builder.append(";transport=ws>;expires=$DEFAULT_EXPIRES\r\n")

        builder.append("Expires: $DEFAULT_EXPIRES\r\n")

        // Authorization if needed
        if (isAuthenticated && accountInfo.authorizationHeader != null) {
            builder.append("Authorization: ${accountInfo.authorizationHeader}\r\n")
        }

        builder.append("Content-Length: 0\r\n\r\n")
        return builder.toString()
    }

    /**
     * Build authenticated REGISTER message
     */
    fun buildAuthenticatedRegisterMessage(accountInfo: AccountInfo, isAppInBackground: Boolean): String {
        return buildRegisterMessage(
            accountInfo = accountInfo,
            callId = accountInfo.callId ?: generateId(),
            fromTag = accountInfo.fromTag ?: generateId(),
            isAppInBackground = isAppInBackground,
            isAuthenticated = true
        )
    }

    /**
     * Build unregister message (expires=0)
     */
    fun buildUnregisterMessage(
        accountInfo: AccountInfo,
        callId: String,
        fromTag: String
    ): String {
        return buildRegisterMessage(
            accountInfo = accountInfo,
            callId = callId,
            fromTag = fromTag,
            isAppInBackground = false,
            isAuthenticated = accountInfo.authorizationHeader != null
        ).replace("Expires: $DEFAULT_EXPIRES", "Expires: $UNREGISTER_EXPIRES")
            .replace("expires=$DEFAULT_EXPIRES", "expires=$UNREGISTER_EXPIRES")
    }

    /**
     * Build INVITE message (handles both regular and authenticated)
     */
    fun buildInviteMessage(
        accountInfo: AccountInfo,
        callData: CallData,
        sdp: String,
        isAuthenticated: Boolean = false
    ): String {
        val target = callData.to
        val uri = "sip:${target}@${accountInfo.domain}"
        val branch = "z9hG4bK${generateId()}"

        // Store branch for future reference
        callData.inviteViaBranch = branch
        callData.via = "$SIP_VERSION/$SIP_TRANSPORT ${accountInfo.domain};branch=$branch"

        val builder = StringBuilder()
        builder.append("INVITE $uri $SIP_VERSION\r\n")
        builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.domain};branch=$branch\r\n")
        builder.append("Max-Forwards: $MAX_FORWARDS\r\n")
        builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.inviteFromTag}\r\n")
        builder.append("To: <$uri>\r\n")
        builder.append("Call-ID: ${callData.callId}\r\n")
        builder.append("CSeq: ${++accountInfo.cseq} INVITE\r\n")
        builder.append("Contact: <sip:${accountInfo.username}@${accountInfo.domain};transport=ws>\r\n")

        if (isAuthenticated && accountInfo.authorizationHeader != null) {
            builder.append("Authorization: ${accountInfo.authorizationHeader}\r\n")
        }

        builder.append("Content-Type: application/sdp\r\n")
        builder.append("Content-Length: ${sdp.length}\r\n\r\n")
        builder.append(sdp)

        return builder.toString()
    }

    /**
     * Build authenticated INVITE message
     */
    fun buildAuthenticatedInviteMessage(
        accountInfo: AccountInfo,
        callData: CallData,
        sdp: String
    ): String = buildInviteMessage(accountInfo, callData, sdp, isAuthenticated = true)

    /**
     * Build call control messages (BYE, CANCEL, ACK)
     */
    private fun buildCallControlMessage(
        method: String,
        accountInfo: AccountInfo,
        callData: CallData,
        useOriginalVia: Boolean = false
    ): String {
        val targetUri = getTargetUri(accountInfo, callData)
        val branch = if (useOriginalVia) {
            callData.inviteViaBranch ?: generateId()
        } else {
            generateId()
        }

        val builder = StringBuilder()
        builder.append("$method $targetUri $SIP_VERSION\r\n")

        if (useOriginalVia && method == "CANCEL") {
            val originalViaHeader = SipMessageParser.extractHeader(
                callData.originalCallInviteMessage.split("\r\n"), "Via"
            )
            builder.append("Via: $originalViaHeader\r\n")
        } else {
            builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.domain};branch=z9hG4bK$branch\r\n")
        }

        builder.append("Max-Forwards: $MAX_FORWARDS\r\n")

        // Add From/To headers based on call direction
        appendFromToHeaders(builder, accountInfo, callData, method)

        builder.append("Call-ID: ${callData.callId}\r\n")

        // Handle CSeq for different methods
        val cseqValue = if (method == "CANCEL") {
            val originalCseqHeader = SipMessageParser.extractHeader(
                callData.originalCallInviteMessage.split("\r\n"), "CSeq"
            )
            originalCseqHeader.split(" ")[0].trim().toInt()
        } else {
            ++accountInfo.cseq
        }

        builder.append("CSeq: $cseqValue $method\r\n")
        builder.append("Content-Length: 0\r\n\r\n")

        return builder.toString()
    }

    /**
     * Get target URI based on call direction
     */
    private fun getTargetUri(accountInfo: AccountInfo, callData: CallData): String {
        return if (callData.direction == CallDirections.OUTGOING) {
            "sip:${callData.to}@${accountInfo.domain}"
        } else {
            val contactHeader = SipMessageParser.extractHeader(
                callData.originalInviteMessage.split("\r\n"), "Contact"
            )
            val contactUri = SipMessageParser.extractUriFromContact(contactHeader)
            contactUri.ifEmpty { "sip:${callData.from}@${accountInfo.domain}" }
        }
    }

    /**
     * Append From/To headers based on call direction
     */
    private fun appendFromToHeaders(
        builder: StringBuilder,
        accountInfo: AccountInfo,
        callData: CallData,
        method: String
    ) {
        when {
            callData.direction == CallDirections.OUTGOING -> {
                builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.inviteFromTag}\r\n")
                val toUri = "sip:${callData.to}@${accountInfo.domain}"
                val toTagStr = if (method == "CANCEL") "" else ";tag=${callData.inviteToTag}"
                builder.append("To: <$toUri>$toTagStr\r\n")
            }

            callData.direction == CallDirections.INCOMING -> {
                builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.inviteToTag}\r\n")
                builder.append("To: <sip:${callData.from}@${accountInfo.domain}>;tag=${callData.inviteFromTag}\r\n")
            }
        }
    }

    /**
     * Build BYE message
     */
    fun buildByeMessage(accountInfo: AccountInfo, callData: CallData): String =
        buildCallControlMessage("BYE", accountInfo, callData)

    /**
     * Build CANCEL message
     */
    fun buildCancelMessage(accountInfo: AccountInfo, callData: CallData): String =
        buildCallControlMessage("CANCEL", accountInfo, callData, useOriginalVia = true)

    /**
     * Build ACK message
     */
    fun buildAckMessage(accountInfo: AccountInfo, callData: CallData): String {
        val target = callData.to
        val uri = "sip:${target}@${accountInfo.domain}"

        return buildString {
            append("ACK $uri $SIP_VERSION\r\n")
            append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.domain};branch=z9hG4bK${generateId()}\r\n")
            append("Max-Forwards: $MAX_FORWARDS\r\n")
            append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.inviteFromTag}\r\n")
            append("To: <$uri>;tag=${callData.inviteToTag}\r\n")
            append("Call-ID: ${callData.callId}\r\n")
            append("CSeq: ${accountInfo.cseq} ACK\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
    }

    /**
     * Build SIP responses (200 OK, 180 Ringing, etc.)
     */
    private fun buildSipResponse(
        statusCode: Int,
        reasonPhrase: String,
        accountInfo: AccountInfo,
        callData: CallData,
        method: String = "INVITE",
        includeToTag: Boolean = true,
        includeContact: Boolean = false,
        contentType: String? = null,
        content: String = ""
    ): String {
        val toUri = "sip:${accountInfo.username}@${accountInfo.domain}"
        val fromUri = "sip:${callData.from}@${accountInfo.domain}"
        val viaHeader = callData.via

        return buildString {
            append("$SIP_VERSION $statusCode $reasonPhrase\r\n")
            append("Via: $viaHeader\r\n")
            append("From: <$fromUri>;tag=${callData.fromTag}\r\n")
            append("To: <$toUri>")
            if (includeToTag) append(";tag=${callData.toTag}")
            append("\r\n")
            append("Call-ID: ${callData.callId}\r\n")
            append("CSeq: ${accountInfo.cseq} $method\r\n")

            if (includeContact) {
                append("Contact: <sip:${accountInfo.username}@${accountInfo.domain};transport=ws>\r\n")
            }

            contentType?.let {
                append("Content-Type: $it\r\n")
                append("Content-Length: ${content.length}\r\n\r\n")
                append(content)
            } ?: run {
                append("Content-Length: 0\r\n\r\n")
            }
        }
    }

    // Response builders using the common function
    fun buildTryingResponse(accountInfo: AccountInfo, callData: CallData): String =
        buildSipResponse(100, "Trying", accountInfo, callData, includeToTag = false)

    fun buildRingingResponse(accountInfo: AccountInfo, callData: CallData): String =
        buildSipResponse(180, "Ringing", accountInfo, callData, includeContact = true)

    fun buildInviteOkResponse(accountInfo: AccountInfo, callData: CallData): String =
        buildSipResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            accountInfo = accountInfo,
            callData = callData,
            includeContact = true,
            contentType = "application/sdp",
            content = callData.localSdp
        )

    fun buildDeclineResponse(accountInfo: AccountInfo, callData: CallData): String =
        buildSipResponse(603, "Decline", accountInfo, callData, includeContact = true)
            .replace(
                "Content-Length: 0\r\n\r\n",
                "Reason: SIP;cause=603;text=\"Decline\"\r\nContent-Length: 0\r\n\r\n"
            )

    fun buildBusyHereResponse(accountInfo: AccountInfo, callData: CallData): String =
        buildSipResponse(486, "Busy Here", accountInfo, callData, includeContact = true)

    fun buildRequestTerminatedResponse(accountInfo: AccountInfo, callData: CallData): String =
        buildSipResponse(487, "Request Terminated", accountInfo, callData)

    /**
     * Build generic OK responses for requests
     */
    private fun buildGenericOkResponse(lines: List<String>): String {
        val viaHeader = SipMessageParser.extractHeader(lines, "Via")
        val fromHeader = SipMessageParser.extractHeader(lines, "From")
        val toHeader = SipMessageParser.extractHeader(lines, "To")
        val callId = SipMessageParser.extractHeader(lines, "Call-ID")
        val cseqHeader = SipMessageParser.extractHeader(lines, "CSeq")

        return buildString {
            append("SIP/2.0 200 OK\r\n")
            append("Via: $viaHeader\r\n")
            append("From: $fromHeader\r\n")
            append("To: $toHeader\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseqHeader\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
    }

    fun buildByeOkResponse(accountInfo: AccountInfo, lines: List<String>): String =
        buildGenericOkResponse(lines)

    fun buildCancelOkResponse(accountInfo: AccountInfo, lines: List<String>): String =
        buildGenericOkResponse(lines)

    /**
     * Build ACK for 487 response
     */
    fun buildAckFor487Response(
        accountInfo: AccountInfo,
        callData: CallData,
        lines: List<String>
    ): String {
        val toHeader = SipMessageParser.extractHeader(lines, "To")
        val fromHeader = SipMessageParser.extractHeader(lines, "From")
        val viaHeader = SipMessageParser.extractHeader(lines, "Via")
        val cseqValue = SipMessageParser.extractHeader(lines, "CSeq").split(" ")[0]
        val uri = "sip:${callData.to}@${accountInfo.domain}"

        return buildString {
            append("ACK $uri $SIP_VERSION\r\n")
            append("Via: $viaHeader\r\n")
            append("From: $fromHeader\r\n")
            append("To: $toHeader\r\n")
            append("Call-ID: ${callData.callId}\r\n")
            append("CSeq: $cseqValue ACK\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
    }

    /**
     * Build re-INVITE message
     */
    fun buildReInviteMessage(
        accountInfo: AccountInfo,
        callData: CallData,
        sdp: String
    ): String {
        val targetUri = getTargetUri(accountInfo, callData)
        val branch = "z9hG4bK${generateId()}"

        return buildString {
            append("INVITE $targetUri $SIP_VERSION\r\n")
            append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.domain};branch=$branch\r\n")
            append("Max-Forwards: $MAX_FORWARDS\r\n")

            // From/To headers based on call direction
            if (callData.direction == CallDirections.OUTGOING) {
                append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.inviteFromTag}\r\n")
                append("To: <sip:${callData.to}@${accountInfo.domain}>;tag=${callData.inviteToTag}\r\n")
            } else {
                append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.inviteToTag}\r\n")
                append("To: <sip:${callData.from}@${accountInfo.domain}>;tag=${callData.inviteFromTag}\r\n")
            }

            append("Call-ID: ${callData.callId}\r\n")
            append("CSeq: ${++accountInfo.cseq} INVITE\r\n")
            append("Contact: <sip:${accountInfo.username}@${accountInfo.domain};transport=ws>\r\n")
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: ${sdp.length}\r\n\r\n")
            append(sdp)
        }
    }

    /**
     * Build INFO message for DTMF
     */
    fun buildDtmfInfoMessage(
        accountInfo: AccountInfo,
        callData: CallData,
        dtmfEvent: Char,
        duration: Int
    ): String {
        try {
            val targetUri = getTargetUri(accountInfo, callData)
            val fromTag = if (callData.direction == CallDirections.OUTGOING) {
                callData.inviteFromTag
            } else {
                callData.inviteToTag
            }
            val toTag = if (callData.direction == CallDirections.OUTGOING) {
                callData.inviteToTag
            } else {
                callData.inviteFromTag
            }

            // Normalizar el dígito DTMF
            val normalizedDigit = when (dtmfEvent.lowercaseChar()) {
                'a', 'b', 'c', 'd' -> dtmfEvent.uppercaseChar()
                else -> dtmfEvent
            }

            // Contenido DTMF según RFC 2833
            val dtmfContent = buildString {
                append("Signal=${normalizedDigit}\r\n")
                append("Duration=${duration}\r\n")
            }

            val message = buildString {
                append("INFO $targetUri $SIP_VERSION\r\n")
                append("Via: ${callData.via}\r\n")
                append("Max-Forwards: $MAX_FORWARDS\r\n")

                if (callData.direction == CallDirections.OUTGOING) {
                    append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=$fromTag\r\n")
                    append("To: <$targetUri>;tag=$toTag\r\n")
                } else {
                    append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=$fromTag\r\n")
                    append("To: <sip:${callData.from}@${accountInfo.domain}>;tag=$toTag\r\n")
                }

                append("Call-ID: ${callData.callId}\r\n")
                append("CSeq: ${++accountInfo.cseq} INFO\r\n")
                append("Contact: <sip:${accountInfo.username}@${accountInfo.domain};transport=ws>\r\n")
                append("User-Agent: ${accountInfo.userAgent}\r\n")
                append("Content-Type: application/dtmf-relay\r\n")
                append("Content-Length: ${dtmfContent.length}\r\n")
                append("\r\n")
                append(dtmfContent)
            }

            log.d(tag = "SipMessageBuilder") { "DTMF INFO message built for digit '$normalizedDigit'" }
            return message

        } catch (e: Exception) {
            log.e(tag = "SipMessageBuilder") { "Error building DTMF INFO message: ${e.message}" }
            throw e
        }
    }
}