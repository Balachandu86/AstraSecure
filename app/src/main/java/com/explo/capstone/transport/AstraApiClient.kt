package com.explo.capstone.transport

import com.explo.capstone.shared.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

// ─── Response DTOs ────────────────────────────────────────────────────────────

data class SyncResponse(
    val version: Long,
    val etag: String,
    val schema: SyncSchema,
    val missions: List<MissionDto>,
    val channels: List<ChannelDto>,
    val clearances: List<ClearanceDto>,
)

data class SyncSchema(
    val ranks: List<RankDto>,
    val channelCategories: List<ChannelCategoryDto>,
    val messageCategories: List<MessageCategoryDto>,
    val missionTypes: List<MissionTypeDto>,
)

data class MissionDto(
    val id: String,
    val name: String,
    val typeId: String,
    val status: String,
    val phase: String?,
    val missionKeyAlias: String = "",
    val participantIds: List<String> = emptyList(),
    val pendingParticipants: List<PendingParticipantDto> = emptyList(),
    val myStatus: String = "ACTIVE",
    val inviterId: String? = null,
    val inviterFingerprint: String? = null,
    val createdAtMs: Long,
    val lastActivityMs: Long,
    val createdBy: String = "",
)

data class PendingParticipantDto(
    val userId: String,
    val callsign: String? = null,
    val invitedBy: String? = null,
    val invitedAtMs: Long = 0,
    val fingerprint: String? = null,
)

data class ChannelDto(
    val id: String,
    val missionId: String,
    val name: String,
    val description: String,
    val categoryId: String,
    val minClearanceToView: Int,
    val minClearanceToPost: Int,
    val createdAtMs: Long,
    val createdBy: String = "",
)

data class RankDto(
    val id: String,
    val name: String,
    val level: Int,
    val color: String,
    val isSystem: Boolean,
)

data class ChannelCategoryDto(
    val id: String,
    val name: String,
    val accent: String,
    val defaultMinClearanceToView: Int,
    val defaultMinClearanceToPost: Int,
    val isSystem: Boolean,
)

data class MessageCategoryDto(
    val id: String,
    val name: String,
    val accent: String,
    val minClearanceToSend: Int,
    val isSystem: Boolean,
)

data class MissionTypeDto(
    val id: String,
    val name: String,
    val accent: String,
    val description: String,
    val isSystem: Boolean,
)

data class ClearanceDto(
    val userId: String,
    val missionId: String,
    val rankId: String,
)

// ─── Request bodies ───────────────────────────────────────────────────────────

data class RegisterUserRequest(val callsign: String)
data class CreateMissionRequest(val name: String, val typeId: String)
// Null fields omitted by Gson — server uses COALESCE, so only provided fields change.
data class UpdateMissionRequest(val status: String? = null, val phase: String? = null)
data class AddParticipantRequest(val userId: String)
data class CreateChannelRequest(
    val missionId: String,
    val name: String,
    val description: String,
    val categoryId: String,
    val minClearanceToView: Int?,
    val minClearanceToPost: Int?,
)
data class UpdateChannelRequest(
    val minClearanceToView: Int? = null,
    val minClearanceToPost: Int? = null,
)
data class AssignClearanceRequest(val userId: String, val missionId: String, val rankId: String)

// ─── Invite DTOs ──────────────────────────────────────────────────────────────

data class IssueInviteRequest(
    val defaultRank: String? = null,    // server defaults to rank_observer when null
    val ttlHours: Int? = null,          // server defaults to 24h
)

data class IssueInviteResponse(
    val token: String,
    val missionId: String,
    val defaultRank: String,
    val expiresAt: String,              // ISO-8601
)

data class RedeemInviteResponse(
    val missionId: String,
    val missionName: String,
    val issuedBy: String,
    val issuerFingerprint: String?,
    val defaultRank: String,
    val status: String,                 // always "PENDING" today
)

data class InviteSummaryDto(
    val token: String,
    val defaultRank: String,
    val expiresAt: String?,
    val redeemedBy: String?,
    val redeemedAt: String?,
    val revokedAt: String?,
    val createdAt: String?,
)

data class ListInvitesResponse(val invites: List<InviteSummaryDto>)

// ─── Retrofit interface ───────────────────────────────────────────────────────

private interface AstraApi {

    @POST("api/users")
    suspend fun registerUser(@Body req: RegisterUserRequest)

    @POST("api/users/me/tombstone")
    suspend fun tombstone()

