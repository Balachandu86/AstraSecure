package com.explo.capstone.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owner: Tejas Khanna
 * Abstracts all network I/O. Implementations: [SignalServerClient] (real server)
 * and a test double for unit tests.
 */
interface MessageTransport {

    /** True when the server is reachable and the JWT token is valid. */
    val isConnected: Boolean

    /** Reactive connection state — emits true when connected, false otherwise. */
    val connectionState: StateFlow<Boolean>

    /**
     * Hot flow of server-pushed "keysNeeded" events. Emits the current OPK count
     * on the server whenever it drops below 10. Collect this and call
     * [com.explo.capstone.crypto.signal.SignalKeyManager.replenishPreKeys].
     */
    val keysNeeded: Flow<Int>

    /**
     * Hot flow of server-pushed "sync_invalidated" events. Emits the new server
     * version number when another client mutated shared state. Collect this and
     * call [com.explo.capstone.shared.AppContainer.syncFromServer].
     */
    val syncInvalidated: Flow<Long>

    /**
     * Hot flow of server-pushed "operative_burned" events. Emits when a peer
     * calls [POST /api/users/me/tombstone] (panic wipe). The server sends this
     * to all online peers before removing the operative from missions.
     */
    val operativeBurned: Flow<BurnedEvent>

    /**
     * Send an encrypted message to [recipientId] via the relay server.
     * [channelId] is attached for routing; the server stores it alongside the ciphertext.
     */
    suspend fun sendMessage(
        recipientId: String,
        channelId: String,
        ciphertext: ByteArray,
        messageType: Int,
    ): Result<Unit>

    /**
     * Hot flow of incoming messages pushed from the server (WebSocket) or
     * polled via GET /v1/messages. Emissions drive [IncomingMessageHandler].
     */
    fun observeIncoming(): Flow<TransportMessage>

    /**
     * Acknowledge successful decryption of [messageIds] so the server
     * removes them from the queue.
     */
    suspend fun acknowledge(messageIds: List<Long>)

    /** Fetch a pre-key bundle for [userId] (used during X3DH session setup). */
    suspend fun fetchPreKeyBundle(userId: String): PreKeyBundleResponse

    /** Register this device's identity + initial pre-keys with the server. */
    suspend fun register(
        userId: String,
        displayName: String,
        registrationId: Int,
        identityKeyBytes: ByteArray,
        signedPreKeyId: Int,
        signedPreKeyPublicBytes: ByteArray,
        signedPreKeySignature: ByteArray,
        oneTimePreKeys: List<Pair<Int, ByteArray>>,
        kyberPreKeyId: Int,
        kyberPreKeyPublicBytes: ByteArray,
        kyberPreKeySignature: ByteArray,
    ): Result<String>   // returns JWT token on success

    /** Upload a new batch of one-time pre-keys (called when OPK count drops below threshold). */
    suspend fun uploadPreKeys(oneTimePreKeys: List<Pair<Int, ByteArray>>): Result<Unit>

    /** Upload a new signed pre-key (called during weekly rotation). */
    suspend fun uploadSignedPreKey(
        id: Int,
        publicKeyBytes: ByteArray,
        signature: ByteArray,
    ): Result<Unit>

    /** Upload a new Kyber-1024 pre-key (called during weekly rotation). */
    suspend fun uploadKyberPreKey(
        id: Int,
        publicKeyBytes: ByteArray,
        signature: ByteArray,
    ): Result<Unit>

    /** Join a channel (registers device as a member on the server). */
    suspend fun joinChannel(channelId: String): Result<Unit>

    /** Get the list of member user IDs for a channel. */
    suspend fun getChannelMembers(channelId: String): Result<List<String>>

    /**
     * Delete the caller's server-side registration (pre-keys, queued messages, membership).
     * Called during panic wipe so no new sessions can be established with the wiped identity.
     */
    suspend fun deleteUser(userId: String): Result<Unit>
}
