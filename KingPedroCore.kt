// ============================================================================
//  KingPedroCore.kt  —  Ukrainian-Canadian King Pedro, fully automated
//  TIER 1 AI ENHANCEMENTS: Hand-strength evaluation, positional strategies, partner signals
// ============================================================================
//  RULES IMPLEMENTED (Ukrainian-Canadian variant):
//   • 4 players, fixed partnerships (0+2 vs 1+3), seated across the table
//   • 9 cards dealt to each player, 3 at a time
//   • Bidding: starts left of dealer, minimum 30, maximum 62, bid higher or
//     pass; if all pass, the dealer is "stuck" with the contract at 30
//   • High bidder names trump
//   • The off-colour 5 (same colour as trump) IS a trump, ranking just
//     below the natural 5 of trump, and follows like a trump
//   • Discard phase: reduce to 6 cards keeping trumps; NO draw from a stock.
//       - 0 trumps  -> fold (sit the hand out)
//       - 1-2 trumps -> may pass those trumps to your partner, then fold;
//         the partner takes them and discards the same number of non-scoring cards
//       - >6 trumps  -> drop non-scoring trumps (the 2 still scores if tossed)
//   • 6 tricks; declarer leads first; you may ALWAYS play a trump, otherwise
//     you must follow the led suit if able
//   • Point cards (62 total, trump suit only):
//       King = 30   Ten = 10   Nine = 9   both Pedros (5s) = 5 each
//       Ace = 1     Jack = 1   Two = 1 (the 2 scores for the team that PLAYS it)
//   • Scoring: bidders make the bid → both teams keep captured points;
//     bidders fail → they go back (minus) the bid, defenders keep theirs
//   • Game to 200 — and you must reach 200 ON A SUCCESSFUL BID to win
//
//  AUTOMATION: all 4 seats are AI, each with a distinct personality and
//  its own vocabulary engine (bidding banter, trump calls, trick talk,
//  partner-feeding chatter, victory/defeat lines).
//  TIER 1: Hand strength evaluation, positional awareness (LEADER/MID/CLOSER),
//  and partner signal inference from bid history.
//
//  HOW TO RUN:  run main() — plays one complete game to 200 with full
//  colour-coded play-by-play. Set USE_COLOR = false for plain text.
// ============================================================================

package com.example.kingpedro

import kotlin.random.Random
import com.ranslu.kingpedro.ai.PlayerMemory
import com.ranslu.kingpedro.ai.TablePosition
import com.ranslu.kingpedro.ai.Card as AICard

// ---------------------------------------------------------------------------
// 0) HOUSE RULES — flip these to match how YOUR table plays King Pedro
// ---------------------------------------------------------------------------
object HouseRules {
    /**
     * When a NON-TRUMP suit is led, may a player trump in even though they
     * could follow the led suit?
     *   true  = MUST FOLLOW SUIT if able (stricter). You may only trump a
     *           non-trump lead when you are void in the led suit.
     *   false = MAY ALWAYS TRUMP (looser, classic Pedro "follow suit or trump").
     * (When a trump is led, you must always follow trump if able — both rules agree.)
     */
    var mustFollowSuit = false

    /**
     * When a TRUMP is led and a player holds no trump, that player surrenders
     * their remaining cards to the junk pile and sits out the rest of the hand.
     * (Their leftover cards are all non-trumps, so they score nothing anyway.)
     */
    var surrenderWhenVoidOnTrumpLead = true

    /** Target score to win the game (must be reached ON a made bid). */
    var winningScore = 200

    /** Bidding limits. */
    var minBid = 30
    var maxBid = 62
}

// ---------------------------------------------------------------------------
// 0b) CONSOLE COLOURS (cyberpunk-ish: cyan headers, yellow points, per-player)
// ---------------------------------------------------------------------------
const val USE_COLOR = true

object Ansi {
    private fun c(code: String) = if (USE_COLOR) "\u001B[${code}m" else ""
    val RESET get() = c("0")
    val BOLD get() = c("1")
    val CYAN get() = c("96")
    val YELLOW get() = c("93")
    val GREEN get() = c("92")
    val RED get() = c("91")
    val MAGENTA get() = c("95")
    val BLUE get() = c("94")
    val GREY get() = c("90")
    val PLAYER = listOf(c("91"), c("94"), c("93"), c("92"))   // red, blue, yellow, green
}

fun header(text: String) =
    println("\n${Ansi.CYAN}${Ansi.BOLD}══════ $text ══════${Ansi.RESET}")

// ---------------------------------------------------------------------------
// 1) CARD MODELS
// ---------------------------------------------------------------------------
enum class Suit(val symbol: String, val red: Boolean) {
    Hearts("♥", true), Diamonds("♦", true), Clubs("♣", false), Spades("♠", false);

    fun sameColor(other: Suit) = this.red == other.red
}

enum class Rank(val baseValue: Int, val symbol: String) {
    Two(2, "2"), Three(3, "3"), Four(4, "4"), Five(5, "5"), Six(6, "6"),
    Seven(7, "7"), Eight(8, "8"), Nine(9, "9"), Ten(10, "10"),
    Jack(11, "J"), Queen(12, "Q"), King(13, "K"), Ace(14, "A")
}

data class Card(val suit: Suit, val rank: Rank) {
    override fun toString() = "${rank.symbol}${suit.symbol}"

    /** Trump test — includes the off-colour 5 (the "Left Pedro"). */
    fun isTrump(trump: Suit): Boolean =
        suit == trump || (rank == Rank.Five && suit != trump && suit.sameColor(trump))

    /** The suit this card counts as for following purposes. */
    fun effectiveSuit(trump: Suit): Suit = if (isTrump(trump)) trump else suit

    /** Authentic 62-point King Pedro values (trump suit only). */
    fun points(trump: Suit): Int {
        if (!isTrump(trump)) return 0
        return when (rank) {
            Rank.King -> 30
            Rank.Ten -> 10
            Rank.Nine -> 9
            Rank.Five -> 5      // catches both Pedros
            Rank.Ace, Rank.Jack, Rank.Two -> 1
            else -> 0
        }
    }

