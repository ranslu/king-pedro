# King Pedro online server

A Cloudflare Worker + Durable Object that runs the authoritative game engine
for online multiplayer. Each room is one Durable Object instance, keyed by a
4-letter room code. It holds the real deck/hands in memory and only ever
sends a connected player their own cards — opponents' hands are redacted to
empty placeholders before being sent over the wire, so there is nothing to
find even by inspecting network traffic in devtools.

`src/rules.js` is a copy of the pure game-rule functions embedded in
`../king-pedro-table.html`'s `<script>`. If you change scoring, bidding, or
trump rules, change both copies.

## Redeploying

```
cd server
npx wrangler login      # one-time, opens a browser to authorize your Cloudflare account
npx wrangler deploy
```

The deployed URL is hardcoded as `SERVER_URL` near the top of the `<script>`
in `king-pedro-table.html` — update it there if you ever deploy under a
different Worker name/subdomain.

## Known limitation

If two players connect to a brand-new room within the same ~100ms window
(only realistic if two people click "Join" at the exact same instant), the
game can occasionally stall waiting on a connection race. It never leaks
hidden card data when this happens — worst case is the game doesn't
progress and someone needs to create a fresh room. Real usage (typing in a
4-letter code takes at least a second or two) doesn't hit this.
