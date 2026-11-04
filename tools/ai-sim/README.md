# King Pedro AI simulator

Node scripts used to tune the table's AI. The rules mirror `king-pedro-table.html`.

- `engine.js`: rules, the previous AI, and a hand runner that can resume from any mid-hand state
- `smart.js`: look-ahead card play. It builds hidden-hand layouts from public information only, plays each one out, and keeps the best card
- `heur2.js`: quick card-play rules used inside the look-ahead play-outs
- `match.js`: duplicate matches. Each deal is played twice with the teams swapped, so luck cancels out
- `bidmatch.js`: the same, but each team bids with its own bid code
- `collect2.js` / `fit3.js`: generate hands and refit the bidding model (`newbid3_nojump.js` holds the current one)

Results (per hand, vs the previous AI): look-ahead card play **+26.9 ± 0.9** points;
opening bids at the minimum instead of jumping **+2.0 ± 0.7** points.
- `analyze-position.js`: rebuilds one recorded play from `data/archive/games/<id>.json` and scores every legal card over many hidden-card layouts. Use it to check Bad-play reports before changing the AI
- `guard.js` / `guardmatch.js`: the counter-safety guard experiment (2026-11-04). No rule improved play and two made it worse, so it isn't used in the game; see `docs/BAD-PLAYS.md`
