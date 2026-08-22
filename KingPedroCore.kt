// ============================================================================
//  KingPedroCore.kt  —  Ukrainian-Canadian King Pedro, fully automated
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
//
//  HOW TO RUN:  run main() — plays one complete game to 200 with full
//  colour-coded play-by-play. Set USE_COLOR = false for plain text.
// ============================================================================

package com.example.kingpedro

import kotlin.random.Random

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
// 3) AI PLAYER
// ---------------------------------------------------------------------------
class AIPlayer(val seat: Int, val name: String, val voice: Vocabulary,
               val aggression: Int /* -3 cautious .. +4 reckless */,
               val chaos: Int /* 0..6 random swing */) {

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

    /** Estimate hand strength for the best suit; returns (suit, suggested bid).
     *  [extra] lets the dealer factor in the one card they peeked at. */
    fun appraise(extra: Card? = null): Pair<Suit, Int> {
        val cards = if (extra != null) hand + extra else hand
        var bestSuit = Suit.Hearts
        var bestScore = -1
        for (s in Suit.values()) {
            var score = 0
            for (c in cards) if (c.isTrump(s)) {
                score += when {
                    c.rank == Rank.Ace -> 7
                    c.rank == Rank.King -> 9   // 30 pts, but needs protection
                    c.rank == Rank.Queen -> 5
                    c.rank == Rank.Jack -> 4
                    c.rank == Rank.Ten -> 4
                    c.rank == Rank.Nine -> 4
                    c.rank == Rank.Five -> 5   // either Pedro
                    else -> 2
                }
            }
            if (score > bestScore) { bestScore = score; bestSuit = s }
        }
        val swing = if (chaos > 0) Random.nextInt(-chaos, chaos + 1) else 0
        val bid = (26 + bestScore + aggression + swing).coerceIn(0, HouseRules.maxBid)
        return bestSuit to bid
    }

    /** Returns a legal play given the trick so far. */
    fun choosePlay(trick: List<Pair<Int, Card>>, trump: Suit, forceTrumpLead: Boolean = false): Card {
        val legal = legalPlays(trick, trump)

        if (trick.isEmpty()) {            // ----- leading -----
            val trumps = legal.filter { it.isTrump(trump) }
            if (forceTrumpLead && trumps.isNotEmpty())   // first trick: a trump MUST be led
                return trumps.maxByOrNull { it.power(trump, trump) }!!
            return when {
                trumps.isNotEmpty() && aggression > 0 ->
                    // Draw trumps with the boss card, but never lead a bare King/Pedro into danger
                    trumps.maxByOrNull { it.power(trump, trump) }!!
                trumps.isNotEmpty() && chaos >= 4 -> trumps.random()
                else -> legal.minByOrNull { it.points(trump) * 100 + it.rank.baseValue }!!
            }
        }

        val led = trick.first().second.effectiveSuit(trump)
        val (winSeat, winCard) = currentWinner(trick, trump)
        val pointsOnTable = trick.sumOf { it.second.points(trump) }
        val lastToAct = trick.size == 3
        val partnerWinning = winSeat == partner

        // 1) Feed the partner: last to act, partner securely winning → give a Pedro
        if (lastToAct && partnerWinning) {
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
            // otherwise it would cost a trump for nothing → fall through and sluff
        }

        // 3) Can't (or won't) win → sluff the cheapest, safest card
        val safe = legal.filter { it.points(trump) == 0 }
        return (safe.ifEmpty { legal.filter { it.rank != Rank.King }.ifEmpty { legal } })
            .minByOrNull { it.power(trump, led) + it.points(trump) * 50 }!!
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
// 4) GAME ENGINE
// ---------------------------------------------------------------------------
class KingPedroEngine(seed: Long? = null) {
    private val rng = if (seed != null) Random(seed) else Random.Default

    val players = listOf(
        AIPlayer(0, "Iron Mike", Voices.IRON_MIKE, aggression = 3, chaos = 1),
        AIPlayer(1, "Prof. Elena", Voices.PROF_ELENA, aggression = -2, chaos = 0),
        AIPlayer(2, "Bohdan", Voices.BOHDAN, aggression = 1, chaos = 2),
        AIPlayer(3, "Lucky Lou", Voices.LUCKY_LOU, aggression = 2, chaos = 5)
    )
    val gameScore = intArrayOf(0, 0)        // Team 0 = Mike+Bohdan, Team 1 = Elena+Lou
    var dealer = rng.nextInt(4)             // first dealer chosen at random; passes left each hand

    fun teamName(t: Int) = if (t == 0) "Team Mike/Bohdan" else "Team Elena/Lou"

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
        while (passed.count { it } < 3 && highBid < HouseRules.maxBid && actions < 16) {
            if (!passed[turn]) {
                val p = players[turn]
                if (turn == declarer) { turn = (turn + 1) % 4; continue }
                val (_, want) = p.appraise(if (turn == dealer) peeked else null)
                val minNeeded = maxOf(HouseRules.minBid, highBid + 1)
                if (want >= minNeeded) {
                    highBid = minOf(want, HouseRules.maxBid); declarer = turn
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
        val (trump, _) = decl.appraise(if (declarer == dealer) peeked else null)
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
        val handPoints = intArrayOf(0, 0)
        var leader = declarer
        for (trickNum in 1..6) {
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

                val card = p.choosePlay(trick, trump, forceTrumpLead = (trickNum == 1 && step == 0))
                p.hand.remove(card)
                trick.add(p.seat to card)
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
        header("KING PEDRO — UKRAINIAN-CANADIAN RULES — FULL AUTO")
        println("  ${teamName(0)} vs ${teamName(1)} — first to ${HouseRules.winningScore} on a made bid")
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
