package com.ranslu.kingpedro.ai

import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Tier 1 Enhanced PlayerMemory System for King Pedro AI
 *
 * Tier 1 Improvements:
 * A) Hand-Strength Evaluation: pre-compute hand value at round start
 * B) Positional Strategies: leader vs mid-hand vs closer behavior
 * C) Partner Signal System: infer partner holdings from bid history
 */

data class CharacterMemory(
    val seatPosition: Int,
    val characterName: String,
    var bidConfidence: Double = 0.5,

    // Play weights (adjusted per-hand based on situation)
    var winEagerness: Double = 0.5,      // 0=conservative, 1=aggressive
    var feedPartnerBias: Double = 0.5,   // 0=selfish, 1=team-oriented
    var leadTrumpBias: Double = 0.5,     // 0=lead weakness, 1=lead trump

    // Tier 1: Hand-strength tracking
    var lastHandStrength: HandStrength = HandStrength(),

    // Tier 1: Partner bid history (last 5 hands)
    var partnerBidHistory: MutableList<PartnerBidEvent> = mutableListOf(),

    // Raw game outcomes for learning
    var handOutcomes: MutableList<HandOutcome> = mutableListOf(),

    // Timestamp of last update
    var lastUpdated: Long = System.currentTimeMillis()
)

data class HandStrength(
    val hasHigh: Boolean = false,
    val hasLow: Boolean = false,
    val hasJack: Boolean = false,
    val hasPedro: Boolean = false,
    val offPedroCount: Int = 0,
    val trumpCount: Int = 0,
    val strength: Double = 0.0  // 0.0=weak, 1.0=very strong
)

data class PartnerBidEvent(
    val handNumber: Int,
    val partnerBid: Int,
    val partnerTrump: String?,
    val handOutcome: Int  // points won by partner
)

data class HandOutcome(
    val handNumber: Int,
    val biddedPoints: Int,
    val actualPoints: Int,
    val playerDecision: String,  // "tagBid" or "tagPlay"
    val wasCorrection: Boolean = false
)

/**
 * Position-aware decision helper
 */
enum class TablePosition {
    LEADER,      // First to play
    MID_HAND,    // Second or third to play
    CLOSER       // Last to play
}

/**
 * Main PlayerMemory manager - handles all AI memory and decision-making
 */
