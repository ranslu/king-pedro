# King Pedro - Project Memory & Adjustments

Snapshot exported from Randy's Claude memory on 2026-10-10 so the rules and design decisions live in the repo.
Source of truth for code is the repo; this file records intent and decisions.

## Identity / culture
- Ukrainian-Canadian trick-taking card game. Strictly Ukrainian identity, never Polish.
- Theme: Ukrainian wood/linen palette with red + blue vyshyvanka accents.
- Targets: browser table UI (single-file HTML), Kotlin core, Android scaffolding, Google Play.

## Characters (seat / style)
- Iron Mike - aggressive, South / seat 0
- Prof. Elena - cautious, West / seat 1
- Bohdan - prairie flavour, North / seat 2
- Lucky Lou - chaos, East / seat 3
- Teams: Mike + Bohdan = Team 0; Elena + Lou = Team 1
- 14 bark trigger categories per character; tribute-character template for family photos.
- Character files (characters.json, CharacterProfile.kt, BarkLibrary.kt, CharacterVoiceEngine.kt, CharacterSelectScreen.kt, character-select.html) are in Google Drive: King-Pedro/Assets.

## Rules confirmed by Randy
- Deal: dealer deals clockwise in sets of three until hands are set; remainder is set aside as a stock. Bidding, then trump is picked. Dealer then deals the stock again clockwise in sets of three; this second round starts with the dealer (not the next player).
- A seat never leads a bare King of trump without holding the Ace, or the Ace having already appeared in play (implemented in KingPedroCore.kt and mirrored in the HTML engine).
- Trump pass rule (final): a player holding one or two trump may pass ALL of them to their partner (no partial acceptance). The exchange happens once, right before the partner leads their first card of the hand. If it pushes the partner over their normal trump limit, the excess trump are laid together into the first trick as the partner's play for that trick. Highest trump among them wins as usual, but only the single highest excess card can be a point card - any point card buried beneath it is dead and scores nothing.

## AI design
- PlayerMemory.kt: per-seat CharacterMemory (bidConfidence + playWeights: winEagerness / feedPartnerBias / leadTrumpBias); hybrid learning = automatic small nudge per hand (about +/-2.5%) plus stronger override from Randy's tagged corrections (tagBid / tagPlay, about +/-15%); JSON persistence to king-pedro-memory/.
- Bidding uses an expected-tricks / points model (14-slot trump ladder, win probability by stack position), scaled by learned bidConfidence.
- Tier 1 enhancements (2026-09-26): 1A hand-strength evaluation (0.0-1.0), 1B positional weights (LEADER / MID_HAND / CLOSER), 1C partner-signal inference from the partner's last 5 bid/outcome pairs. Integration steps: docs/INTEGRATION_GUIDE.md.

## UI adjustments (king-pedro-table.html)
- Mobile-first pass: much bigger cards on phone; perspective rotation so the human's seat is always south/bottom and partner north/top.
- Partners shuffle randomly each new game by default; setup-screen seat toggle overrides for that game.
- Persistent "who won the bid + points" badge once trump is set; bidder name and each seat's pass status shown during bidding.
- Smaller, repositioned bid-amount box plus a separate larger trump-suit picker; button-contrast fixes.

## Companion tools
- King Pedro Trick Tracker (single-file HTML, same theme) for tracking a live/physical game, updated to the final trump-pass rule. Published as a Claude artifact; copy in Drive King-Pedro/Assets.
- "King Pedro - Player Strategy Guide" Claude Doc (bidding, trump, leading, partner play, dos/don'ts).

## Local Geekom notes
- Checkout: C:\Users\rslus\king-pedro. Desktop shortcut points at king-pedro-table.html using assets/king-pedro.ico.
- archive/geekom-intellij-2026-09-27/ holds the older IntelliJ-project copies of the Kotlin sources (package com.example.kingpedro, MemoryCard naming) and the 2026-09-17 table snapshot. Repo root KingPedroCore.kt / PlayerMemory.kt are the newer synced versions.
- Default branch is master. GitHub Pages serves docs/ at https://ranslu.github.io/king-pedro/.