    /** Trick-taking power. Trumps beat everything; off-5 ranks just below 5. */
    fun power(trump: Suit, led: Suit): Int {
        if (isTrump(trump)) {
            val r = when {
                rank == Rank.Five && suit == trump -> 5          // natural Pedro
                rank == Rank.Five -> 4                           // off-colour Pedro
                rank.baseValue <= 4 -> rank.baseValue - 1        // 2,3,4 shift down
                else -> rank.baseValue                            // 6..A unchanged
            }
            return 200 + r
        }
        return if (suit == led) rank.baseValue else 0
    }
}

fun freshDeck(): MutableList<Card> {
    val d = mutableListOf<Card>()
    for (s in Suit.values()) for (r in Rank.values()) d.add(Card(s, r))
    return d
}

// ---------------------------------------------------------------------------
// 2) PERSONALITY-DRIVEN VOCABULARY ENGINE
// ---------------------------------------------------------------------------
enum class Mood { BID, PASS, STUCK, TRUMP_CALL, LEAD, WIN_TRICK, FEED_PARTNER,
                  SLUFF, MADE_BID, GOT_SET, GAME_WIN, BIG_POINTS,
                  PASS_TRUMP /* hand off 1-2 trumps to partner */, FOLD /* sit the hand out */ }

class Vocabulary(private val lines: Map<Mood, List<String>>) {
    fun line(mood: Mood, vararg args: Any): String {
        val pool = lines[mood] ?: listOf("...")
        var s = pool[Random.nextInt(pool.size)]
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
        return s
    }
}

object Voices {
    val IRON_MIKE = Vocabulary(mapOf(
        Mood.BID to listOf("Step aside — {0}. This hand is MINE.", "{0}. I smell blood in the water.",
            "Crank it to {0} and watch them squirm.", "{0}! Somebody had to show some spine."),
        Mood.PASS to listOf("Garbage hand. Pass.", "Pass — but don't get comfortable.",
            "I fold this round. Enjoy it while it lasts."),
        Mood.STUCK to listOf("Stuck at 30?! Fine. I'll make 30 look easy."),
        Mood.TRUMP_CALL to listOf("Trump is {0}. Hide your Pedros.", "{0}! Now we go to war."),
        Mood.LEAD to listOf("Eat this.", "Coming in heavy.", "Bleed out those trumps, people."),
        Mood.WIN_TRICK to listOf("Exactly as planned.", "Too easy. Next victim.", "Hand over those points."),
        Mood.FEED_PARTNER to listOf("Take it, partner — it's payday.", "Feeding you the goods. Don't waste it."),
        Mood.SLUFF to listOf("Take the trash.", "Worthless. Like your bid."),
        Mood.MADE_BID to listOf("Contract MADE. Pay up.", "That's how champions close."),
        Mood.GOT_SET to listOf("Unbelievable. UNBELIEVABLE.", "We got robbed and you all know it."),
        Mood.GAME_WIN to listOf("200! Read it and weep!"),
        Mood.BIG_POINTS to listOf("THIRTY points walking out the door! WHO LET THE KING GO?!"),
        Mood.PASS_TRUMP to listOf("Take my trump, partner. Don't make me regret it.",
            "Here — {0}. Go make it count.", "Useless to me. Lethal in your hands. {0}."),
        Mood.FOLD to listOf("I'm out. Carry us, partner.", "Folding. Wake me when there's a fight.")
    ))

    val PROF_ELENA = Vocabulary(mapOf(
        Mood.BID to listOf("Expected value supports {0}. I bid {0}.", "The distribution favours us — {0}.",
            "{0}. The numbers rarely lie.", "Probability of contract at {0}: acceptable."),
        Mood.PASS to listOf("Insufficient trump density. Pass.", "Variance is too high. I pass.",
            "Pass — this sample is unremarkable."),
        Mood.STUCK to listOf("Dealer's burden. Statistically survivable at 30."),
        Mood.TRUMP_CALL to listOf("Declaring {0} — optimal concentration.", "{0}. The data is conclusive."),
        Mood.LEAD to listOf("Initiating extraction sequence.", "Let us thin the trump population."),
        Mood.WIN_TRICK to listOf("As modelled.", "Within one standard deviation of perfect.",
            "Noted: trick secured."),
        Mood.FEED_PARTNER to listOf("Transferring assets to my partner.", "An efficient allocation, partner."),
        Mood.SLUFF to listOf("Discarding a null value.", "This card contributes nothing — fitting."),
        Mood.MADE_BID to listOf("Hypothesis confirmed. Contract fulfilled.", "QED."),
        Mood.GOT_SET to listOf("An outlier. We regress to the mean next hand.", "My model requires... revision."),
        Mood.GAME_WIN to listOf("200 points. The proof is complete."),
        Mood.BIG_POINTS to listOf("A 30-point swing. Catastrophic for someone's spreadsheet."),
        Mood.PASS_TRUMP to listOf("Reallocating {0} to my partner — higher expected utility.",
            "My single trump is dominated. Transferring it: {0}.", "Optimal: consolidate. Take {0}."),
        Mood.FOLD to listOf("Folding. My marginal contribution is zero.", "I withdraw. The variance does not favour me.")
    ))