    // If-None-Match is omitted when null — Retrofit skips null @Header values.
    @GET("api/sync")
    suspend fun sync(@Header("If-None-Match") etag: String?): Response<SyncResponse>

    @POST("api/missions")
    suspend fun createMission(@Body req: CreateMissionRequest): MissionDto

    @PUT("api/missions/{id}")
    suspend fun updateMission(@Path("id") id: String, @Body req: UpdateMissionRequest)

    @DELETE("api/missions/{id}")
    suspend fun deleteMission(@Path("id") id: String)

    @POST("api/missions/{id}/participants")
    suspend fun addParticipant(@Path("id") missionId: String, @Body req: AddParticipantRequest)

    @DELETE("api/missions/{missionId}/participants/{userId}")
    suspend fun removeParticipant(
        @Path("missionId") missionId: String,
        @Path("userId") userId: String,
    )

    @POST("api/channels")
    suspend fun createChannel(@Body req: CreateChannelRequest): ChannelDto

    @PUT("api/channels/{id}")
    suspend fun updateChannel(@Path("id") id: String, @Body req: UpdateChannelRequest)

    @DELETE("api/channels/{id}")
    suspend fun deleteChannel(@Path("id") id: String)

    @PUT("api/clearances")
    suspend fun assignClearance(@Body req: AssignClearanceRequest)

    @DELETE("api/clearances/{userId}/{missionId}")
    suspend fun removeClearance(
        @Path("userId") userId: String,
        @Path("missionId") missionId: String,
    )

    // ─── Invites ──────────────────────────────────────────────────────────────

    @POST("api/missions/{id}/invites")
    suspend fun issueInvite(
        @Path("id") missionId: String,
        @Body req: IssueInviteRequest,
    ): IssueInviteResponse

    @GET("api/missions/{id}/invites")
    suspend fun listInvites(@Path("id") missionId: String): ListInvitesResponse

    @DELETE("api/invites/{token}")
    suspend fun revokeInvite(@Path("token") token: String)

    @POST("api/invites/{token}/redeem")
    suspend fun redeemInvite(@Path("token") token: String): RedeemInviteResponse

    @POST("api/missions/{missionId}/participants/{userId}/confirm")
    suspend fun confirmParticipant(
        @Path("missionId") missionId: String,
        @Path("userId") userId: String,
    )
}

// ─── Client ───────────────────────────────────────────────────────────────────

class AstraApiClient(
    serverUrl: String,
    private val jwtProvider: () -> String,
) {
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .addInterceptor { chain ->
            val token = jwtProvider()
            val req = if (token.isNotEmpty())
                chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            else chain.request()
            chain.proceed(req)
        }
        .build()

    private val api: AstraApi = Retrofit.Builder()
        .baseUrl(serverUrl.trimEnd('/') + "/")
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AstraApi::class.java)

    suspend fun registerUser(callsign: String): Result<Unit> = runCatching {
        api.registerUser(RegisterUserRequest(callsign))
    }

    /** Returns null on 304 (client is already up to date). */
    suspend fun sync(currentEtag: String? = null): Result<SyncResponse?> = runCatching {
        val resp = api.sync(currentEtag)
        if (resp.code() == 304) null else resp.body()
    }

    suspend fun tombstone(): Result<Unit> = runCatching { api.tombstone() }

    suspend fun createMission(name: String, typeId: String): Result<MissionDto> = runCatching {
        api.createMission(CreateMissionRequest(name, typeId))
    }

    suspend fun updateMissionStatus(id: String, status: String): Result<Unit> = runCatching {
        api.updateMission(id, UpdateMissionRequest(status = status))
    }

    suspend fun updateMissionPhase(id: String, phase: String?): Result<Unit> = runCatching {
        // Null phase sent as omitted field — server COALESCE keeps existing value.
        // Clearing phase (setting to null) is not supported by this endpoint;
        // the next /api/sync will restore server truth regardless.
        if (phase != null) api.updateMission(id, UpdateMissionRequest(phase = phase))
    }

    suspend fun deleteMission(id: String): Result<Unit> = runCatching {
        api.deleteMission(id)
    }

    suspend fun addParticipant(missionId: String, userId: String): Result<Unit> = runCatching {
        api.addParticipant(missionId, AddParticipantRequest(userId))
    }

    suspend fun removeParticipant(missionId: String, userId: String): Result<Unit> = runCatching {
        api.removeParticipant(missionId, userId)
    }

    suspend fun createChannel(
        missionId: String,
        name: String,
        description: String,
        categoryId: String,
        minClearanceToView: Int?,
        minClearanceToPost: Int?,
    ): Result<ChannelDto> = runCatching {
        api.createChannel(
            CreateChannelRequest(missionId, name, description, categoryId, minClearanceToView, minClearanceToPost)
        )
    }

    suspend fun updateChannelClearance(channelId: String, view: Int, post: Int): Result<Unit> = runCatching {
        api.updateChannel(channelId, UpdateChannelRequest(minClearanceToView = view, minClearanceToPost = post))
    }

    suspend fun deleteChannel(channelId: String): Result<Unit> = runCatching {
        api.deleteChannel(channelId)
    }

    suspend fun assignClearance(userId: String, missionId: String, rankId: String): Result<Unit> = runCatching {
        api.assignClearance(AssignClearanceRequest(userId, missionId, rankId))
    }

    suspend fun removeClearance(userId: String, missionId: String): Result<Unit> = runCatching {
        api.removeClearance(userId, missionId)
    }

    // ─── Invites ──────────────────────────────────────────────────────────────

    suspend fun issueInvite(
        missionId: String,
        defaultRank: String? = null,
        ttlHours: Int? = null,
    ): Result<IssueInviteResponse> = runCatching {
        api.issueInvite(missionId, IssueInviteRequest(defaultRank, ttlHours))
    }

    suspend fun listInvites(missionId: String): Result<List<InviteSummaryDto>> = runCatching {
        api.listInvites(missionId).invites
    }

    suspend fun revokeInvite(token: String): Result<Unit> = runCatching {
        api.revokeInvite(token)
    }

    suspend fun redeemInvite(token: String): Result<RedeemInviteResponse> = runCatching {
        api.redeemInvite(token)
    }

    suspend fun confirmParticipant(missionId: String, userId: String): Result<Unit> = runCatching {
        api.confirmParticipant(missionId, userId)
    }
}

