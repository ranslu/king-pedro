# CLAUDE.md

## Priority #1: save tokens
- Do the least work that finishes the task. Don't re-read files already read, re-derive settled facts, or explore beyond what the task needs.
- Prefer targeted reads (`grep`, line ranges) over whole-file dumps; never dump large data files whole.
- Batch independent tool calls in one turn. No subagents unless asked.
- Keep replies short: result first, no narration of options not taken.
- Keep this file short — it is loaded into every session.

## Project
King Pedro (Ukrainian-Canadian 4-player partnership card game) with AI opponents and online multiplayer.
- `king-pedro-table.html`: the single-file game (rules, AI, UI). `docs/index.html` is the GitHub Pages PWA copy (with `manifest.webmanifest`, `sw.js`, `icons/`); served at https://ranslu.github.io/king-pedro/.
- `streamlit_app.py`: embeds `docs/index.html` in Streamlit. `king-pedro-landing.html`: landing page.
- `server/`: Cloudflare Worker + Durable Object authoritative multiplayer engine (`npx wrangler deploy`). `server/src/rules.js` duplicates the rules in `king-pedro-table.html` — change both. Deployed URL is `SERVER_URL` in the table's `<script>`.
- `tools/ai-sim/`: Node scripts for tuning the AI (duplicate matches, look-ahead play); see its README.
- `KingPedroCore.kt`, `PlayerMemory.kt`: Kotlin version of the rules/AI.
- HTML files are ~75 KB — grep for the function you need instead of reading them whole.

## Reference
- LLM token/prompt-caching cost notes: `.claude/skills/token-caching/SKILL.md` (loaded on demand via the `token-caching` skill).