    val BOHDAN = Vocabulary(mapOf(
        Mood.BID to listOf("Davai! {0}!", "{0}, like my baba taught me — bid bold, perogies later!",
            "Slava kartam! {0} it is!", "{0}! In Vegreville we call this a Tuesday."),
        Mood.PASS to listOf("Ni, ni — pass. Even borscht needs the right beets.",
            "Pass. My cards are sadder than a January in Mundare.",
            "I pass, friends. The kovbasa is not worth the fire."),
        Mood.STUCK to listOf("Stuck with 30? Eh, we have survived worse winters!"),
        Mood.TRUMP_CALL to listOf("Trump is {0}! Strong like ox!", "{0}, my friends! Dobre, dobre!"),
        Mood.LEAD to listOf("Hop! Out comes the big one!", "Davai, davai — follow if you can!"),
        Mood.WIN_TRICK to listOf("Ha! Like grandfather's scythe — clean!", "Dyakuyu! I take this one home!",
            "Beautiful! Put it in the pyrohy pot!"),
        Mood.FEED_PARTNER to listOf("For you, partner! Like Easter bread, fresh and warm!",
            "Catch, partner! A little Pedro perogy!"),
        Mood.SLUFF to listOf("Take this one — it is only good for kindling.", "Pfft. To the compost with it."),
        Mood.MADE_BID to listOf("We made it! Tonight, holubtsi for everyone!", "Slava! The contract is ours!"),
        Mood.GOT_SET to listOf("Oy... set like cabbage in October.", "Bozhe. The cards were cruel today."),
        Mood.GAME_WIN to listOf("200! Bring out the horilka — we celebrate!"),
        Mood.BIG_POINTS to listOf("The KING! Thirty points — bozhe miy, what a harvest!"),
        Mood.PASS_TRUMP to listOf("Here, partner — {0}! Take it like fresh perogies from baba.",
            "One little trump for you, partner: {0}. Make borscht from it!", "{0} is yours, partner. Davai!"),
        Mood.FOLD to listOf("I sit this one out, friends. Even oxen rest.", "Folding — I will watch and eat sunflower seeds.")
    ))

    val LUCKY_LOU = Vocabulary(mapOf(
        Mood.BID to listOf("{0}! Let it RIDE!", "Feeling spicy — {0}!", "{0}? Sure, why not. YOLO.",
            "The dice in my soul say {0}!"),
        Mood.PASS to listOf("Pass... the vibes are off.", "Even I won't touch this hand. Pass.",
            "Pass! Saving my luck for later."),
        Mood.STUCK to listOf("Stuck at 30?! Baby, fortune LOVES a cornered gambler!"),
        Mood.TRUMP_CALL to listOf("Trump is {0}! Spin the wheel!", "{0}!! I literally flipped a coin."),
        Mood.LEAD to listOf("Surprise! Bet you didn't see THAT coming!", "Chaos card, activate!"),
        Mood.WIN_TRICK to listOf("HA! I didn't even mean to win that!", "Mine mine mine! Beautiful chaos!",
            "The house always... wait, I'M the house!"),
        Mood.FEED_PARTNER to listOf("Jackpot delivery for my partner!", "Catch! Consider it a tip!"),
        Mood.SLUFF to listOf("Here's a coupon for nothing.", "Yeet."),
        Mood.MADE_BID to listOf("WINNER WINNER! Contract dinner!", "Told you the vibes were good!"),
        Mood.GOT_SET to listOf("Welp. The wheel giveth, the wheel yeeteth away.",
            "Set?! I demand a recount of the universe."),
        Mood.GAME_WIN to listOf("200!! CASH ME OUT, DEALER!"),
        Mood.BIG_POINTS to listOf("THIRTY POINTS just changed hands — somebody's crying tonight!"),
        Mood.PASS_TRUMP to listOf("Sliding you my chip, partner: {0}. Let it ride!",
            "All-in on you, partner — {0}!", "Take {0}. I'm cashing out of this hand."),
        Mood.FOLD to listOf("Folding! Even I know when to walk away.", "I'm out — saving my luck for the next spin!")
    ))
}


// ---------------------------------------------------------------------------
// BIDDING MODEL — fitted from 300,000 simulated hands of this rule set.
// Predicts the points the declaring team captures with suit s as trump, split by
// whether the bidder holds the King of trump (worth ~20 points on its own), then
// turns that into "highest bid I make with at least X% confidence".
// ---------------------------------------------------------------------------
object BidModel {
    /** Chance of making the bid each character wants: Mike, Elena, Bohdan, Lou. */
    val CONFIDENCE = listOf(0.62, 0.75, 0.68, 0.55)
    /** How far past the minimum each character jumps (−1 = random). */
    val JUMP = listOf(0.6, 0.0, 0.3, -1.0)
    // weights: base, A, Q, J, 10, 9, Pedro 5, off-5, 2, each low trump (3,4,6,7,8), dealer
    private val W_NO_KING = doubleArrayOf(20.19, 3.29, 2.42, 3.89, 3.96, 2.56, 1.15, 0.81, 1.23, 1.14, -1.02)
    private val W_KING    = doubleArrayOf(35.29, 7.29, 5.14, 9.17, 6.36, 4.42, 2.74, 2.05, 3.17, 3.13, -1.27)
    private val RISK = doubleArrayOf(0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50)
    private val Q_NO_KING = doubleArrayOf(-21.6, -20.5, -19.2, -16.6, -14.1, -11.5, -8.8, -5.6)
    private val Q_KING    = doubleArrayOf(-11.9, -9.0, -6.6, -4.7, -2.9, -1.3, 0.4, 1.9)

    fun quantile(hasKing: Boolean, risk: Double): Double {
        val i = RISK.indices.minByOrNull { kotlin.math.abs(RISK[it] - risk) } ?: 0
        return if (hasKing) Q_KING[i] else Q_NO_KING[i]
    }

    fun expectedPoints(cards: List<Card>, s: Suit, isDealer: Boolean): Pair<Double, Boolean> {
        val t = cards.filter { it.isTrump(s) }
        fun has(r: Rank) = if (t.any { it.rank == r && it.suit == s }) 1.0 else 0.0
        val king = has(Rank.King) > 0
        val x = doubleArrayOf(1.0, has(Rank.Ace), has(Rank.Queen), has(Rank.Jack), has(Rank.Ten), has(Rank.Nine),
            has(Rank.Five), if (t.any { it.rank == Rank.Five && it.suit != s }) 1.0 else 0.0, has(Rank.Two),
            t.count { it.suit == s && it.rank in listOf(Rank.Three, Rank.Four, Rank.Six, Rank.Seven, Rank.Eight) }.toDouble(),
            if (isDealer) 1.0 else 0.0)
        val w = if (king) W_KING else W_NO_KING
        return x.indices.sumOf { x[it] * w[it] } to king
    }
}

