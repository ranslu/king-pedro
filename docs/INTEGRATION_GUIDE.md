# Tier 1 AI Enhancement Integration Guide
## King Pedro PlayerMemory.kt Upgrade

**Status:** 🟡 Ready to integrate into KingPedroCore.kt  
**Date:** September 26, 2026  
**Improvements:** Hand-strength evaluation + Positional strategies + Partner signal system

---

## 📋 What's New in PlayerMemory.kt (Tier 1A, B, C)

### **Tier 1A: Hand-Strength Evaluation**
```kotlin
fun evaluateHandStrength(heldCards: List<Card>, trump: String): HandStrength
```
- **What it does:** Pre-computes hand value at round start (0.0 = weak, 1.0 = very strong)
- **Key metrics tracked:**
  - ✅ Has High (K of trump) = +10 points
  - ✅ Has Low (3 of trump) = +1 point
  - ✅ Has Jack (off-trump) = +1 point
  - ✅ Has Pedro (5 of trump) = +5 points
  - ✅ Extra Pedros (off-trump 5s) = +2 each
  - ✅ Trump count = +0.5 per card
- **Returns:** `HandStrength` object with full breakdown

**Why it matters:** Players can now bid smarter because they know their hand value upfront.

---

### **Tier 1B: Positional Strategies**
```kotlin
fun getPositionalWeights(
    position: TablePosition,
    baseMemory: CharacterMemory,
    partnerStrength: Double
): PositionalWeights
```
- **Three positions identified:**
  - 🟢 **LEADER** (first to play)
    - More aggressive (winEagerness × 1.2)
    - Lead trump to flush it (leadTrumpBias = 0.7)
    - Focus on own strength
  
  - 🟡 **MID-HAND** (second/third to play)
    - Balanced strategy
    - Slightly partner-focused (feedPartnerBias × 1.1)
    - Mixed lead strategy
  
  - 🔵 **CLOSER** (last to play)
    - More selective (winEagerness × 0.8)
    - Maximize value recovery (fewer wasted trump)
    - Set up next trick (leadTrumpBias = 0.4)

**Why it matters:** Position determines play quality. A closer playing as if they were a leader is a classic AI mistake.

---

### **Tier 1C: Partner Signal System**
```kotlin
fun inferPartnerStrength(memory: CharacterMemory): Double
```
- **How it works:** Analyzes partner's last 5 bid/outcome pairs
  - Recent hands weighted heavier
  - Bid amount + accuracy = strength estimate
  - Returns 0.0 (weak) to 1.0 (very strong)

- **Strength tiers:**
  - 0.9 = Aggressive bidder who makes their bids
  - 0.7 = Solid, consistent partner
  - 0.5 = Conservative player
  - 0.3 = Weak bidder

**Why it matters:** If partner is strong, feed them better cards; if weak, keep control.

---

## 🔧 Integration Checklist

### Step 1: Replace PlayerMemory.kt
- [ ] Backup existing `PlayerMemory.kt` (if you have one)
- [ ] Copy new `PlayerMemory.kt` to `C:\Users\rslus\king-pedro\`

### Step 2: Update KingPedroCore.kt
Need to modify these functions to USE the new PlayerMemory methods:

#### **2a. Bidding Phase**
**Old:**
```kotlin
fun decideBid(seatPosition: Int, hand: List<Card>, trump: String): Int {
    // Basic trump ladder
    return calculateTrumpLadderBid(hand, trump)
}
```

**New:**
```kotlin
fun decideBid(seatPosition: Int, hand: List<Card>, trump: String): Int {
    val playerMemory = playerMemoryManager.getMemory(seatPosition)
    val partnerStrength = playerMemory.inferPartnerStrength(playerMemory)
    
    return playerMemory.getBidRecommendation(
        seatPosition, hand, trump, 
        partnerStrength, 
        cardsPlayed = 0  // Bidding happens before cards played
    )
}
```

#### **2b. Play Phase**
**Old:**
```kotlin
fun decidePlay(seatPosition: Int, hand: List<Card>, trump: String): Card {
    // Dumb greedy play
    return hand.first()
}
```

**New:**
```kotlin
fun decidePlay(seatPosition: Int, hand: List<Card>, trump: String): Card {
    val position = determineTablePosition(seatPosition, playOrder)
    val playerMemory = playerMemoryManager.getMemory(seatPosition)
    val partnerStrength = playerMemory.inferPartnerStrength(playerMemory)
    
    return playerMemory.getPlayRecommendation(
        seatPosition, hand, trump, position, 
        cardsPlayed = currentTrick.size,
        partnerStrength
    ) ?: hand.first()
}

