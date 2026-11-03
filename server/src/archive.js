// Permanent store for bad-play reports and full game records sent by the table.
// One Durable Object instance ("main") holds everything in its built-in
// storage, so nothing depends on a player's browser keeping its data.
//
//   report:<id>  one bad-play report (the human's notes + the situation)
//   game:<id>    one game's full event record; re-sent after every hand, so
//                each save overwrites the previous copy of the same game

const ID_RE = /^[A-Za-z0-9_-]{8,64}$/;
const MAX_REPORT = 64 * 1024;      // bytes
const MAX_GAME = 1024 * 1024;      // bytes

async function sha256(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
const strip = ({ _tokenHash, ...rest }) => rest;   // never hand the token hash back out

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

export class Archive {
  constructor(state) {
    this.storage = state.storage;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "POST" && (path === "/report" || path === "/game")) {
      const kind = path.slice(1);
      const text = await request.text();
      if (text.length > (kind === "report" ? MAX_REPORT : MAX_GAME)) return json({ error: "too large" }, 413);
      let item;
      try { item = JSON.parse(text); } catch { return json({ error: "not JSON" }, 400); }
      if (!item || typeof item !== "object" || !ID_RE.test(String(item.id || ""))) return json({ error: "missing or bad id" }, 400);
      // Each record carries a random write token chosen by the device that created it.
      // Only its hash is stored, and an existing record can only be overwritten with the same token.
      const token = typeof item.writeToken === "string" ? item.writeToken : "";
      if (token.length < 16 || token.length > 128) return json({ error: "missing write token" }, 400);
      delete item.writeToken;
      const tokenHash = await sha256(token);
      const key = `${kind}:${item.id}`;
      const prev = await this.storage.get(key);
      if (prev && prev._tokenHash !== tokenHash) return json({ error: "id already taken" }, 409);
      await this.storage.put(key, { ...item, _tokenHash: tokenHash,
        receivedAt: prev?.receivedAt || new Date().toISOString(), updatedAt: new Date().toISOString() });
      return json({ ok: true, id: item.id });
    }

    if (request.method === "GET" && path === "/reports") {
      const all = await this.storage.list({ prefix: "report:" });
      return json([...all.values()].map(strip).sort((a, b) => String(a.at).localeCompare(String(b.at))));
    }

    if (request.method === "GET" && path === "/games") {
      // index only — full records via /game/<id>
      const all = await this.storage.list({ prefix: "game:" });
      return json([...all.values()].map((g) => ({
        id: g.id, startedAt: g.startedAt, updatedAt: g.updatedAt, players: g.players,
        humanSeats: g.humanSeats, hands: Array.isArray(g.hands) ? g.hands.length : 0, finalScore: g.score,
      })).sort((a, b) => String(a.startedAt).localeCompare(String(b.startedAt))));
    }

    const m = path.match(/^\/game\/([A-Za-z0-9_-]{8,64})$/);
    if (request.method === "GET" && m) {
      const g = await this.storage.get(`game:${m[1]}`);
      return g ? json(strip(g)) : json({ error: "not found" }, 404);
    }

    return json({ error: "not found" }, 404);
  }
}
