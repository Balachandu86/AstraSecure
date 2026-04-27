package com.explo.capstone.shared.data

import com.explo.capstone.shared.*

/**
 * Debug-build seed data — populates schema records and sample missions on first launch.
 * Gated by BuildConfig.DEBUG at the call site (AstraApp).
 */
object SeedData {

    // ─── Ranks ───────────────────────────────────────────────────────────────

    val ranks = listOf(
        Rank("rank_observer",  "OBSERVER",  level = 1, color = ColorToken.NEUTRAL,  isSystem = true),
        Rank("rank_operative", "OPERATIVE", level = 5, color = ColorToken.PRIMARY,  isSystem = true),
        Rank("rank_chief",     "CHIEF",     level = 9, color = ColorToken.TERTIARY, isSystem = true),
    )

    // ─── Channel categories ──────────────────────────────────────────────────

    val channelCategories = listOf(
        ChannelCategory("cc_command",   "COMMAND & CONTROL",             ColorToken.SECONDARY, defaultMinClearanceToView = 9, defaultMinClearanceToPost = 9, isSystem = true),
        ChannelCategory("cc_recon",     "RECONNAISSANCE & INTELLIGENCE", ColorToken.PRIMARY,   defaultMinClearanceToView = 5, defaultMinClearanceToPost = 5, isSystem = true),
        ChannelCategory("cc_logistics", "LOGISTICS & DEPLOYMENT",        ColorToken.NEUTRAL,   defaultMinClearanceToView = 1, defaultMinClearanceToPost = 1, isSystem = true),
    )

    // ─── Message categories ──────────────────────────────────────────────────

    val messageCategories = listOf(
        MessageCategory("mc_command",    "COMMAND",      ColorToken.SECONDARY, minClearanceToSend = 9, isSystem = true),
        MessageCategory("mc_intel",      "INTELLIGENCE", ColorToken.PRIMARY,   minClearanceToSend = 5, isSystem = true),
        MessageCategory("mc_standard",   "STANDARD",     ColorToken.NEUTRAL,   minClearanceToSend = 1, isSystem = true),
        MessageCategory("mc_restricted", "RESTRICTED",   ColorToken.ERROR,     minClearanceToSend = 9, isSystem = true),
    )

    // ─── Mission types ───────────────────────────────────────────────────────

    val missionTypes = listOf(
        MissionType("mt_top_secret",   "TOP SECRET",   ColorToken.TERTIARY, "Maximum sensitivity operations",             isSystem = true),
        MissionType("mt_confidential", "CONFIDENTIAL", ColorToken.NEUTRAL,  "Restricted access operations",               isSystem = true),
        MissionType("mt_restricted",   "RESTRICTED",   ColorToken.ERROR,    "Compromised or quarantined operations",       isSystem = true),
    )

    // ─── Sample missions ─────────────────────────────────────────────────────

    private val now = System.currentTimeMillis()

    val missions = listOf(
        Mission(
            id = "OSS-9921", name = "Operation Silent Sentinel",
            typeId = "mt_top_secret", status = MissionStatus.ACTIVE,
            phase = "PHASE 2 / SURVEILLANCE",
            missionKeyAlias = "key_oss_9921",
            participantIds = listOf("user_local"),
            createdAtMs = now - 86_400_000L * 3, lastActivityMs = now - 240_000L, // 4m ago
        ),
        Mission(
            id = "TFA-0042", name = "Task Force Alpha",
            typeId = "mt_confidential", status = MissionStatus.STANDBY,
            missionKeyAlias = "key_tfa_0042",
            participantIds = listOf("user_local"),
            createdAtMs = now - 86_400_000L * 7, lastActivityMs = now - 7_920_000L, // 2h 12m ago
        ),
        Mission(
            id = "VR-8810", name = "Vanguard Recon",
            typeId = "mt_restricted", status = MissionStatus.COMPROMISED,
            missionKeyAlias = "key_vr_8810",
            participantIds = listOf("user_local"),
            createdAtMs = now - 86_400_000L, lastActivityMs = now, // 0s ago
        ),
        Mission(
            id = "SP-3301", name = "Shadow Protocol",
            typeId = "mt_top_secret", status = MissionStatus.ACTIVE,
            phase = "PHASE 1 / INSERTION",
            missionKeyAlias = "key_sp_3301",
            participantIds = listOf("user_local"),
            createdAtMs = now - 86_400_000L * 14, lastActivityMs = now - 1_080_000L, // 18m ago
        ),
    )

    // ─── Sample channels ─────────────────────────────────────────────────────