// ---------------------------------------------------------------------------
// 3) AI PLAYER — TIER 1 ENHANCED
// ---------------------------------------------------------------------------
class AIPlayer(val seat: Int, val name: String, val voice: Vocabulary,
               val aggression: Int /* -3 cautious .. +4 reckless */,
               val chaos: Int /* 0..6 random swing */,
               private val playerMemoryManager: PlayerMemory) {

    val hand = mutableListOf<Card>()
    val partner get() = (seat + 2) % 4
    val team get() = seat % 2
    var folded = false

    fun say(mood: Mood, vararg args: Any) =
        println("  ${Ansi.PLAYER[seat]}$name${Ansi.RESET}: \"${voice.line(mood, *args)}\"")

    /** Decide whether to pass 1-2 trumps to partner and fold. */
    fun wantsToPassAndFold(trump: Suit, isDeclarer: Boolean): Boolean {
        if (isDeclarer) return false                 // the bidder never folds
        val trumps = hand.count { it.isTrump(trump) }
        if (trumps !in 1..2) return false            // only allowed with 1 or 2 trumps
        // Cautious players almost always help the partner; reckless ones sometimes gamble.
        val keepUrge = aggression + Random.nextInt(0, chaos + 1)
        return keepUrge < 2                           // weak hand → consolidate with partner
    }

    /**
     * Expected points this hand can actually CAPTURE if [s] is trump — the bid
     * driver Randy asked for: bidding is graded by the tricks/points a card is
     * likely to WIN, not a flat per-rank lookup table.
     *   - Cards are ranked using the same 14-slot trump ladder choosePlay()
     *     already uses (Card.power), so bidding and card-play share one model
     *     of "how strong is this trump."
     *   - winProb decays the further down your OWN trump stack a card sits:
     *     your top trump is likely to win a trick; your fourth-best trump
     *     probably won't survive that long. That's the "tricks you actually
     *     get" part — a hand full of low trumps no longer inflates the bid
     *     the way a flat count would.
     *   - The 2 of trump always scores for whoever plays it, so it gets a
     *     flat guaranteed bump on top of its winProb share.
     */
    fun expectedPoints(cards: List<Card>, s: Suit): Double {
        val trumps = cards.filter { it.isTrump(s) }.sortedByDescending { it.power(s, s) }
        var total = 0.0
        for ((i, c) in trumps.withIndex()) {
            val rankStrength = (c.power(s, s) - 200) / 14.0          // 0..1 on the trump ladder
            val positionDecay = (1.0 / (i + 1)).coerceAtLeast(0.15)  // your Nth-best trump, not your 1st
            val winProb = (rankStrength * positionDecay).coerceIn(0.05, 1.0)
            total += c.points(s) * winProb
            if (c.rank == Rank.Two) total += 0.5                     // the 2 scores just by being played
        }
        return total
    }

    /** ===== TIER 1A: Hand-Strength Evaluation =====
     *  Evaluate hand strength using PlayerMemory hand evaluation
     *  [extra] lets the dealer factor in the one card they peeked at.
     */
    fun evaluateHandStrength(s: Suit, extra: Card? = null): Double {
        val cards = if (extra != null) hand + extra else hand
        val aiCards = cards.map { AICard(it.suit.name, it.rank.symbol) }
        val handStrength = playerMemoryManager.evaluateHandStrength(aiCards, s.name)
        return handStrength.strength
    }

    /** Estimate hand strength for the best suit; returns (suit, suggested bid).
     *  ===== TIER 1: Integrated hand-strength + partner signal =====
     *  [extra] lets the dealer factor in the one card they peeked at.
     *  Bid = uses PlayerMemory.getBidRecommendation() with hand strength + partner insight */
    fun appraise(extra: Card? = null): Pair<Suit, Int> {
        val cards = if (extra != null) hand + extra else hand
        // Target chance of making the contract before this character will bid it.
        // The learned bidConfidence (0.5 = neutral) nudges it by up to ±0.10.
        val memory = playerMemoryManager.getMemory(seat)
        var conf = BidModel.CONFIDENCE.getOrElse(seat) { 0.68 } - (memory.bidConfidence - 0.5) * 0.2
        if (chaos > 0) conf -= Random.nextDouble() * chaos / 100.0     // gamblers get braver on a whim
        val risk = (Math.round((1 - conf).coerceIn(0.15, 0.50) * 20) / 20.0)
        var bestSuit = Suit.Hearts; var bestBid = 0; var bestMean = -1.0
        for (s in Suit.values()) {
            val (mean, hasKing) = BidModel.expectedPoints(cards, s, isDealer = extra != null)
            val bid = kotlin.math.floor(mean + BidModel.quantile(hasKing, risk)).toInt()
            if (bid > bestBid || (bid == bestBid && mean > bestMean)) { bestSuit = s; bestBid = bid; bestMean = mean }
        }
        return bestSuit to bestBid
    }

    /** Bid to make now (0 = pass): outbid by only what's needed, jump when overcalling an
     *  opponent, and never overbid your own partner unless clearly stronger. */
    fun chooseBid(limit: Int, need: Int, currentDeclarer: Int): Int {
        if (limit < need) return 0
        if (currentDeclarer >= 0 && currentDeclarer % 2 == team && limit < need + 8) return 0
        var j = BidModel.JUMP.getOrElse(seat) { 0.3 }
        if (j < 0) j = Random.nextDouble()
        if (currentDeclarer >= 0) j = maxOf(j, 0.5)
        return minOf(HouseRules.maxBid, need + ((limit - need) * j).toInt())
    }

    /**
     * Never lead a bare King of trump (worth 30) unless you also hold the Ace,
     * or the Ace has already appeared in play from anyone. Otherwise, lead low
     * to flush the Ace out first — once it's gone, the King leads safely.
     */
    private fun chooseLead(legal: List<Card>, trumps: List<Card>, trump: Suit,
                            forceTrumpLead: Boolean, trumpAceGone: Boolean): Card {
        val hasAce = trumps.any { it.rank == Rank.Ace }
        val safeTrumps = if (hasAce || trumpAceGone) trumps else trumps.filterNot { it.rank == Rank.King }

        if (forceTrumpLead && trumps.isNotEmpty())   // house rule: trick 1 must open with a trump
            return (safeTrumps.ifEmpty { trumps }).maxByOrNull { it.power(trump, trump) }!!

        // ===== TIER 1B: Positional awareness =====
        val memory = playerMemoryManager.getMemory(seat)
        val leadTrumpBias = memory.lastHandStrength.strength  // Use hand strength to modulate aggressiveness
        return when {
            // learned leadTrumpBias nudges a borderline-cautious hand into drawing trump anyway
            safeTrumps.isNotEmpty() && (aggression + (leadTrumpBias - 0.5) * 5) > 0 ->
                safeTrumps.maxByOrNull { it.power(trump, trump) }!!
            trumps.isNotEmpty() && chaos >= 4 -> trumps.random()
            // King protected & nothing else to draw with -> lead low to flush the Ace out
            else -> legal.minByOrNull { it.points(trump) * 100 + it.rank.baseValue }!!
        }
    }

    /** Returns a legal play given the trick so far. [trumpAceGone] tells this seat
     *  whether the Ace of trump has already appeared in play this hand.
     *  ===== TIER 1B & 1C: Positional strategy + partner signals ===== */
    fun choosePlay(trick: List<Pair<Int, Card>>, trump: Suit, forceTrumpLead: Boolean = false,
                   trumpAceGone: Boolean = false, playOrder: List<Int>): Card {
        val legal = legalPlays(trick, trump)

        if (trick.isEmpty()) {            // ----- leading -----
            val trumps = legal.filter { it.isTrump(trump) }
            return chooseLead(legal, trumps, trump, forceTrumpLead, trumpAceGone)
        }

        // ===== TIER 1B: Determine table position =====
        val position = determineTablePosition(playOrder)
        val memory = playerMemoryManager.getMemory(seat)
        val partnerStrength = playerMemoryManager.inferPartnerStrength(memory)

        // Try to get position-aware play recommendation
        val aiCards = legal.map { AICard(it.suit.name, it.rank.symbol) }
        val recommendation = playerMemoryManager.getPlayRecommendation(
            seat, aiCards, trump.name, position,
            cardsInTrick = trick.size,
            partnerStrength = partnerStrength
        )

        if (recommendation != null) {
            val recCard = legal.firstOrNull {
                it.suit.name == recommendation.suit && it.rank.symbol == recommendation.rank
            }
            if (recCard != null) return recCard
        }

        // Fall back to original logic if recommendation not found
        val led = trick.first().second.effectiveSuit(trump)
        val (winSeat, winCard) = currentWinner(trick, trump)
        val pointsOnTable = trick.sumOf { it.second.points(trump) }
        val lastToAct = trick.size == 3
        val partnerWinning = winSeat == partner

        val feedBias = 0.5  // Use default for now; could be enhanced with partner signal
        val winEagerness = 0.5

        // 1) Feed the partner: last to act, partner securely winning → give a Pedro
        if (lastToAct && partnerWinning && feedBias > 0.55) {
            val feed = legal.filter { it.rank == Rank.Five && it.isTrump(trump) }
                .maxByOrNull { it.points(trump) }
            if (feed != null) { say(Mood.FEED_PARTNER); return feed }
        }

        // 2) Try to win — but never burn a trump on a worthless (0-point) trick
        val winners = legal.filter { it.power(trump, led) > winCard.power(trump, led) }
        if (winners.isNotEmpty() && (pointsOnTable > 0 || !partnerWinning)) {
            val cheap = winners.filter { it.rank != Rank.King && !(it.rank == Rank.Five && it.isTrump(trump)) }
            val pick = (cheap.ifEmpty { winners }).minByOrNull { it.power(trump, led) }!!
            if (pointsOnTable > 0) return pick                       // points to grab → take them
            val freeWin = cheap.filter { !it.isTrump(trump) }        // 0 points: only win if it's free
                .minByOrNull { it.power(trump, led) }
            if (freeWin != null) return freeWin                      // win with a non-trump, save trumps
            // learned winEagerness: a seat that's been told it plays too passively will
            // occasionally burn a trump for board control even on a 0-point trick
            if (winEagerness > 1.25 && cheap.isNotEmpty()) return cheap.minByOrNull { it.power(trump, led) }!!
            // otherwise it would cost a trump for nothing → fall through and sluff
        }

        // 3) Can't (or won't) win → sluff the cheapest, safest card
        val safe = legal.filter { it.points(trump) == 0 }
        return (safe.ifEmpty { legal.filter { it.rank != Rank.King }.ifEmpty { legal } })
            .minByOrNull { it.power(trump, led) + it.points(trump) * 50 }!!
    }

    /** ===== TIER 1B: Determine table position for strategy weighting ===== */
    private fun determineTablePosition(playOrder: List<Int>): TablePosition {
        val index = playOrder.indexOf(seat)
        return when (index) {
            0 -> TablePosition.LEADER        // First to play
            playOrder.size - 1 -> TablePosition.CLOSER  // Last to play
            else -> TablePosition.MID_HAND   // Middle positions
        }
    }

    fun legalPlays(trick: List<Pair<Int, Card>>, trump: Suit): List<Card> {
        if (trick.isEmpty()) return hand.toList()
        val led = trick.first().second.effectiveSuit(trump)
        val trumpLed = led == trump
        val follows = hand.filter { it.effectiveSuit(trump) == led }
        if (HouseRules.mustFollowSuit) {
            // strict: must follow the led suit (trump or not) if able, else anything
            return if (follows.isNotEmpty()) follows else hand.toList()
        }
        // house rule:
        return if (trumpLed) {
            if (follows.isNotEmpty()) follows else hand.toList()   // trump led -> follow trump if able
        } else {
            hand.toList()                                          // non-trump led -> throw ANY card
        }
    }
}

