import {
  RULES, DEFAULT_PLAYERS, isTrump, points, legalPlays, currentWinner,
  appraise, choosePlay, wantsPass, freshDeck, shuffle, handSort,
} from "./rules.js";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Redact a hand-bearing event so a given seat only ever sees its own cards.
// Other seats' cards become empty placeholder objects — the client only
// needs the array length (to draw the right number of card backs).
function redactForSeat(event, viewerSeat) {
  if (event.type === "deal") {
    return {
      ...event,
      hands: event.hands.map((h, s) => (s === viewerSeat ? h : h.map(() => ({})))),
      peeked: viewerSeat === event.dealer ? event.peeked : null,
    };
  }
  if (event.type === "deal2" || event.type === "settle") {
    return { ...event, hands: event.hands.map((h, s) => (s === viewerSeat ? h : h.map(() => ({})))) };
  }
  return event;
}

// This Durable Object holds one game room. It uses a plain (non-hibernating)
// WebSocket accept() so the room's in-memory game state — including a
// long-lived async game loop that awaits player input — survives for the
// whole session without needing to be reconstructed from storage on every
// message. Trade-off: the room stays resident (and billed) for as long as
// any socket is open, instead of hibernating between messages. For a casual
// few-player card game this is well within Cloudflare's free tier.
export class GameRoom {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.sockets = new Map(); // connId -> WebSocket
    this.seats = [null, null, null, null]; // {connId, name} | null (AI-controlled)
    this.seatMeta = DEFAULT_PLAYERS.map((p) => ({ ...p }));
    this.hostConnId = null;
    this.started = false;
    this.gameOver = false;
    this.dealer = 0;
    this.gameScore = [0, 0];
    this.pendingResolvers = new Map(); // seat -> resolve fn
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/exists") {
      const exists = this.started || this.seats.some(Boolean);
      return new Response(JSON.stringify({ exists }), { headers: { "content-type": "application/json" } });
    }
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket", { status: 400 });
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();
    const connId = crypto.randomUUID();
    this.sockets.set(connId, server);
    if (this.hostConnId === null) this.hostConnId = connId;
    server.addEventListener("message", (ev) => this.onMessage(connId, ev));
    server.addEventListener("close", () => this.onClose(connId));
    server.addEventListener("error", () => this.onClose(connId));
    this.send(connId, { type: "welcome", connId, isHost: connId === this.hostConnId, started: this.started });
    this.broadcastRoster();
    return new Response(null, { status: 101, webSocket: client });
  }

  seatOf(connId) {
    return this.seats.findIndex((s) => s && s.connId === connId);
  }
  send(connId, msg) {
    const ws = this.sockets.get(connId);
    if (ws) { try { ws.send(JSON.stringify(msg)); } catch {} }
  }
  sendToSeat(seat, msg) {
    const occ = this.seats[seat];
    if (occ) this.send(occ.connId, msg);
  }
  isHumanControlled(seat) {
    const occ = this.seats[seat];
    return !!occ && this.sockets.has(occ.connId);
  }
  broadcast(msg) {
    for (const connId of this.sockets.keys()) this.send(connId, msg);
  }
  broadcastEvent(event) {
    for (const connId of this.sockets.keys()) {
      const seat = this.seatOf(connId);
      this.send(connId, { type: "event", event: redactForSeat(event, seat) });
    }
  }
  broadcastRoster() {
    const roster = this.seats.map((occ, s) => ({
      seat: s,
      claimed: !!occ,
      connected: this.isHumanControlled(s),
      name: occ ? occ.name : this.seatMeta[s].name,
      tag: occ ? "Human" : this.seatMeta[s].tag,
    }));
    for (const connId of this.sockets.keys()) {
      this.send(connId, {
        type: "roster", roster, started: this.started,
        isHost: connId === this.hostConnId, yourSeat: this.seatOf(connId),
      });
    }
  }

  onMessage(connId, ev) {
    let msg;
    try { msg = JSON.parse(ev.data); } catch { return; }
    try { this.handleMessage(connId, msg); }
    catch (err) {
      console.error("onMessage crashed:", err && err.stack ? err.stack : err);
      this.send(connId, { type: "error", message: "Server error handling your action: " + (err && err.message ? err.message : String(err)) });
    }
  }
  handleMessage(connId, msg) {
    if (msg.type === "claimSeat") return this.claimSeat(connId, msg.seat, msg.name);
    if (msg.type === "releaseSeat") return this.releaseSeat(connId, msg.seat);
    if (msg.type === "startGame") {
      if (connId === this.hostConnId && !this.started) {
        this.started = true;
        this.broadcastRoster();
        this.runGame().catch((err) => {
          console.error("runGame crashed:", err && err.stack ? err.stack : err);
          this.broadcast({ type: "error", message: "Server error — the game had to stop: " + (err && err.message ? err.message : String(err)) });
        });
      }
      return;
    }
    if (msg.type === "answer") {
      const seat = this.seatOf(connId);
      if (seat !== -1 && this.pendingResolvers.has(seat)) {
        const resolve = this.pendingResolvers.get(seat);
        this.pendingResolvers.delete(seat);
        resolve(msg.value);
      }
      return;
    }
  }

  claimSeat(connId, seatIdx, name) {
    if (seatIdx < 0 || seatIdx > 3) return;
    if (this.seatOf(connId) !== -1) return; // one seat per connection
    const occ = this.seats[seatIdx];
    if (occ && this.sockets.has(occ.connId)) return; // already taken by a live player
    this.seats[seatIdx] = { connId, name: (name || "").trim().slice(0, 20) || `Player ${seatIdx + 1}` };
    this.seatMeta[seatIdx].name = this.seats[seatIdx].name;
    this.broadcastRoster();
  }
  releaseSeat(connId, seatIdx) {
    const occ = this.seats[seatIdx];
    if (occ && occ.connId === connId) {
      this.seats[seatIdx] = null;
      this.seatMeta[seatIdx].name = DEFAULT_PLAYERS[seatIdx].name;
      this.broadcastRoster();
    }
  }
  onClose(connId) {
    this.sockets.delete(connId);
    if (connId === this.hostConnId) {
      const next = this.sockets.keys().next();
      this.hostConnId = next.done ? null : next.value;
    }
    if (!this.started) {
      const s = this.seatOf(connId);
      if (s !== -1) { this.seats[s] = null; this.seatMeta[s].name = DEFAULT_PLAYERS[s].name; }
    }
    this.broadcastRoster();
  }

  needInput(seat, payload) {
    return new Promise((resolve) => {
      this.pendingResolvers.set(seat, resolve);
      this.sendToSeat(seat, { type: "needInput", ...payload });
    });
  }

  async runGame() {
    this.dealer = Math.floor(Math.random() * 4);
    this.gameScore = [0, 0];
    while (!this.gameOver) {
      await this.playHand();
    }
  }

  async playHand() {
    const dealer = this.dealer, gameScore = this.gameScore;
    const hands = [[], [], [], []];
    const deck = shuffle(freshDeck());
    let idx = 0;
    for (let r = 0; r < 3; r++) for (let o = 1; o <= 4; o++) { const s = (dealer + o) % 4; for (let k = 0; k < 3; k++) hands[s].push(deck[idx++]); }
    const stub = deck.slice(idx);
    const peeked = stub[stub.length - 1];
    this.broadcastEvent({ type: "deal", hands: hands.map(handSort), dealer, peeked });
    await sleep(300);

    let highBid = 0, declarer = -1;
    const passed = [false, false, false, false];
    let turn = (dealer + 1) % 4, acts = 0;
    while (passed.filter(Boolean).length < 3 && highBid < RULES.maxBid && acts < 16) {
      if (!passed[turn] && turn !== declarer) {
        const need = Math.max(RULES.minBid, highBid + 1);
        if (this.isHumanControlled(turn)) {
          const answer = await this.needInput(turn, { needInput: "bid", seat: turn, need, max: RULES.maxBid, highBid });
          if (answer === "pass") { passed[turn] = true; this.broadcastEvent({ type: "pass", seat: turn }); }
          else { highBid = Math.max(need, Math.min(RULES.maxBid, answer | 0)); declarer = turn; this.broadcastEvent({ type: "bid", seat: turn, bid: highBid }); }
        } else {
          const p = this.seatMeta[turn];
          const a = appraise(hands[turn], turn === dealer ? peeked : null);
          const swing = p.chaos > 0 ? Math.floor(Math.random() * (2 * p.chaos + 1)) - p.chaos : 0;
          const want = Math.max(0, Math.min(RULES.maxBid, 26 + a.score + p.aggr + swing));
          if (want >= need) { highBid = Math.min(want, RULES.maxBid); declarer = turn; this.broadcastEvent({ type: "bid", seat: turn, bid: highBid }); }
          else { passed[turn] = true; this.broadcastEvent({ type: "pass", seat: turn }); }
          await sleep(450);
        }
      }
      turn = (turn + 1) % 4; acts++;
    }
    if (declarer === -1) { declarer = dealer; highBid = RULES.minBid; this.broadcastEvent({ type: "stuck", seat: dealer, bid: highBid }); }
    const bidTeam = declarer % 2;

    let trump;
    if (this.isHumanControlled(declarer)) {
      const handForChoice = (declarer === dealer ? hands[declarer].concat([peeked]) : hands[declarer]).slice();
      trump = await this.needInput(declarer, { needInput: "trump", seat: declarer, hand: handForChoice });
    } else {
      trump = appraise(hands[declarer], declarer === dealer ? peeked : null).suit;
      await sleep(450);
    }
    this.broadcastEvent({ type: "trump", seat: declarer, trump, bid: highBid, bidTeam });
    await sleep(300);

    let si = 0;
    for (let o = 1; o <= 3; o++) { const s = (dealer + o) % 4; for (let k = 0; k < 4; k++) hands[s].push(stub[si++]); }
    for (let k = 0; k < 4; k++) hands[dealer].push(stub[si++]);
    this.broadcastEvent({ type: "deal2", hands: hands.map(handSort) });
    await sleep(300);

    for (let s = 0; s < 4; s++) {
      const tr = hands[s].filter((c) => isTrump(c, trump));
      const nt = hands[s].filter((c) => !isTrump(c, trump)).sort((a, b) => b.rank - a.rank);
      let nh = tr.slice(); const room = 6 - nh.length; if (room > 0) nh = nh.concat(nt.slice(0, room));
      hands[s] = nh;
    }
    const folded = [false, false, false, false];
    for (let s = 0; s < 4; s++) {
      const tr = hands[s].filter((c) => isTrump(c, trump));
      if (tr.length === 0) { folded[s] = true; hands[s] = []; this.broadcastEvent({ type: "fold", seat: s }); }
      else {
        let doPass;
        if (s === declarer || tr.length > 2) doPass = false;
        else if (this.isHumanControlled(s)) doPass = await this.needInput(s, { needInput: "foldchoice", seat: s, trumps: tr.slice() });
        else { doPass = wantsPass(hands[s], trump, this.seatMeta[s].aggr, this.seatMeta[s].chaos, s === declarer); await sleep(350); }
        if (doPass) {
          const partner = (s + 2) % 4;
          if (!folded[partner]) {
            this.broadcastEvent({ type: "passt", seat: s, partner, cards: tr.slice() });
            hands[partner] = hands[partner].concat(tr);
            for (let n = 0; n < tr.length; n++) {
              const toss = minByLocal(hands[partner].filter((c) => points(c, trump) === 0), (c) => powerLocal(c, trump))
                || minByLocal(hands[partner], (c) => points(c, trump));
              if (toss) { const i = hands[partner].indexOf(toss); hands[partner].splice(i, 1); }
            }
            folded[s] = true; hands[s] = [];
          }
        }
      }
    }
    this.broadcastEvent({ type: "settle", hands: hands.map(handSort), folded: folded.slice(), trump });
    await sleep(300);

    const handPts = [0, 0];
    let leader = declarer;
    for (let tn = 1; tn <= 6; tn++) {
      this.broadcastEvent({ type: "trickStart", num: tn, leader });
      const trick = [];
      let ledSpoke = false;
      for (let step = 0; step < 4; step++) {
        const s = (leader + step) % 4;
        if (folded[s] || hands[s].length === 0) continue;
        const trumpLed = trick.length > 0 && isTrump(trick[0].card, trump);
        if (RULES.surrenderWhenVoidOnTrumpLead && trumpLed && !hands[s].some((c) => isTrump(c, trump))) {
          this.broadcastEvent({ type: "surrender", seat: s, cards: hands[s].slice() });
          folded[s] = true; hands[s] = []; continue;
        }
        const force = tn === 1 && step === 0;
        let card, say = null;
        if (this.isHumanControlled(s)) {
          const legal = legalPlays(hands[s], trick, trump);
          card = await this.needInput(s, { needInput: "play", seat: s, hand: hands[s].slice(), legal, trick: trick.slice() });
          const found = hands[s].find((c) => c.suit === card.suit && c.rank === card.rank);
          card = found || legal[0];
        } else {
          let res = choosePlay(this.seatMeta[s], hands[s], trick, trump, force);
          if (res.card) { card = res.card; say = res.say; } else card = res;
          await sleep(500);
        }
        hands[s].splice(hands[s].indexOf(card), 1);
        trick.push({ seat: s, card });
        this.broadcastEvent({ type: "play", seat: s, card, lead: step === 0, say, speakLead: step === 0 && !ledSpoke && Math.random() < 0.4 });
        await sleep(250);
        if (tn === 1) {
          let extras = hands[s].length - 5;
          while (extras > 0) {
            const bury = minByLocal(hands[s], (c) => points(c, trump) * 100 + powerLocal(c, trump));
            hands[s].splice(hands[s].indexOf(bury), 1);
            trick.push({ seat: s, card: bury, buried: true });
            this.broadcastEvent({ type: "play", seat: s, card: bury, buried: true });
            extras--;
          }
        }
      }
      if (trick.length === 0) break;
      const win = currentWinner(trick, trump);
      leader = win.seat;
      let trickPts = 0;
      for (const e of trick) {
        const pt = points(e.card, trump); if (pt === 0) continue;
        if (e.card.rank === 2 && isTrump(e.card, trump)) handPts[e.seat % 2] += 1;
        else trickPts += pt;
      }
      handPts[win.seat % 2] += trickPts;
      this.broadcastEvent({ type: "trickEnd", winner: win.seat, winCard: win.card, points: trickPts, captured: handPts.slice() });
      await sleep(500);
    }

    const made = handPts[bidTeam] >= highBid;
    const newScore = gameScore.slice();
    if (made) { newScore[0] += handPts[0]; newScore[1] += handPts[1]; }
    else { newScore[bidTeam] -= highBid; newScore[1 - bidTeam] += handPts[1 - bidTeam]; }
    const won = made && newScore[bidTeam] >= RULES.winningScore;
    this.broadcastEvent({ type: "handResult", captured: handPts.slice(), made, bidTeam, bid: highBid, gameScore: newScore, won, declarer });
    this.gameScore = newScore;
    this.dealer = (this.dealer + 1) % 4;
    if (won) { this.gameOver = true; await sleep(1200); }
    else await sleep(900);

    // helper used only within this function (trump/power without importing full power())
    function powerLocal(c, t) {
      if (isTrump(c, t)) {
        let r; if (c.rank === 5 && c.suit === t) r = 5; else if (c.rank === 5) r = 4; else if (c.rank <= 4) r = c.rank - 1; else r = c.rank;
        return 200 + r;
      }
      return 0;
    }
    function minByLocal(a, f) { return a.length ? a.reduce((b, c) => (f(c) < f(b) ? c : b), a[0]) : null; }
  }
}
