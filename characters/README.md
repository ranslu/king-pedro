# Characters (Android, not yet wired in)

Imported from Google Drive `King-Pedro/Assets` (files dated 2026-08-21), unchanged.

- `characters.json` — roster, behaviour profiles, voice settings, 14 bark categories, tribute-character template
- `CharacterProfile.kt`, `BarkLibrary.kt`, `CharacterVoiceEngine.kt`, `CharacterSelectScreen.kt` — Android/Compose code (package `com.slusarchuk.kingpedro.characters`)
- `character-select.html` — browser preview of the seat-the-table screen

Not yet connected to the game loop (`KingPedroCore.kt` / `king-pedro-table.html`).
These files still use the original character names (Iron Mike, Prof. Elena, Bohdan, Lucky Lou);
the table was renamed to Joe, Grace, Jean, Steve in PR #9.
