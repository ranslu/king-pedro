# Characters

Imported from Google Drive `King-Pedro/Assets` (files dated 2026-08-21). Names updated to the game's Joe, Grace, Jean and Steve on 2026-11-18.

- `characters.json` — roster, behaviour profiles, voice settings, 14 bark categories, tribute-character template
- `CharacterProfile.kt`, `BarkLibrary.kt`, `CharacterVoiceEngine.kt`, `CharacterSelectScreen.kt` — Android/Compose code (package `com.slusarchuk.kingpedro.characters`)
- `character-select.html` — browser preview of the seat-the-table screen

`characters.json` is the source for what each character says in the web game. After editing it, run
`python3 tools/sync-characters.py` to copy the lines into `king-pedro-table.html` and `docs/index.html`.
The Android/Compose files (`*.kt`) are not wired into an app yet.