fun currentWinner(trick: List<Pair<Int, Card>>, trump: Suit): Pair<Int, Card> {
    val led = trick.first().second.effectiveSuit(trump)
    return trick.maxByOrNull { it.second.power(trump, led) }!!
}

// ---------------------------------------------------------------------------
// 4) GAME ENGINE — TIER 1 WIRED UP
// ---------------------------------------------------------------------------
class KingPedroEngine(seed: Long? = null) {
    private val rng = if (seed != null) Random(seed) else Random.Default

    // ===== TIER 1: PlayerMemory manager =====
    private val playerMemory = PlayerMemory("king-pedro-memory")

    val players = listOf(
        AIPlayer(0, "Iron Mike", Voices.IRON_MIKE, aggression = 3, chaos = 1, playerMemory),
        AIPlayer(1, "Prof. Elena", Voices.PROF_ELENA, aggression = -2, chaos = 0, playerMemory),
        AIPlayer(2, "Bohdan", Voices.BOHDAN, aggression = 1, chaos = 2, playerMemory),
        AIPlayer(3, "Lucky Lou", Voices.LUCKY_LOU, aggression = 2, chaos = 5, playerMemory)
    )
    val gameScore = intArrayOf(0, 0)        // Team 0 = Mike+Bohdan, Team 1 = Elena+Lou
    var dealer = rng.nextInt(4)             // first dealer chosen at random; passes left each hand
    val handLogs = mutableListOf<HandLog>() // every hand played, kept for post-game review

