package com.explo.capstone.transport

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.IOException
import java.util.concurrent.TimeUnit

// ─── Retrofit API surface ─────────────────────────────────────────────────────

private interface SignalApi {
    @POST("v1/users")
    suspend fun register(@Body request: RegisterRequest): RegisterResponse

    @GET("v1/keys/{userId}")
    suspend fun fetchPreKeyBundle(@Path("userId") userId: String): PreKeyBundleResponse

    @PUT("v1/keys")
    suspend fun uploadPreKeys(@Body request: UploadPreKeysRequest)

    @PUT("v1/keys/signed")
    suspend fun uploadSignedPreKey(@Body dto: SignedPreKeyDto)

    @PUT("v1/keys/kyber")
    suspend fun uploadKyberPreKey(@Body dto: KyberPreKeyDto)

    @POST("v1/messages/{recipientId}")
    suspend fun sendMessage(@Path("recipientId") recipientId: String, @Body request: SendMessageRequest)

    @GET("v1/messages")
    suspend fun fetchMessages(@Header("Authorization") token: String): FetchMessagesResponse

    @DELETE("v1/messages")
    suspend fun acknowledgeMessages(@Body request: AckMessagesRequest)

    @POST("v1/channels/{channelId}/members")
    suspend fun joinChannel(@Path("channelId") channelId: String, @Body request: JoinChannelRequest)

    @GET("v1/channels/{channelId}/members")
    suspend fun getChannelMembers(@Path("channelId") channelId: String): ChannelMembersResponse

    @DELETE("v1/users/{userId}")
    suspend fun deleteUser(@Path("userId") userId: String)
}

// ─── Client ───────────────────────────────────────────────────────────────────

/**
 * Owner: Tejas Khanna
 * HTTP + WebSocket client for the AstraSecure Signal relay server.
 *
 * All network calls are coroutine-safe. The WebSocket connection is kept alive
 * while the app is in the foreground; [observeIncoming] returns a cold Flow that
 * opens the socket on collection and closes it when cancelled.
 *
 * When [serverUrl] is blank or the server is unreachable, all operations fail
 * gracefully — the app falls back to local AES-GCM mode automatically.
 */
