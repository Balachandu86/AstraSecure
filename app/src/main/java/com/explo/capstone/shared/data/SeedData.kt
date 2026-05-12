package com.explo.capstone.shared.data

import com.explo.capstone.shared.*

/**
 * Seeds required schema records on first launch and after snapshot restore.
 * No demo missions are ever seeded — the app starts with an empty mission queue.
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
        MissionType("mt_top_secret",   "TOP SECRET",   ColorToken.TERTIARY, "Maximum sensitivity operations",       isSystem = true),
        MissionType("mt_confidential", "CONFIDENTIAL", ColorToken.NEUTRAL,  "Restricted access operations",         isSystem = true),
        MissionType("mt_restricted",   "RESTRICTED",   ColorToken.ERROR,    "Compromised or quarantined operations", isSystem = true),
    )

    // ─── Seed loaders ────────────────────────────────────────────────────────

    /**
     * Seeds required schema records (ranks, categories, types).
     * Safe to call on any build type — all records are system defaults that must
     * exist for the app to function. No missions or channels are ever added here.
     */
    fun seedSchema(store: InMemoryStore) {
        store.updateRanks { ranks.sortedByDescending { it.level } }
        store.updateChannelCategories { channelCategories }
        store.updateMessageCategories { messageCategories }
        store.updateMissionTypes { missionTypes }
    }
}