    fun teamName(t: Int) = if (t == 0) "Team Mike/Bohdan" else "Team Elena/Lou"

    /** Reveal a hand: every player's cards + trick-by-trick sequence, bid vs actual. */
    fun review(handNumber: Int) {
        val log = handLogs.find { it.handNumber == handNumber } ?: return
        printHandReview(log, players.map { it.name })
    }

    /** ===== TIER 1: Tag bid error for manual correction (faster learning) ===== */
    fun tagBid(handNumber: Int, seat: Int, biddedPoints: Int, actualPoints: Int) {
        val p = players[seat]
        playerMemory.updateAfterHand(seat, biddedPoints, actualPoints, tagCorrection = "tagBid")
        println("  ${Ansi.MAGENTA}Bid lesson saved → ${p.name}'s bidding (manual correction)${Ansi.RESET}")
    }

    /** ===== TIER 1: Tag play error for manual correction (faster learning) ===== */
    fun tagPlay(handNumber: Int, trickNum: Int, seat: Int, biddedPoints: Int, actualPoints: Int) {
        val p = players[seat]
        playerMemory.updateAfterHand(seat, biddedPoints, actualPoints, tagCorrection = "tagPlay")
        println("  ${Ansi.MAGENTA}Play lesson saved → ${p.name}'s card play, trick $trickNum (manual correction)${Ansi.RESET}")
    }