class SignalServerClient(
    private val serverUrl: String,
    initialJwt: String = "",
    private val onJwtSaved: (String) -> Unit = {},
) : MessageTransport {

    companion object {
        private const val TAG = "SignalServerClient"
        private const val WS_PATH = "v1/websocket"
    }

    private var jwt: String = initialJwt
    private var localUserId: String = ""
    private val _connectionState = MutableStateFlow(false)
    override val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()
    override val isConnected: Boolean get() = _connectionState.value

    private val _keysNeeded = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    override val keysNeeded: Flow<Int> = _keysNeeded.asSharedFlow()

    private val _syncInvalidated = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    override val syncInvalidated: Flow<Long> = _syncInvalidated.asSharedFlow()

    private val _operativeBurned = MutableSharedFlow<BurnedEvent>(extraBufferCapacity = 10)
    override val operativeBurned: Flow<BurnedEvent> = _operativeBurned.asSharedFlow()

    private val okHttp: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val req = if (jwt.isNotEmpty()) {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $jwt")
                        .build()
                } else chain.request()
                chain.proceed(req)
            }
            .build()
    }

    private val api: SignalApi by lazy {
        if (serverUrl.isBlank()) throw IllegalStateException("Server URL not configured")
        Retrofit.Builder()
            .baseUrl(serverUrl.trimEnd('/') + "/")
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SignalApi::class.java)
    }

    // ─── Registration ─────────────────────────────────────────────────────────

    override suspend fun register(
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
    ): Result<String> = runCatching {
        if (serverUrl.isBlank()) return Result.failure(IllegalStateException("No server"))
        val response = api.register(
            RegisterRequest(
                userId = userId,
                displayName = displayName,
                registrationId = registrationId,
                identityKey = b64(identityKeyBytes),
                signedPreKey = SignedPreKeyDto(
                    id = signedPreKeyId,
                    publicKey = b64(signedPreKeyPublicBytes),
                    signature = b64(signedPreKeySignature),
                ),
                oneTimePreKeys = oneTimePreKeys.map { (id, pub) -> PreKeyDto(id, b64(pub)) },
                kyberPreKey = KyberPreKeyDto(
                    id = kyberPreKeyId,
                    publicKey = b64(kyberPreKeyPublicBytes),
                    signature = b64(kyberPreKeySignature),
                ),
            )
        )
        localUserId = userId
        jwt = response.token
        onJwtSaved(jwt)
        _connectionState.value = true
        response.token
    }.onFailure { Log.w(TAG, "register failed: ${it.message}") }

    // ─── Pre-key operations ───────────────────────────────────────────────────

    override suspend fun fetchPreKeyBundle(userId: String): PreKeyBundleResponse {
        return api.fetchPreKeyBundle(userId)
    }

    override suspend fun uploadPreKeys(oneTimePreKeys: List<Pair<Int, ByteArray>>): Result<Unit> =
        runCatching {
            api.uploadPreKeys(UploadPreKeysRequest(
                oneTimePreKeys.map { (id, pub) -> PreKeyDto(id, b64(pub)) }
            ))
        }.onFailure { Log.w(TAG, "uploadPreKeys failed: ${it.message}") }

    override suspend fun uploadSignedPreKey(id: Int, publicKeyBytes: ByteArray, signature: ByteArray): Result<Unit> =
        runCatching {
            api.uploadSignedPreKey(SignedPreKeyDto(id, b64(publicKeyBytes), b64(signature)))
        }.onFailure { Log.w(TAG, "uploadSignedPreKey failed: ${it.message}") }

    override suspend fun uploadKyberPreKey(id: Int, publicKeyBytes: ByteArray, signature: ByteArray): Result<Unit> =
        runCatching {
            api.uploadKyberPreKey(KyberPreKeyDto(id, b64(publicKeyBytes), b64(signature)))
        }.onFailure { Log.w(TAG, "uploadKyberPreKey failed: ${it.message}") }

    // ─── Messaging ────────────────────────────────────────────────────────────

    override suspend fun sendMessage(
        recipientId: String,
        channelId: String,
        ciphertext: ByteArray,
        messageType: Int,
    ): Result<Unit> = runCatching {
        api.sendMessage(
            recipientId,
            SendMessageRequest(
                senderId = "",   // server extracts from JWT
                channelId = channelId,
                messageType = messageType,
                ciphertext = b64(ciphertext),
            )
        )
    }.onFailure { Log.w(TAG, "sendMessage failed: ${it.message}") }

    override suspend fun acknowledge(messageIds: List<Long>) {
        runCatching { api.acknowledgeMessages(AckMessagesRequest(messageIds)) }
    }

    // ─── Channel membership ───────────────────────────────────────────────────

    override suspend fun joinChannel(channelId: String): Result<Unit> =
        runCatching {
            api.joinChannel(channelId, JoinChannelRequest(userId = localUserId))
        }.onFailure { Log.w(TAG, "joinChannel failed: ${it.message}") }

    override suspend fun getChannelMembers(channelId: String): Result<List<String>> =
        runCatching {
            api.getChannelMembers(channelId).memberIds
        }.onFailure { Log.w(TAG, "getChannelMembers failed: ${it.message}") }

    // ─── User deletion (panic wipe) ───────────────────────────────────────────

    override suspend fun deleteUser(userId: String): Result<Unit> =
        runCatching {
            api.deleteUser(userId)
        }.onFailure { Log.w(TAG, "deleteUser failed: ${it.message}") }

    // ─── WebSocket (real-time incoming messages) ──────────────────────────────

    override fun observeIncoming(): Flow<TransportMessage> {
        if (serverUrl.isBlank() || jwt.isBlank()) return emptyFlow()

        return callbackFlow {
            val wsUrl = serverUrl
                .trimEnd('/')
                .replace("http://", "ws://")
                .replace("https://", "wss://") + "/$WS_PATH"

            val request = Request.Builder().url(wsUrl).build()
            val ws = okHttp.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // Send JWT as first frame rather than exposing it in the URL query string
                    webSocket.send(JSONObject().apply {
                        put("type", "auth")
                        put("token", jwt)
                    }.toString())
                    _connectionState.value = true
                    Log.i(TAG, "WebSocket connected")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val json = JSONObject(text)
                        when (json.getString("type")) {
                            "message" -> {
                                val msg = TransportMessage(
                                    id = json.getLong("id"),
                                    senderId = json.getString("senderId"),
                                    channelId = json.getString("channelId"),
                                    messageType = json.getInt("messageType"),
                                    ciphertext = Base64.decode(json.getString("ciphertext"), Base64.DEFAULT),
                                )
                                trySend(msg)
                                // ACK immediately via WebSocket text frame
                                webSocket.send(JSONObject().apply {
                                    put("type", "ack")
                                    put("messageIds", JSONArray().apply { put(msg.id) })
                                }.toString())
                            }
                            "keysNeeded" -> {
                                val count = json.optInt("currentCount", 0)
                                Log.i(TAG, "Server requests OPK replenishment (count=$count)")
                                _keysNeeded.tryEmit(count)
                            }
                            "sync_invalidated" -> {
                                val version = json.optLong("version", 0L)
                                Log.i(TAG, "sync_invalidated version=$version reason=${json.optString("reason")}")
                                _syncInvalidated.tryEmit(version)
                            }
                            "operative_burned" -> {
                                val userId   = json.optString("userId", "")
                                val callsign = json.optString("callsign", userId.take(8).uppercase())
                                Log.i(TAG, "operative_burned userId=$userId callsign=$callsign")
                                _operativeBurned.tryEmit(BurnedEvent(userId, callsign))
                            }
                        }
                    }.onFailure { Log.w(TAG, "WS message parse error: ${it.message}") }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _connectionState.value = false
                    Log.w(TAG, "WebSocket failure: ${t.message}")
                    close(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _connectionState.value = false
                    close()
                }
            })

            awaitClose { ws.close(1000, "flow cancelled") }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)
}
