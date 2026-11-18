#!/usr/bin/env python3
"""Copy each character's lines from characters/characters.json into the web game.

The game keeps a block between the markers `// <characters:begin>` and
`// <characters:end>` in king-pedro-table.html and docs/index.html; this script
rewrites that block. Lines are keyed by seat (south 0, west 1, north 2, east 3),
the same way the game's own VOICES are.

Usage: python3 tools/sync-characters.py
"""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
SEAT = {"south": 0, "west": 1, "north": 2, "east": 3}
TARGETS = [ROOT / "king-pedro-table.html", ROOT / "docs" / "index.html"]
BEGIN, END = "// <characters:begin>", "// <characters:end>"


def build_block() -> str:
    data = json.loads((ROOT / "characters" / "characters.json").read_text(encoding="utf-8"))
    by_seat = {}
    for c in data["characters"]:
        lines = {k: [str(x) for x in v if str(x).strip() and str(x).strip() != "..."]
                 for k, v in c.get("barks", {}).items()}
        by_seat[SEAT[c["seatDefault"]]] = {"name": c["name"], "voice": c.get("voice", {}), "barks": lines}
    body = json.dumps({str(k): by_seat[k] for k in sorted(by_seat)}, ensure_ascii=False, indent=1)
    return (f"{BEGIN}\n"
            "// Generated from characters/characters.json by tools/sync-characters.py. Edit the JSON, then rerun.\n"
            f"const CHARACTER_BARKS = {body};\n"
            f"{END}")


def main() -> None:
    block = build_block()
    pattern = re.compile(re.escape(BEGIN) + r".*?" + re.escape(END), re.S)
    for path in TARGETS:
        text = path.read_text(encoding="utf-8")
        if not pattern.search(text):
            raise SystemExit(f"{path}: markers {BEGIN} / {END} not found")
        path.write_text(pattern.sub(lambda _: block, text), encoding="utf-8")
        print(f"updated {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
