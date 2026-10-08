# CLAUDE.md

## Priority #1: save tokens
- Do the least work that finishes the task. Don't re-read files already read, re-derive settled facts, or explore beyond what the task needs.
- Prefer targeted reads (`grep`, line ranges) over whole-file dumps; never dump large data files whole.
- Batch independent tool calls in one turn. No subagents unless asked.
- Keep replies short: result first, no narration of options not taken.
- Keep this file short — it is loaded into every session.

## Reference
- LLM token/prompt-caching cost notes: `.claude/skills/token-caching/SKILL.md` (loaded on demand via the `token-caching` skill).
