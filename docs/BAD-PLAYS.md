# Bad plays — compiled list

Source: `data/archive/` (games and reports backed up from the online server).
Update this file each time reports are compiled; mark items **Fixed (PR #n)** when a fix ships.

## 2026-11-10 — Randy's saved Bad-play reports (amendment)

Source: `data/archive/reports.json` (2 reports backed up) + the Report-a-bad-play panel (3 saved, all marked safely on the server).

| # | Saved | Game · hand | Trump / bid | Randy's report |
|---|---|---|---|---|
| R1 | 03/11 23:53 | b29829d1 · 3 | ♠ / Randy 44 | Let the partner know where the Ace is, so they can scalp the opponents' trumps more aggressively. Must make the bid at least. |
| R2 | 05/11 18:41 | 758c794e · 2 | ♣ / Randy 38 | Partner should have played the Ace of trumps earlier; we could have made more points. |
| R3 | 10/11 21:57 | not backed up yet | — | "Opponent threw several cards at the first trick. All playe…" (text cut off on screen; full text still only on the server). |

Themes: R1 + R2 are both **Ace-of-trump signalling** (the "Call the Ace" strategy: lead a low trump to ask partner for the Ace; partner with the Ace plays it early). R3 looks like the **excess-trump trick-1 display** rule (excess cards laid face-up so all players see them).

Status: **Open** — not yet changed in the AI. Per the process below, R1/R2 still need `analyze-position.js` before touching play logic; R3 needs its full text from the next backup.

## 2026-11-04 — first compile

Data: 2 games, 10 hands, played by Randy on 2026-11-03. **No Bad-play reports were saved to the server**, so these
mistakes come from an automatic scan of the recorded games (`trick` = trick number within the hand).

### Flagged by the scan

| # | Game · hand · trick | Trump | Who | What happened | Looked better at first |
|---|---|---|---|---|---|
| 1 | 77679f2d · 1 · 4 | ♣ (Jean bid 46) | Jean (declarer) | **Led the off-Pedro 5♠** holding 7♣ A♣ 5♠; Grace took it with the 5♣ (+10). Jean was set **41 vs 46** — this trick decided the hand. | Lead A♣ (boss) or 7♣; never lead a Pedro while the other Pedro is out. |
| 2 | 77679f2d · 4 · 2 | ♥ (Joe bid 39) | Jean | **Led 9♥** holding 6♥ 9♥ K♦ A♣ A♠; Randy's A♥ took it (+10). | Lead 6♥ to flush the Ace, keep the 9. |
| 3 | 77679f2d · 6 · 3 | ♦ (Joe bid 44) | Grace | **Led the off-Pedro 5♥** holding 5♥ 4♦ 8♦ J♦; Jean's 10♦ took it (+15). | Lead 4♦ or 8♦. |
| 4 | a215b115 · 1 · 2 | ♣ (Steve bid 50) | Joe | Steve led 7♣; Joe **played the 5♠ under it** (+5 to Steve) holding 8♣ 10♣ Q♣ 5♠ Q♠. | Win with 8♣ (or Q♣) and keep the Pedro. |
| 5 | a215b115 · 4 · 4 | ♣ (Randy bid 39) | Grace | Joe led K♠ (not trump); Grace **played 10♣**, Randy's J♣ beat it (+11 to Randy). Grace held 10♣ Q♣ A♣. | Play A♣ — sure winner, keeps the 10. |

### Review: none of the five were mistakes

Each position was rebuilt from the game record and every legal card was scored over 2,000 imagined layouts of the
hidden cards (public information only), with `tools/ai-sim/analyze-position.js`. Average game-score result for the
player's team. The AI's card was the best choice every time:

| # | Who | Played | Best alternative | Margin |
|---|---|---|---|---|
| 1 | Jean | 5♠ −19.7 | 7♣ −67.5 | +47.8 |
| 2 | Jean | 9♥ −3.7 | 6♥ −7.8 | +4.1 |
| 3 | Grace | 5♥ −18.6 | 8♦ −29.9 | +11.3 |
| 4 | Joe | 5♠ 66.2 | 8♣ 58.7 | +7.5 |
| 5 | Grace | 10♣ 44.4 | A♣ 37.7 | +6.7 |

These plays look bad trick by trick, but the counter was usually going to be lost anyway, and keeping the strong
trumps for later tricks was worth more. The scan only looked at one trick at a time.

Caveat: the analysis plays the hand out with the same quick rules the AI uses, so it shows the AI is consistent with
a much deeper look, not that each play is perfect.

### Counter-safety guard: tested, not shipped

Duplicate matches (`tools/ai-sim/guardmatch.js`, look-ahead with vs without each rule, points per hand):

| Rule | Result |
|---|---|
| All rules together | −6.3 ± 0.5 (worse) |
| Don't throw a counter under an opponent | −1.3 ± 0.6 (worse) |
| Following: swap a risky counter for a sure safe winner | −2.1 ± 0.6 (worse) |
| Leading: swap a risky counter for a sure safe winner | +0.2 ± 0.6 (no difference) |
| Leading: lead the lowest non-counter trump instead | +0.3 ± 0.6 (no difference) |

### Bidding (for reference)

AI bids: 8 hands, 6 made, 2 set (Jean 46 → 41; Steve 50 → 46). No bidding change suggested yet.

### Next

Check every future report with `analyze-position.js` before changing the AI. Plays the analysis also dislikes are real
mistakes; plays it likes but Randy doesn't are strategy preferences.