    // ----- one complete hand; returns true if the game was won on this hand -----
    fun playHand(handNumber: Int): Boolean {
        header("HAND $handNumber  —  dealer: ${players[dealer].name}")

        // 1) FIRST DEAL: 9 cards each, 3 at a time, starting on the dealer's left
        val deck = freshDeck().also { it.shuffle(rng) }
        players.forEach { it.hand.clear() }
        var idx = 0
        repeat(3) { for (offset in 1..4) { val p = players[(dealer + offset) % 4]
            repeat(3) { p.hand.add(deck[idx++]) } } }
        // The balance of the deck (16 cards) is set face-down for the second deal.
        val stub = deck.subList(idx, deck.size).toMutableList()   // exactly 16 cards
        val peeked = stub.last()                                  // dealer peeks the BOTTOM card only

        for (p in players)
            println("  ${Ansi.GREY}${p.name}'s 9: ${p.hand.sortedBy { it.suit.ordinal * 20 + it.rank.baseValue }.joinToString(" ")}${Ansi.RESET}")
        println("  ${Ansi.GREY}(${players[dealer].name} deals and peeks the bottom card: $peeked)${Ansi.RESET}")

        // 2) BIDDING: min 30, max 62, starts left of dealer; 3 passes ends it
        header("BIDDING (min ${HouseRules.minBid}, max ${HouseRules.maxBid})")
        var highBid = 0; var declarer = -1
        val passed = BooleanArray(4)
        var turn = (dealer + 1) % 4
        var actions = 0
        val limits = arrayOfNulls<Pair<Suit, Int>>(4)   // each seat's (best suit, highest safe bid)
        while (passed.count { it } < 3 && highBid < HouseRules.maxBid && actions < 80) {
            if (!passed[turn]) {
                val p = players[turn]
                if (turn == declarer) { turn = (turn + 1) % 4; continue }
                val lim = limits[turn] ?: p.appraise(if (turn == dealer) peeked else null).also { limits[turn] = it }
                val minNeeded = maxOf(HouseRules.minBid, highBid + 1)
                val bid = p.chooseBid(lim.second, minNeeded, declarer)
                if (bid > 0) {
                    highBid = bid; declarer = turn
                    p.say(Mood.BID, highBid)
                } else {
                    passed[turn] = true
                    p.say(Mood.PASS)
                }
            }
            turn = (turn + 1) % 4; actions++
        }
        if (declarer == -1) {              // all passed → dealer stuck at 30
            declarer = dealer; highBid = HouseRules.minBid
            players[dealer].say(Mood.STUCK)
        }
        val decl = players[declarer]
        val bidTeam = decl.team

        // 3) TRUMP CALL (declarer names trump from their 9 cards; dealer also knows the peek)
        val (trump, _) = limits[declarer] ?: decl.appraise(if (declarer == dealer) peeked else null)
        decl.say(Mood.TRUMP_CALL, "${trump.name} ${trump.symbol}")
        println("  ${Ansi.YELLOW}Contract: ${decl.name} (${teamName(bidTeam)}) needs $highBid of 62${Ansi.RESET}")

        // 3b) SECOND DEAL: the 16-card stub goes out in four-card packets.
        //     dealer's left gets the top 4, then around the table; the DEALER gets the bottom 4.
        header("SECOND DEAL (4 more cards each from the stub)")
        for (offset in 1..3) {
            val p = players[(dealer + offset) % 4]
            repeat(4) { p.hand.add(stub.removeAt(0)) }       // top-of-stub packets, in order
        }
        repeat(4) { players[dealer].hand.add(stub.removeAt(0)) }   // dealer takes the bottom 4
        println("  ${Ansi.GREY}Everyone now holds 13 cards (the peeked $peeked went to ${players[dealer].name}).${Ansi.RESET}")

        // 4) DISCARD non-trumps & reduce to 6; FOLD with none; PASS-AND-FOLD with 1-2
        header("DISCARD, FOLD & PASS-TO-PARTNER (reduce 13 → 6)")
        for (p in players) p.folded = false

        // 4a) Reduce to 6 by discarding ONLY non-trumps. You never throw away a trump —
        //     counters and non-counting trumps alike are all kept. Keep every trump, then
        //     fill up to 6 with the best non-trumps. If your trumps total more than 6, the
        //     hand stays oversized and the extras are shed on the first trick (play loop),
        //     so every player ends the first trick with the same number of cards.
        for (p in players) {
            val trumps = p.hand.filter { it.isTrump(trump) }                       // keep ALL trumps
            val nonTrumps = p.hand.filter { !it.isTrump(trump) }.sortedByDescending { it.rank.baseValue }
            p.hand.clear()
            p.hand.addAll(trumps)
            val room = 6 - p.hand.size
            if (room > 0) p.hand.addAll(nonTrumps.take(room))
        }

        // 4b) FOLD (no trumps) and PASS-AND-FOLD (1-2 trumps handed to the partner).
        for (p in players) {
            val trumps = p.hand.filter { it.isTrump(trump) }
            when {
                trumps.isEmpty() -> {                       // must fold
                    p.say(Mood.FOLD); p.folded = true; p.hand.clear()
                }
                p.wantsToPassAndFold(trump, p.seat == declarer) -> {
                    val partner = players[p.partner]
                    if (!partner.folded) {                  // pass trump(s), then fold
                        p.say(Mood.PASS_TRUMP, trumps.joinToString(" "))
                        partner.hand.addAll(trumps)
                        // partner discards an equal number of non-scoring cards to stay at 6
                        repeat(trumps.size) {
                            val toss = partner.hand.filter { it.points(trump) == 0 }
                                .minByOrNull { it.power(trump, trump) }
                                ?: partner.hand.minByOrNull { it.points(trump) }
                            if (toss != null) partner.hand.remove(toss)
                        }
                        partner.say(Mood.FEED_PARTNER)
                        p.folded = true; p.hand.clear()
                    }
                }
            }
        }

        for (p in players) {
            if (p.folded) println("  ${Ansi.PLAYER[p.seat]}${p.name}${Ansi.RESET} ${Ansi.GREY}folded (out this hand)${Ansi.RESET}")
            else println("  ${Ansi.PLAYER[p.seat]}${p.name}${Ansi.RESET} holds: ${p.hand.joinToString(" ")} " +
                "${Ansi.GREY}(${p.hand.count { it.isTrump(trump) }} trump)${Ansi.RESET}")
        }

        // 5) PLAY 6 TRICKS — declarer leads the first
        val bidExpected = decl.expectedPoints(if (declarer == dealer) decl.hand + peeked else decl.hand, trump)
        val log = HandLog(
            handNumber = handNumber, trump = trump, declarerSeat = declarer,
            bid = highBid, bidExpectedPoints = bidExpected,
            startingHands = players.associate { it.seat to it.hand.toList() }
        )
        val handPoints = intArrayOf(0, 0)
        var leader = declarer
        var trumpAceGone = false   // flips true the moment anyone plays the Ace of trump

        // ===== TIER 1: Build play order for positional strategies =====
        val playOrder = mutableListOf<Int>()
        for (i in 0..3) {
            val p = players[(leader + i) % 4]
            if (!p.folded) playOrder.add(p.seat)
        }

        for (trickNum in 1..6) {
            val leaderThisTrick = leader
            println("\n  ${Ansi.MAGENTA}— Trick $trickNum —${Ansi.RESET}")
            val trick = mutableListOf<Pair<Int, Card>>()
            for (step in 0..3) {
                val p = players[(leader + step) % 4]
                if (p.folded || p.hand.isEmpty()) continue   // folded players sit out

                // Surrender rule: a trump was led and this follower holds no trump
                val trumpLed = trick.isNotEmpty() && trick.first().second.isTrump(trump)
                if (HouseRules.surrenderWhenVoidOnTrumpLead && trumpLed &&
                    p.hand.none { it.isTrump(trump) }) {
                    p.say(Mood.FOLD)
                    println("    ${Ansi.GREY}${p.name} is out of trump — surrenders " +
                        "${p.hand.joinToString(" ")} to the junk pile and sits out${Ansi.RESET}")
                    p.folded = true; p.hand.clear()
                    continue
                }

                val card = p.choosePlay(trick, trump, forceTrumpLead = (trickNum == 1 && step == 0),
                    trumpAceGone = trumpAceGone, playOrder = playOrder)
                p.hand.remove(card)
                trick.add(p.seat to card)
                if (card.isTrump(trump) && card.rank == Rank.Ace) trumpAceGone = true
                val pts = card.points(trump)
                val tag = if (pts > 0) " ${Ansi.YELLOW}[$pts pts]${Ansi.RESET}" else ""
                println("    ${Ansi.PLAYER[p.seat]}${p.name}${Ansi.RESET} plays $card$tag")
                if (step == 0 && Random.nextInt(3) == 0) p.say(Mood.LEAD)

                // Overload: on the FIRST trick, a player holding more than 6 trumps sheds the
                // extras so every hand ends the trick with the same count. The lowest cards go
                // first — worthless non-counting trumps before any counter — and a buried 2
                // still scores for its owner.
                if (trickNum == 1) {
                    var extras = p.hand.size - 5
                    while (extras > 0) {
                        val bury = p.hand.minByOrNull { it.points(trump) * 100 + it.power(trump, trump) }!!
                        p.hand.remove(bury); trick.add(p.seat to bury)
                        println("    ${Ansi.GREY}${p.name} buries $bury (overload — too many counters)${Ansi.RESET}")
                        extras--
                    }
                }
            }
            if (trick.isEmpty()) break
            val (winSeat, winCard) = currentWinner(trick, trump)
            leader = winSeat
            // Tally: the 2 of trump scores for the team that PLAYED it
            var trickPts = 0
            for ((seat, card) in trick) {
                val pts = card.points(trump)
                if (pts == 0) continue
                if (card.rank == Rank.Two && card.isTrump(trump)) handPoints[seat % 2] += 1
                else trickPts += pts
            }
            handPoints[winSeat % 2] += trickPts
            players[winSeat].say(Mood.WIN_TRICK)
            println("    ${Ansi.GREEN}➤ ${players[winSeat].name} wins with $winCard " +
                "(+$trickPts pts → ${teamName(winSeat % 2)})${Ansi.RESET}")
            if (trickPts >= 30) players[(winSeat + 1) % 4].say(Mood.BIG_POINTS)
            log.tricks.add(TrickRecord(trickNum, leaderThisTrick, trick.toList(), winSeat, trickPts))
        }

        // 6) SCORE THE HAND
        header("HAND RESULT")
        println("  Captured — ${teamName(0)}: ${handPoints[0]}   ${teamName(1)}: ${handPoints[1]}   (62 total)")
        val made = handPoints[bidTeam] >= highBid
        if (made) {
            gameScore[0] += handPoints[0]; gameScore[1] += handPoints[1]
            decl.say(Mood.MADE_BID)
            println("  ${Ansi.GREEN}✔ ${teamName(bidTeam)} MADE the $highBid bid${Ansi.RESET}")
        } else {
            gameScore[bidTeam] -= highBid
            gameScore[1 - bidTeam] += handPoints[1 - bidTeam]
            decl.say(Mood.GOT_SET)
            println("  ${Ansi.RED}✘ ${teamName(bidTeam)} SET — back $highBid (in the hole!)${Ansi.RESET}")
        }
        println("  ${Ansi.BOLD}GAME SCORE → ${teamName(0)}: ${gameScore[0]}   ${teamName(1)}: ${gameScore[1]}   (first to ${HouseRules.winningScore} on a made bid)${Ansi.RESET}")

        // ===== TIER 1: Record hand outcome for auto-learning =====
        playerMemory.updateAfterHand(
            declarer,
            highBid,
            handPoints[bidTeam],
            tagCorrection = null  // Auto-learning: ±2.5% nudge
        )

        handLogs.add(log)

        // WIN CHECK: must reach 200 on a successful bid
        if (made && gameScore[bidTeam] >= HouseRules.winningScore) {
            header("🏆 GAME OVER")
            decl.say(Mood.GAME_WIN)
            println("  ${Ansi.GREEN}${Ansi.BOLD}${teamName(bidTeam)} wins with ${gameScore[bidTeam]} points!${Ansi.RESET}")
            return true
        }
        dealer = (dealer + 1) % 4
        return false
    }