fun determineTablePosition(seatPosition: Int, playOrder: List<Int>): TablePosition {
    val index = playOrder.indexOf(seatPosition)
    return when (index) {
        0 -> TablePosition.LEADER
        playOrder.size - 1 -> TablePosition.CLOSER
        else -> TablePosition.MID_HAND
    }
}
```

#### **2c. After Each Hand**
**Add:**
```kotlin
fun recordHandOutcome(seatPosition: Int, biddedPoints: Int, actualPoints: Int) {
    playerMemoryManager.updateAfterHand(
        seatPosition,
        biddedPoints,
        actualPoints,
        tagCorrection = null  // Will be set if Randy manually tags it
    )
}

// For manual corrections (Randy calls after reviewing)
fun tagBidError(seatPosition: Int, biddedPoints: Int, actualPoints: Int) {
    playerMemoryManager.updateAfterHand(
        seatPosition, biddedPoints, actualPoints,
        tagCorrection = "tagBid"
    )
}

fun tagPlayError(seatPosition: Int, biddedPoints: Int, actualPoints: Int) {
    playerMemoryManager.updateAfterHand(
        seatPosition, biddedPoints, actualPoints,
        tagCorrection = "tagPlay"
    )
}
```

### Step 3: Update king-pedro-table.html
Add UI hooks to display hand strength & position strategy:

#### **Optional: Show Hand Strength in Bidding**
```html
<!-- In bidding phase UI -->
<div id="handStrengthIndicator">
  Hand Strength: <span id="strengthBar">████░░░░░░</span> (0.4)
</div>
```

#### **Optional: Show Position in Play Phase**
```html
<!-- In play phase UI -->
<div id="positionIndicator">
  Your Position: CLOSER (maximize points)
</div>
```

---

## 🎯 Testing Checklist

After integration, play 5–10 hands and verify:

- [ ] **Hand-strength eval works** → Players bid more on strong hands, less on weak hands
- [ ] **Positional strategy works** → Leader leads trump; Closer plays conservatively
- [ ] **Partner signals work** → If you bid high, partner feeds you good cards next hand
- [ ] **No crashes** → Check browser console & Kotlin logs for errors
- [ ] **Memory persists** → Check `king-pedro-memory/seat_*.json` files after each hand

---

## 📊 Expected Behavior Changes

| Scenario | Before Tier 1 | After Tier 1 |
|----------|---------------|-------------|
| **Strong hand + weak bid** | Bid 8 | Bid 14+ (hand-strength boost) |
| **Leader with K♥ trump** | Play random card | Lead trump (position-aware) |
| **Partner bid 22, made it** | Treat partner normally | Feed partner better cards (signal boost) |
| **Closer with few trump** | Waste trump early | Play selectively (position-aware) |

---

## 🐛 Known Issues & Fixes

### Issue: "Type mismatch: Hand ≠ List<Card>"
**Fix:** Ensure your `Card` class matches the one in PlayerMemory.kt:
```kotlin
data class Card(val suit: String, val rank: String)
```

### Issue: "serialization.SerializationException"
**Fix:** Add kotlinx-serialization dependency to build.gradle.kts:
```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
```

### Issue: Memory files never load
**Fix:** Ensure `king-pedro-memory/` directory exists and is writable:
```kotlin
File("king-pedro-memory").mkdirs()
```

---

## 🚀 Next Steps (Tier 2+)

Once Tier 1 is stable:

### **Tier 2: 1-Trick Lookahead**
- Simulate: "If I trump this trick, can I win the next?"
- Uses existing trump ladder + partner info
- ~100 lines of code

### **Tier 3: Game Theory (EV Calculation)**
- Monte Carlo simulation for win probabilities
- Advanced partner coordination
- Full game-tree search (slower, much smarter)

---

## 📝 Integration Notes

🟣 **Remember to save this file to Google Drive:**  
`King-Pedro/PlayerMemory.kt` (updated version)

🟢 **After integration**, Randy can play test hands and tag corrections:
```kotlin
engine.tagBidError(0, 20, 12)   // Seat 0 overbid
engine.tagPlayError(1, 15, 8)   // Seat 1 played badly
```

Each tag nudges the AI learning by ±15% (vs. ±2.5% for auto-learning).

---

## 💾 File Locations

| File | Location | Purpose |
|------|----------|---------|
| PlayerMemory.kt | C:\Users\rslus\king-pedro\ | Main AI memory system |
| KingPedroCore.kt | C:\Users\rslus\king-pedro\ | Engine integration hooks |
| king-pedro-table.html | C:\Users\rslus\king-pedro\ | UI integration (optional) |
| Memory files | king-pedro-memory/seat_*.json | Persistent AI state |

---

**Ready to integrate? Copy PlayerMemory.kt to your project, update KingPedroCore.kt with the hooks above, and play!**
