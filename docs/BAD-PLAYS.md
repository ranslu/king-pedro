# Bad plays — compiled list

Source: `data/archive/` (games and reports backed up from the online server).
Update this file each time reports are compiled; mark items **Fixed (PR #n)** when a fix ships.

## 2026-11-04 — first compile

Data: 2 games, 10 hands, played by Randy on 2026-11-03. **No Bad-play reports were saved to the server**, so these
mistakes come from an automatic scan of the recorded games (`trick` = trick number within the hand).

### Clear mistakes found

| # | Game · hand · trick | Trump | Who | What happened | Should have |
|---|---|---|---|---|---|
| 1 | 77679f2d · 1 · 4 | ♣ (Jean bid 46) | Jean (declarer) | **Led the off-Pedro 5♠** holding 7♣ A♣ 5♠; Grace took it with the 5♣ (+10). Jean was set **41 vs 46** — this trick decided the hand. | Lead A♣ (boss) or 7♣; never lead a Pedro while the other Pedro is out. |
| 2 | 77679f2d · 4 · 2 | ♥ (Joe bid 39) | Jean | **Led 9♥** holding 6♥ 9♥ K♦ A♣ A♠; Randy's A♥ took it (+10). | Lead 6♥ to flush the Ace, keep the 9. |
| 3 | 77679f2d · 6 · 3 | ♦ (Joe bid 44) | Grace | **Led the off-Pedro 5♥** holding 5♥ 4♦ 8♦ J♦; Jean's 10♦ took it (+15). | Lead 4♦ or 8♦. |
| 4 | a215b115 · 1 · 2 | ♣ (Steve bid 50) | Joe | Steve led 7♣; Joe **played the 5♠ under it** (+5 to Steve) holding 8♣ 10♣ Q♣ 5♠ Q♠. | Win with 8♣ (or Q♣) and keep the Pedro. |
| 5 | a215b115 · 4 · 4 | ♣ (Randy bid 39) | Grace | Joe led K♠ (not trump); Grace **played 10♣**, Randy's J♣ beat it (+11 to Randy). Grace held 10♣ Q♣ A♣. | Play A♣ — sure winner, keeps the 10. |

### Pattern

All five are the same mistake: **exposing a counter (5, 9, 10) when it isn't sure to win**, while a safe card
was available. The bare-King rule already stops this for the King; the 5s, 9 and 10 have no such guard, and the
look-ahead play (60 imagined layouts) is noisy enough to pick these cards sometimes.

### Bidding (for reference)

AI bids: 8 hands, 6 made, 2 set (Jean 46 → 41, set by mistake #1; Steve 50 → 46). No bidding change suggested yet.

### Proposed fix

Add a **counter-safety guard** after the AI picks a card (`smartPlay` in `king-pedro-table.html`, same rule in
`server/src/rules.js` and `KingPedroCore.kt`):

- If the chosen card is a counter worth 5 or more, and it is **not** sure to take the trick (a higher trump is still
  unseen, or a later player can still beat it), and the partner is not already securely winning,
- then play the best non-counter legal card instead (lowest card that still wins if winning is possible; otherwise the
  cheapest card).
- Exception: the last trick, or when the counter is the only legal card.

Check: replay these 10 hands with the guard and confirm none of the 5 plays repeat, then run the `tools/ai-sim`
duplicate matches to confirm points per hand don't drop.