    val channels = listOf(
        // OSS-9921 channels
        Channel("ch_oss_cmd",   "OSS-9921", "COMMAND NET",           "Primary command channel",           "cc_command",   minClearanceToView = 9, minClearanceToPost = 9, createdAtMs = now - 86_400_000L * 3),
        Channel("ch_oss_recon", "OSS-9921", "RECON ALPHA",           "Forward observation reports",        "cc_recon",     minClearanceToView = 5, minClearanceToPost = 5, createdAtMs = now - 86_400_000L * 3),
        Channel("ch_oss_intel", "OSS-9921", "SIGINT FEED",           "Signal intelligence aggregation",    "cc_recon",     minClearanceToView = 5, minClearanceToPost = 9, createdAtMs = now - 86_400_000L * 2),
        Channel("ch_oss_log",   "OSS-9921", "LOGISTICS",             "Supply and movement coordination",   "cc_logistics", minClearanceToView = 1, minClearanceToPost = 1, createdAtMs = now - 86_400_000L * 3),
        Channel("ch_oss_medic", "OSS-9921", "MEDICAL",               "Medical status and casualty reports", "cc_logistics", minClearanceToView = 1, minClearanceToPost = 5, createdAtMs = now - 86_400_000L * 2),
        Channel("ch_oss_comms", "OSS-9921", "COMMS CHECK",           "Equipment status verification",      "cc_logistics", minClearanceToView = 1, minClearanceToPost = 1, createdAtMs = now - 86_400_000L * 3),
        Channel("ch_oss_exfil", "OSS-9921", "EXFILTRATION PLANNING", "Extraction route coordination",      "cc_command",   minClearanceToView = 9, minClearanceToPost = 9, createdAtMs = now - 86_400_000L),
        Channel("ch_oss_cyber", "OSS-9921", "CYBER OPS",             "Digital warfare coordination",        "cc_recon",     minClearanceToView = 5, minClearanceToPost = 5, createdAtMs = now - 86_400_000L),

        // TFA-0042 channels
        Channel("ch_tfa_cmd",   "TFA-0042", "ALPHA NET",       "Team coordination",           "cc_command",   minClearanceToView = 9, minClearanceToPost = 9, createdAtMs = now - 86_400_000L * 7),
        Channel("ch_tfa_ops",   "TFA-0042", "OPS FLOOR",       "Operational updates",          "cc_recon",     minClearanceToView = 5, minClearanceToPost = 5, createdAtMs = now - 86_400_000L * 7),
        Channel("ch_tfa_log",   "TFA-0042", "SUPPLY CHAIN",    "Logistics tracking",           "cc_logistics", minClearanceToView = 1, minClearanceToPost = 1, createdAtMs = now - 86_400_000L * 6),

        // VR-8810 channel
        Channel("ch_vr_emerg",  "VR-8810",  "EMERGENCY NET",   "Compromised operation channel", "cc_command",   minClearanceToView = 9, minClearanceToPost = 9, createdAtMs = now - 86_400_000L),

        // SP-3301 channels
        Channel("ch_sp_cmd",    "SP-3301",  "SHADOW CMD",       "Shadow protocol command",       "cc_command",   minClearanceToView = 9, minClearanceToPost = 9, createdAtMs = now - 86_400_000L * 14),
        Channel("ch_sp_dead",   "SP-3301",  "DEAD DROP",        "Asynchronous intel exchange",   "cc_recon",     minClearanceToView = 5, minClearanceToPost = 5, createdAtMs = now - 86_400_000L * 14),
        Channel("ch_sp_cover",  "SP-3301",  "COVER STORIES",    "Identity management",           "cc_recon",     minClearanceToView = 5, minClearanceToPost = 9, createdAtMs = now - 86_400_000L * 13),
        Channel("ch_sp_safe",   "SP-3301",  "SAFE HOUSE NET",   "Location tracking",             "cc_logistics", minClearanceToView = 1, minClearanceToPost = 1, createdAtMs = now - 86_400_000L * 14),
        Channel("ch_sp_exfil",  "SP-3301",  "EXFIL CORRIDOR",   "Extraction planning",           "cc_command",   minClearanceToView = 9, minClearanceToPost = 9, createdAtMs = now - 86_400_000L * 10),
        Channel("ch_sp_comms",  "SP-3301",  "COMMS RELAY",      "Communication relay points",    "cc_logistics", minClearanceToView = 1, minClearanceToPost = 1, createdAtMs = now - 86_400_000L * 12),
        Channel("ch_sp_fin",    "SP-3301",  "FINANCE",           "Operational funding",           "cc_logistics", minClearanceToView = 5, minClearanceToPost = 9, createdAtMs = now - 86_400_000L * 11),
        Channel("ch_sp_psych",  "SP-3301",  "PSYCH OPS",        "Psychological operations",      "cc_recon",     minClearanceToView = 5, minClearanceToPost = 5, createdAtMs = now - 86_400_000L * 10),
        Channel("ch_sp_techint","SP-3301",  "TECHINT",           "Technical intelligence",        "cc_recon",     minClearanceToView = 5, minClearanceToPost = 5, createdAtMs = now - 86_400_000L * 9),
        Channel("ch_sp_sere",   "SP-3301",  "SERE PROTOCOLS",   "Survival and evasion",          "cc_logistics", minClearanceToView = 1, minClearanceToPost = 5, createdAtMs = now - 86_400_000L * 8),
        Channel("ch_sp_air",    "SP-3301",  "AIR SUPPORT",      "Air coordination",              "cc_command",   minClearanceToView = 9, minClearanceToPost = 9, createdAtMs = now - 86_400_000L * 7),
        Channel("ch_sp_counter","SP-3301",  "COUNTER-INTEL",    "Counter-intelligence ops",      "cc_recon",     minClearanceToView = 5, minClearanceToPost = 9, createdAtMs = now - 86_400_000L * 6),
    )

    // ─── Seed loader ─────────────────────────────────────────────────────────

    fun seed(store: InMemoryStore) {
        store.updateRanks { ranks.sortedByDescending { it.level } }
        store.updateChannelCategories { channelCategories }
        store.updateMessageCategories { messageCategories }
        store.updateMissionTypes { missionTypes }
        store.updateMissions { missions }
        store.updateChannels { channels }
    }
}
