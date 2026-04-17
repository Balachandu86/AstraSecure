package com.explo.capstone.metadata

import com.explo.capstone.shared.Message
import kotlin.random.Random

/**
 * Owner: Jatin Preet Singh
 * Responsible for: metadata normalization — padding, batching, and randomized transmission timing.
 *
 * TODO Jatin:
 *  1. Implement padMessage() — pad encrypted payload to nearest block size to hide true length
 *  2. Implement batchMessages() — collect messages and release as a batch to obscure frequency
 *  3. Implement randomizedDelay() — add random delay before sending to prevent timing analysis
 *  4. Wire MetadataProcessor into MissionViewModel.sendMessage()
 */
class MetadataProcessor {

    private val blockSizeBytes = 256  // Pad to multiples of this

    /**
     * Pad a message's encrypted payload to the next multiple of blockSizeBytes.
     * This prevents an observer inferring message length from ciphertext size.
     */
    fun padMessage(encryptedBytes: ByteArray): ByteArray {
        // TODO: pad to next multiple of blockSizeBytes with random bytes
        throw NotImplementedError("Jatin: implement padMessage")
    }

    /**
     * Return a random delay in milliseconds to add before transmitting a message.
     * Randomising transmission timing defeats traffic-pattern analysis.
     * @param minMs minimum delay
     * @param maxMs maximum delay
     */
    fun randomizedDelayMs(minMs: Long = 200, maxMs: Long = 2000): Long {
        // TODO: return a secure random delay, not just kotlin.random
        return Random.nextLong(minMs, maxMs)
    }

    /**
     * Collect messages into a batch and return them together.
     * Sending multiple messages at once hides which message triggered the transmission.
     */
    fun batchMessages(pending: List<Message>, maxBatchSize: Int = 5): List<List<Message>> {
        // TODO: implement batching logic with a flush-on-timeout fallback
        throw NotImplementedError("Jatin: implement batchMessages")
    }
}
