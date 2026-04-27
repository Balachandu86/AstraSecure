package com.explo.capstone.metadata

import com.explo.capstone.shared.Message
import java.security.SecureRandom

/**
 * Owner: Jatin Preet Singh
 * Responsible for: metadata normalization — padding, batching, and randomized transmission timing.
 */
class MetadataProcessor {

    private val blockSizeBytes = 256  // Pad to multiples of this

    private val secureRandom = SecureRandom()

    /**
     * Pad a message's encrypted payload to the next multiple of blockSizeBytes.
     * This prevents an observer inferring message length from ciphertext size.
     */
    fun padMessage(encryptedBytes: ByteArray): ByteArray {
        val remainder = encryptedBytes.size % blockSizeBytes
        if (remainder == 0) return encryptedBytes
        val padLength = blockSizeBytes - remainder
        val padding = ByteArray(padLength).also { secureRandom.nextBytes(it) }
        return encryptedBytes + padding
    }

    /**
     * Return a cryptographically random delay in milliseconds.
     * Randomising transmission timing defeats traffic-pattern analysis.
     */
    fun randomizedDelayMs(minMs: Long = 200, maxMs: Long = 2000): Long {
        val range = maxMs - minMs
        val rand = (secureRandom.nextLong().and(Long.MAX_VALUE)) % range
        return minMs + rand
    }

    /**
     * Collect messages into fixed-size batches.
     * Sending multiple messages at once hides which message triggered the transmission.
     */
    fun batchMessages(pending: List<Message>, maxBatchSize: Int = 5): List<List<Message>> =
        pending.chunked(maxBatchSize)
}