class PlayerMemory(
    private val memoryDir: String = "king-pedro-memory"
) {
    private val memories = mutableMapOf<Int, CharacterMemory>()
    private val handHistoryDir = File(memoryDir, "hands")

    init {
        File(memoryDir).mkdirs()
        handHistoryDir.mkdirs()
        loadAllMemories()
    }

    /**
     * TIER 1A: Evaluate hand strength at round start
     * Returns 0.0 (weak) to 1.0 (very strong)
     */
    fun evaluateHandStrength(
        heldCards: List<Card>,
        trump: String
    ): HandStrength {
        val trumpCards = heldCards.filter { it.suit == trump }
        val hasHigh = heldCards.any { it.suit == trump && it.rank == "K" }
        val hasLow = heldCards.any { it.suit == trump && it.rank == "3" }
        val hasJack = heldCards.any { it.suit != trump && it.rank == "J" }
        val hasPedro = heldCards.any { it.suit != trump && it.rank == "5" }
        val offPedroCount = heldCards.count { it.rank == "5" && it.suit != trump }

        // Strength calculation: 0-40 points from key cards, normalized
        var points = 0.0
        if (hasHigh) points += 10.0      // King of trump = 10 points
        if (hasLow) points += 1.0        // 3 of trump = 1 point
        if (hasJack) points += 1.0       // Jack off-trump = 1 point
        if (hasPedro) points += 5.0      // 5 of trump = 5 points
        points += (offPedroCount - 1) * 2.0 // Extra 5s = 2 points each
        points += trumpCards.size * 0.5  // Extra trump control

        val strength = min(1.0, points / 20.0)  // Normalize to 0.0-1.0

        return HandStrength(
            hasHigh = hasHigh,
            hasLow = hasLow,
            hasJack = hasJack,
            hasPedro = hasPedro,
            offPedroCount = offPedroCount,
            trumpCount = trumpCards.size,
            strength = strength
        )
    }

    /**
     * TIER 1B: Get position-aware play strategy weights
     * Returns adjusted win/feed/trump weights based on table position
     */
    fun getPositionalWeights(
        position: TablePosition,
        baseMemory: CharacterMemory,
        partnerStrength: Double  // 0.0=weak, 1.0=strong
    ): PositionalWeights {
        return when (position) {
            TablePosition.LEADER -> {
                // Leader sets tone: be aggressive if strong hand, lead trump to flush it
                PositionalWeights(
                    winEagerness = baseMemory.winEagerness * 1.2,  // Bump up aggression
                    feedPartnerBias = 0.3,  // Focus on own strength
                    leadTrumpBias = 0.7     // Lead trump to control
                )
            }
            TablePosition.MID_HAND -> {
                // Mid-hand: read what's been played, adjust based on partner
                PositionalWeights(
                    winEagerness = baseMemory.winEagerness,
                    feedPartnerBias = baseMemory.feedPartnerBias * 1.1,  // Slightly partner-focused
                    leadTrumpBias = 0.5     // Mixed strategy
                )
            }
            TablePosition.CLOSER -> {
                // Closer: know what's out; maximize value recovery
                PositionalWeights(
                    winEagerness = baseMemory.winEagerness * 0.8,  // More selective
                    feedPartnerBias = baseMemory.feedPartnerBias,
                    leadTrumpBias = 0.4     // Play to set up next trick
                )
            }
        }
    }

    /**
     * TIER 1C: Infer partner's likely holdings from bid history
     * Returns a "partner strength estimate" 0.0-1.0
     */
    fun inferPartnerStrength(memory: CharacterMemory): Double {
        if (memory.partnerBidHistory.isEmpty()) return 0.5

        // Analyze recent partner bids (last 5 hands carry more weight)
        var totalBid = 0
        var totalOutcome = 0
        var weight = 0

        for ((idx, event) in memory.partnerBidHistory.takeLast(5).withIndex()) {
            val recencyWeight = (idx + 1).toDouble() / 5.0  // Most recent weighted heavier
            totalBid += (event.partnerBid * recencyWeight).toInt()
            totalOutcome += event.handOutcome
            weight++
        }

        if (weight == 0) return 0.5

        val avgBid = totalBid / weight
        val avgOutcome = totalOutcome / weight

        // If partner bids high and makes it, they're strong
        // If they bid low and make it, they're careful/weak
        val strength = when {
            avgBid >= 20 && avgOutcome >= 15 -> 0.9  // Aggressive and succeeds
            avgBid >= 15 && avgOutcome >= 10 -> 0.7  // Solid bidder
            avgBid >= 10 -> 0.5                       // Conservative
            else -> 0.3                               // Very weak bidder
        }

        return min(1.0, max(0.0, strength))
    }

    /**
     * TIER 1: Update memory after a hand
     * Hybrid learning: small auto-nudge + manual tag correction
     */
    fun updateAfterHand(
        seatPosition: Int,
        biddedPoints: Int,
        actualPoints: Int,
        tagCorrection: String? = null  // "tagBid" or "tagPlay" for manual correction
    ) {
        val memory = memories.getOrPut(seatPosition) {
            CharacterMemory(seatPosition, "Seat$seatPosition")
        }

        val accuracy = if (biddedPoints > 0) actualPoints.toDouble() / biddedPoints else 0.5

        // Auto-nudge learning (small): decay old memory, nudge based on outcome
        val autoNudge = (accuracy - 0.5) * 0.05  // ±2.5% per hand

        if (tagCorrection != null) {
            // Manual correction (stronger): override with bigger nudge
            when (tagCorrection) {
                "tagBid" -> memory.bidConfidence += 0.15 * (accuracy - 0.5).coerceIn(-1.0, 1.0)
                "tagPlay" -> {
                    memory.winEagerness += 0.10 * (accuracy - 0.5).coerceIn(-1.0, 1.0)
                    memory.feedPartnerBias += 0.05 * (if (actualPoints > biddedPoints) 1.0 else -1.0)
                }
            }
        } else {
            // Just auto-nudge
            memory.bidConfidence = (memory.bidConfidence + autoNudge).coerceIn(0.0, 1.0)
        }

        // Record outcome
        memory.handOutcomes.add(HandOutcome(
            handNumber = memory.handOutcomes.size + 1,
            biddedPoints = biddedPoints,
            actualPoints = actualPoints,
            playerDecision = tagCorrection ?: "auto",
            wasCorrection = tagCorrection != null
        ))

        // Keep only last 50 outcomes
        if (memory.handOutcomes.size > 50) {
            memory.handOutcomes = memory.handOutcomes.takeLast(50).toMutableList()
        }

        memory.lastUpdated = System.currentTimeMillis()
        saveMemory(seatPosition)
    }

    /**
     * TIER 1: Get smart bid recommendation
     * Uses hand strength + positional + partner info
     */
    fun getBidRecommendation(
        seatPosition: Int,
        hand: List<Card>,
        trump: String,
        partnerStrength: Double,
        cardsPlayed: Int
    ): Int {
        val memory = memories.getOrDefault(seatPosition, CharacterMemory(seatPosition, "Seat$seatPosition"))
        val strength = evaluateHandStrength(hand, trump)
        memory.lastHandStrength = strength

        // Base bid from trump ladder (existing logic)
        val baseBid = calculateTrumpLadderBid(hand, trump, cardsPlayed)

        // Adjust by hand strength and bidConfidence
        val adjustment = (strength.strength - 0.5) * 10.0 * memory.bidConfidence

        // Partner boost: if partner is strong, bid a bit higher (feed them good cards)
        val partnerBoost = partnerStrength * 3.0

        return max(6, min(30, (baseBid + adjustment + partnerBoost).toInt()))
    }

    /**
     * TIER 1: Get smart play decision
     * Considers position, hand strength, partner info, cards played
     */
    fun getPlayRecommendation(
        seatPosition: Int,
        hand: List<Card>,
        trump: String,
        position: TablePosition,
        cardsInTrick: Int,
        partnerStrength: Double
    ): Card? {
        val memory = memories.getOrDefault(seatPosition, CharacterMemory(seatPosition, "Seat$seatPosition"))
        val weights = getPositionalWeights(position, memory, partnerStrength)

        // Placeholder: actual play logic would go here
        // This would use the weights to score candidate cards and pick the best
        return hand.firstOrNull()
    }

    // ===== Helper Methods =====

    private fun calculateTrumpLadderBid(hand: List<Card>, trump: String, cardsPlayed: Int): Int {
        // Existing 14-slot trump ladder logic
        val trumpCards = hand.filter { it.suit == trump }
        val offTrumpPoints = hand.count { it.rank in listOf("K", "J", "5") && it.suit != trump }

        return when {
            trumpCards.size >= 4 -> 18 + offTrumpPoints
            trumpCards.size == 3 -> 14 + offTrumpPoints
            trumpCards.size == 2 -> 10 + offTrumpPoints
            trumpCards.size == 1 -> 8
            else -> 6
        }
    }

    /** Writes one seat's memory to king-pedro-memory/seat_N.json (temp file + rename, so a crash can't leave half a file). */
    private fun saveMemory(seatPosition: Int) {
        val memory = memories[seatPosition] ?: return
        val file = File(memoryDir, "seat_$seatPosition.json")
        val tmp = File(memoryDir, "seat_$seatPosition.json.tmp")
        try {
            tmp.writeText(MemoryJson.encode(memory))
            if (!tmp.renameTo(file)) {           // Windows won't rename over an existing file
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            System.err.println("PlayerMemory: could not save ${file.path}: ${e.message}")
        }
    }

    /** Loads every king-pedro-memory/seat_N.json. A missing or damaged file just means that seat starts fresh. */
    private fun loadAllMemories() {
        val seatFile = Regex("""seat_(\d+)\.json""")
        val files = File(memoryDir).listFiles() ?: return
        for (f in files) {
            val seat = seatFile.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            try {
                memories[seat] = MemoryJson.decode(f.readText(), seat)
            } catch (e: Exception) {
                System.err.println("PlayerMemory: ignoring unreadable ${f.path}: ${e.message}")
            }
        }
    }

    fun getMemory(seatPosition: Int): CharacterMemory {
        return memories.getOrDefault(seatPosition, CharacterMemory(seatPosition, "Seat$seatPosition"))
    }
}

/**
 * Hand-rolled JSON for CharacterMemory, so the AI needs no external library
 * (same approach as the Sep 17 version). Unknown keys are ignored and missing
 * keys keep their defaults, so older or newer files still load.
 */
internal object MemoryJson {

    fun encode(m: CharacterMemory): String {
        val hs = m.lastHandStrength
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"seatPosition\": ${m.seatPosition},\n")
        sb.append("  \"characterName\": ${str(m.characterName)},\n")
        sb.append("  \"bidConfidence\": ${m.bidConfidence},\n")
        sb.append("  \"winEagerness\": ${m.winEagerness},\n")
        sb.append("  \"feedPartnerBias\": ${m.feedPartnerBias},\n")
        sb.append("  \"leadTrumpBias\": ${m.leadTrumpBias},\n")
        sb.append("  \"lastUpdated\": ${m.lastUpdated},\n")
        sb.append("  \"lastHandStrength\": {\"hasHigh\": ${hs.hasHigh}, \"hasLow\": ${hs.hasLow}, " +
            "\"hasJack\": ${hs.hasJack}, \"hasPedro\": ${hs.hasPedro}, \"offPedroCount\": ${hs.offPedroCount}, " +
            "\"trumpCount\": ${hs.trumpCount}, \"strength\": ${hs.strength}},\n")
        sb.append("  \"partnerBidHistory\": [")
        sb.append(m.partnerBidHistory.joinToString(",") {
            "\n    {\"handNumber\": ${it.handNumber}, \"partnerBid\": ${it.partnerBid}, " +
                "\"partnerTrump\": ${it.partnerTrump?.let(::str) ?: "null"}, \"handOutcome\": ${it.handOutcome}}"
        })
        sb.append(if (m.partnerBidHistory.isEmpty()) "],\n" else "\n  ],\n")
        sb.append("  \"handOutcomes\": [")
        sb.append(m.handOutcomes.joinToString(",") {
            "\n    {\"handNumber\": ${it.handNumber}, \"biddedPoints\": ${it.biddedPoints}, " +
                "\"actualPoints\": ${it.actualPoints}, \"playerDecision\": ${str(it.playerDecision)}, " +
                "\"wasCorrection\": ${it.wasCorrection}}"
        })
        sb.append(if (m.handOutcomes.isEmpty()) "]\n" else "\n  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    fun decode(json: String, seat: Int): CharacterMemory {
        @Suppress("UNCHECKED_CAST")
        val o = Parser(json).parseValue() as? Map<String, Any?>
            ?: throw IllegalArgumentException("not a JSON object")
        val m = CharacterMemory(seat, (o["characterName"] as? String) ?: "Seat$seat")
        (o["bidConfidence"] as? Double)?.let { m.bidConfidence = it }
        (o["winEagerness"] as? Double)?.let { m.winEagerness = it }
        (o["feedPartnerBias"] as? Double)?.let { m.feedPartnerBias = it }
        (o["leadTrumpBias"] as? Double)?.let { m.leadTrumpBias = it }
        (o["lastUpdated"] as? Double)?.let { m.lastUpdated = it.toLong() }
        (o["lastHandStrength"] as? Map<*, *>)?.let { h ->
            m.lastHandStrength = HandStrength(
                hasHigh = h["hasHigh"] == true, hasLow = h["hasLow"] == true,
                hasJack = h["hasJack"] == true, hasPedro = h["hasPedro"] == true,
                offPedroCount = h.int("offPedroCount"), trumpCount = h.int("trumpCount"),
                strength = (h["strength"] as? Double) ?: 0.0
            )
        }
        (o["partnerBidHistory"] as? List<*>)?.forEach { e ->
            val h = e as? Map<*, *> ?: return@forEach
            m.partnerBidHistory.add(PartnerBidEvent(h.int("handNumber"), h.int("partnerBid"),
                h["partnerTrump"] as? String, h.int("handOutcome")))
        }
        (o["handOutcomes"] as? List<*>)?.forEach { e ->
            val h = e as? Map<*, *> ?: return@forEach
            m.handOutcomes.add(HandOutcome(h.int("handNumber"), h.int("biddedPoints"), h.int("actualPoints"),
                (h["playerDecision"] as? String) ?: "auto", h["wasCorrection"] == true))
        }
        return m
    }

    private fun Map<*, *>.int(key: String) = (this[key] as? Double)?.toInt() ?: 0

    private fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    /** Minimal JSON reader: objects -> Map, arrays -> List, numbers -> Double. */
    private class Parser(private val s: String) {
        private var i = 0

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("unexpected end of JSON")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++; skipWs()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs(); expect(':')
                map[key] = parseValue()
                skipWs()
                if (peek() == ',') { i++; continue }
                expect('}'); return map
            }
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            i++; skipWs()
            if (peek() == ']') { i++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (peek() == ',') { i++; continue }
                expect(']'); return list
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull()
                ?: throw IllegalArgumentException("bad value at position $start")
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("bad value at position $i")
            i += word.length
            return value
        }

        private fun peek() = if (i < s.length) s[i] else '\u0000'
        private fun expect(c: Char) {
            if (peek() != c) throw IllegalArgumentException("expected '$c' at position $i")
            i++
        }
        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
    }
}

/**
 * Positional strategy weights (mutable, per decision)
 */
data class PositionalWeights(
    val winEagerness: Double,
    val feedPartnerBias: Double,
    val leadTrumpBias: Double
)

/**
 * Card data class (minimal for this example)
 */
data class Card(
    val suit: String,  // "hearts", "diamonds", "clubs", "spades"
    val rank: String   // "K", "Q", "J", "10", "9", "8", "7", "6", "5", "4", "3"
)
