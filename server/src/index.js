// King Pedro online — Worker entry point. Routes HTTP/WebSocket requests to
// a per-room Durable Object (GameRoom), keyed by a short room code.
export { GameRoom } from "./room.js";

function randomCode(){
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I to avoid confusion
  let s = "";
  for(let i=0;i<4;i++) s += chars[Math.floor(Math.random()*chars.length)];
  return s;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/api/new-room") {
      // hand back a fresh, unused room code
      for (let tries = 0; tries < 20; tries++) {
        const code = randomCode();
        const id = env.ROOMS.idFromName(code);
        const stub = env.ROOMS.get(id);
        const resp = await stub.fetch("https://room/exists");
        const { exists } = await resp.json();
        if (!exists) {
          return new Response(JSON.stringify({ code }), {
            headers: { "content-type": "application/json", "access-control-allow-origin": "*" },
          });
        }
      }
      return new Response(JSON.stringify({ error: "could not allocate room code" }), { status: 500 });
    }

    const m = url.pathname.match(/^\/room\/([A-Z0-9]{4})$/);
    if (m) {
      const code = m[1];
      const id = env.ROOMS.idFromName(code);
      const stub = env.ROOMS.get(id);
      return stub.fetch(request);
    }

    return new Response("King Pedro online room server. Use /api/new-room or /room/CODE.", {
      headers: { "access-control-allow-origin": "*" },
    });
  },
};