// ─── Domain model converters ──────────────────────────────────────────────────

private fun String.toColorToken(): ColorToken =
    runCatching { ColorToken.valueOf(this) }.getOrDefault(ColorToken.PRIMARY)

fun MissionDto.toDomain() = Mission(
    id = id,
    name = name,
    typeId = typeId,
    status = runCatching { MissionStatus.valueOf(status) }.getOrDefault(MissionStatus.ACTIVE),
    phase = phase,
    missionKeyAlias = missionKeyAlias,
    participantIds = participantIds,
    pendingParticipants = pendingParticipants.map {
        PendingParticipant(
            userId = it.userId,
            callsign = it.callsign.orEmpty(),
            invitedBy = it.invitedBy.orEmpty(),
            invitedAtMs = it.invitedAtMs,
            fingerprint = it.fingerprint.orEmpty(),
        )
    },
    myStatus = runCatching { ParticipantStatus.valueOf(myStatus) }.getOrDefault(ParticipantStatus.ACTIVE),
    inviterId = inviterId,
    inviterFingerprint = inviterFingerprint,
    createdAtMs = createdAtMs,
    lastActivityMs = lastActivityMs,
    createdBy = createdBy,
)

fun ChannelDto.toDomain() = Channel(
    id = id,
    missionId = missionId,
    name = name,
    description = description,
    categoryId = categoryId,
    minClearanceToView = minClearanceToView,
    minClearanceToPost = minClearanceToPost,
    createdAtMs = createdAtMs,
    createdBy = createdBy,
)

fun RankDto.toDomain() = Rank(
    id = id,
    name = name,
    level = level,
    color = color.toColorToken(),
    isSystem = isSystem,
)

fun ChannelCategoryDto.toDomain() = ChannelCategory(
    id = id,
    name = name,
    accent = accent.toColorToken(),
    defaultMinClearanceToView = defaultMinClearanceToView,
    defaultMinClearanceToPost = defaultMinClearanceToPost,
    isSystem = isSystem,
)

fun MessageCategoryDto.toDomain() = MessageCategory(
    id = id,
    name = name,
    accent = accent.toColorToken(),
    minClearanceToSend = minClearanceToSend,
    isSystem = isSystem,
)

fun MissionTypeDto.toDomain() = MissionType(
    id = id,
    name = name,
    accent = accent.toColorToken(),
    description = description,
    isSystem = isSystem,
)

fun ClearanceDto.toDomain() = ClearanceAssignment(
    userId = userId,
    missionId = missionId,
    rankId = rankId,
)
