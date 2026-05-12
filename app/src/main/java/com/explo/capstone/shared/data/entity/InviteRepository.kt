package com.explo.capstone.shared.data.entity

import com.explo.capstone.transport.AstraApiClient
import com.explo.capstone.transport.InviteSummaryDto
import com.explo.capstone.transport.IssueInviteResponse
import com.explo.capstone.transport.RedeemInviteResponse

/**
 * Mission invite operations. Wraps the AstraApiClient with the project's
 * Result<T> idiom and handles cases where the remote is not configured.
 *
 * State for invites is server-authoritative — the next /api/sync after any
 * mutation here re-pulls the truth into the in-memory store. This repo
 * therefore does not maintain its own StateFlow.
 */
interface InviteRepository {

    /** CHIEF on the mission generates a fresh single-use invite token. */
    suspend fun issue(
        missionId: String,
        defaultRank: String? = null,
        ttlHours: Int? = null,
    ): Result<IssueInviteResponse>

    /** Caller redeems an invite token; returns inviter fingerprint for SAS. */
    suspend fun redeem(token: String): Result<RedeemInviteResponse>

    /** CHIEF confirms a PENDING participant after out-of-band SAS check. */
    suspend fun confirm(missionId: String, userId: String): Result<Unit>

    /** CHIEF lists open + spent invites for a mission. */
    suspend fun list(missionId: String): Result<List<InviteSummaryDto>>

    /** CHIEF revokes an unredeemed invite token. */
    suspend fun revoke(token: String): Result<Unit>
}

class RemoteInviteRepository(
    private val remote: AstraApiClient?,
) : InviteRepository {

    override suspend fun issue(
        missionId: String,
        defaultRank: String?,
        ttlHours: Int?,
    ): Result<IssueInviteResponse> =
        remote?.issueInvite(missionId, defaultRank, ttlHours)
            ?: Result.failure(IllegalStateException("Server not configured"))

    override suspend fun redeem(token: String): Result<RedeemInviteResponse> =
        remote?.redeemInvite(token)
            ?: Result.failure(IllegalStateException("Server not configured"))

    override suspend fun confirm(missionId: String, userId: String): Result<Unit> =
        remote?.confirmParticipant(missionId, userId)
            ?: Result.failure(IllegalStateException("Server not configured"))

    override suspend fun list(missionId: String): Result<List<InviteSummaryDto>> =
        remote?.listInvites(missionId)
            ?: Result.failure(IllegalStateException("Server not configured"))

    override suspend fun revoke(token: String): Result<Unit> =
        remote?.revokeInvite(token)
            ?: Result.failure(IllegalStateException("Server not configured"))
}

/**
 * Canonical encoding of an invite token for QR / clipboard / deep-link transport.
 *
 *   astrasecure://invite/<TOKEN>
 *
 * The path component is the raw token. Both the redeem screen's paste field and
 * the QR scanner accept either the bare token or the full URI; this module is
 * the single source of truth for the format.
 */
object InviteUri {
    const val SCHEME = "astrasecure"
    const val HOST = "invite"

    /** Build a canonical invite URI from a raw token. */
    fun encode(token: String): String = "$SCHEME://$HOST/${token.trim()}"

    /** Extract a raw token from either a canonical URI or a bare token string.
     *  Returns null if input is empty after trimming. */
    fun decode(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val prefix = "$SCHEME://$HOST/"
        return if (trimmed.startsWith(prefix, ignoreCase = true)) {
            trimmed.removePrefix(prefix).trim().ifEmpty { null }
        } else {
            trimmed
        }
    }
}
