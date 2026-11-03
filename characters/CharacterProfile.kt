package com.slusarchuk.kingpedro.characters

import kotlinx.serialization.Serializable

/**
 * Data-driven character definition. Loaded from assets/characters.json at startup.
 * New characters — including family tribute characters — are added by editing the
 * JSON file only. No code changes required.
 */
@Serializable
data class CharacterProfile(
    val id: String,
    val name: String,
    val seatDefault: String,
    val portrait: String,
    val attitudeTag: String,
    val bioShort: String,
    val skillStars: Int,
    val isTribute: Boolean = false,
    val behaviorProfile: BehaviorProfile,
    val voice: VoiceProfile,
    val barks: Map<String, List<String>>
)

@Serializable
data class BehaviorProfile(
    val bidAggression: Float,
    val riskTolerance: Float,
    val renegeAvoidance: Float,
    val bluffiness: Float
)

/**
 * IMPORTANT: Ukrainian phrases in bark lines are English-transliterated
 * (e.g. "Slava Bohu") so they render correctly even when only an en-US voice
 * is available on-device. Never substitute Polish for Ukrainian in this data set.
 */
@Serializable
data class VoiceProfile(
    val locale: String,
    val pitch: Float,
    val speechRate: Float
)

@Serializable
data class CharacterRoster(
    val characters: List<CharacterProfile>
)

enum class BarkTrigger(val key: String) {
    GAME_START_INTRO("game_start_intro"),
    BID_HIGH("bid_high"),
    BID_LOW_OR_PASS("bid_low_or_pass"),
    WIN_TRICK("win_trick"),
    LOSE_TRICK("lose_trick"),
    RENEGE_OR_PENALTY("renege_or_penalty"),
    BIG_SCORE_GAIN("big_score_gain"),
    BIG_SCORE_LOSS("big_score_loss"),
    TAUNT_HUMAN("taunt_human"),
    PARTNER_PRAISE("partner_praise"),
    PARTNER_SCOLD("partner_scold"),
    IDLE_TOO_LONG("idle_too_long"),
    GAME_WIN("game_win"),
    GAME_LOSS("game_loss");

    companion object {
        val PRIORITY = listOf(
            IDLE_TOO_LONG, BID_LOW_OR_PASS, BID_HIGH, WIN_TRICK, LOSE_TRICK,
            PARTNER_PRAISE, PARTNER_SCOLD, TAUNT_HUMAN, RENEGE_OR_PENALTY,
            BIG_SCORE_LOSS, BIG_SCORE_GAIN, GAME_LOSS, GAME_WIN, GAME_START_INTRO
        )
        fun priorityOf(trigger: BarkTrigger): Int = PRIORITY.indexOf(trigger)
    }
}

enum class Seat { SOUTH, WEST, NORTH, EAST }

data class TableAssignment(
    val south: CharacterProfile?,
    val west: CharacterProfile?,
    val north: CharacterProfile?,
    val east: CharacterProfile?
) {
    fun opponentSeats(): List<CharacterProfile> = listOfNotNull(west, east)
    fun partnerSeat(): CharacterProfile? = north
}
