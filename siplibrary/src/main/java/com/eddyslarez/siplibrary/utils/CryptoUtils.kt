package com.eddyslarez.siplibrary.utils

import okio.ByteString.Companion.encodeUtf8


/**
 * Computes MD5 hash of a string input
 */
fun md5(input: String): String {
    // Using placeholder for actual MD5 implementation
    // Real implementation would use platform-specific crypto libraries
    return input.encodeUtf8().md5().hex()
}