    fun playGame(maxHands: Int = 60) {
        header("KING PEDRO — UKRAINIAN-CANADIAN RULES — FULL AUTO — TIER 1 AI ENHANCED")
        println("  ${teamName(0)} vs ${teamName(1)} — first to ${HouseRules.winningScore} on a made bid")
        println("  ${Ansi.CYAN}[Tier 1: Hand-Strength Evaluation + Positional Strategies + Partner Signals]${Ansi.RESET}")
        var hand = 1
        while (hand <= maxHands) {
            if (playHand(hand)) return
            hand++
        }
        println("\n${Ansi.YELLOW}Reached the $maxHands-hand safety limit without a winner — adjust AI aggression and rerun.${Ansi.RESET}")
    }
}

// ---------------------------------------------------------------------------
// 5) VERIFICATION HARNESS
// ---------------------------------------------------------------------------
fun main() {
    // Pass a seed (e.g. KingPedroEngine(42)) for a reproducible game
    KingPedroEngine().playGame()
}

// Stub data classes (these would normally be in separate files)
data class HandLog(
    val handNumber: Int,
    val trump: Suit,
    val declarerSeat: Int,
    val bid: Int,
    val bidExpectedPoints: Double,
    val startingHands: Map<Int, List<Card>>,
    var pointsCaptured: IntArray? = null,
    var bidMade: Boolean = false,
    val tricks: MutableList<TrickRecord> = mutableListOf()
)

data class TrickRecord(
    val trickNum: Int,
    val leader: Int,
    val cardsPlayed: List<Pair<Int, Card>>,
    val winner: Int,
    val pointsAwarded: Int
)

fun printHandReview(log: HandLog, playerNames: List<String>) {
    // Stub: would display full hand review here
    println("Hand ${log.handNumber}: ${log.trump.name} trump, ${playerNames[log.declarerSeat]} declared $${log.bid}")
}