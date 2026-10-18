// Pure King Pedro rule functions — kept in lockstep with the copies embedded in
// king-pedro-table.html's <script>. No DOM/browser APIs here; safe to run on
// the Cloudflare Worker. If you change scoring/trump/bidding rules, change both.

export const RULES = { mustFollowSuit:false, surrenderWhenVoidOnTrumpLead:true, winningScore:200, minBid:30, maxBid:62 };
export const SUITS = ["hearts","diamonds","clubs","spades"];
const RED = s => s==="hearts"||s==="diamonds";
const sameColor = (a,b)=>RED(a)===RED(b);

export const DEFAULT_PLAYERS = [
  {seat:0,name:"Iron Mike",  tag:"Aggressive", aggr:3, chaos:1},
  {seat:1,name:"Prof. Elena",tag:"Cautious",   aggr:-2,chaos:0},
  {seat:2,name:"Bohdan",     tag:"Chaotic",    aggr:1, chaos:2},
  {seat:3,name:"Lucky Lou",  tag:"Gambler",    aggr:2, chaos:5},
];

export function isTrump(c,t){ return c.suit===t || (c.rank===5 && c.suit!==t && sameColor(c.suit,t)); }
export function effSuit(c,t){ return isTrump(c,t)? t : c.suit; }
export function points(c,t){
  if(!isTrump(c,t)) return 0;
  switch(c.rank){ case 13:return 30; case 10:return 10; case 9:return 9; case 5:return 5;
    case 14:case 11:case 2:return 1; default:return 0; }
}
export function power(c,t,led){
  if(isTrump(c,t)){
    let r;
    if(c.rank===5 && c.suit===t) r=5;
    else if(c.rank===5) r=4;
    else if(c.rank<=4) r=c.rank-1;
    else r=c.rank;
    return 200+r;
  }
  return c.suit===led ? c.rank : 0;
}
export function freshDeck(){ const d=[]; for(const s of SUITS) for(let r=2;r<=14;r++) d.push({suit:s,rank:r}); return d; }
export function shuffle(a){ for(let i=a.length-1;i>0;i--){ const j=Math.floor(Math.random()*(i+1)); [a[i],a[j]]=[a[j],a[i]]; } return a; }

export function appraise(hand,extra){
  const cards = extra ? hand.concat([extra]) : hand;
  let bestSuit="hearts", bestScore=-1;
  for(const s of SUITS){
    let sc=0;
    for(const c of cards) if(isTrump(c,s)){
      sc += c.rank===14?7 : c.rank===13?9 : c.rank===12?5 : c.rank===11?4 :
            c.rank===10?4 : c.rank===9?4 : c.rank===5?5 : 2;
    }
    if(sc>bestScore){ bestScore=sc; bestSuit=s; }
  }
  return {suit:bestSuit, score:bestScore};
}
// ---- BIDDING MODEL (same as king-pedro-table.html; fitted from 18,000 hands played with the look-ahead card play) ----
export const BID_MODEL = {
  // weights: [base, A, Q, J, 10, 9, Pedro5, off5, 2, each low trump(3,4,6,7,8), dealer]
  K0:{w:[22.29, 7.4, 2.2, 2.21, 2.5, 2.57, 1.4, 1.34, 1.88, 0.96, -1.29],
      q:{"0.15":-20.7,"0.20":-18.1,"0.25":-16.1,"0.30":-14.2,"0.35":-12.3,"0.40":-10.4,"0.45":-8,"0.50":-5.1}},
  K1:{w:[39.21, 9.01, 3.02, 3.17, 3.38, 3.62, 0.85, 0.66, 3.29, 2.1, -2.2],
      q:{"0.15":-9.8,"0.20":-7.3,"0.25":-5.4,"0.30":-3.9,"0.35":-2.3,"0.40":-1.2,"0.45":0.5,"0.50":1.6}}
};
// how sure each character wants to be before bidding (probability of making it)
export const BID_CONFIDENCE = [0.55, 0.70, 0.62, 0.50];   // Mike, Elena, Bohdan, Lou
export function bidFeatures(cards,s,isDealer){
  const T=cards.filter(c=>isTrump(c,s)), has=r=>T.some(c=>c.rank===r&&c.suit===s)?1:0;
  return {K:has(13), x:[1,has(14),has(12),has(11),has(10),has(9),has(5),
    T.some(c=>c.rank===5&&c.suit!==s)?1:0, has(2),
    T.filter(c=>c.suit===s&&[3,4,6,7,8].includes(c.rank)).length, isDealer?1:0]};
}
export function expectedPts(cards,s,isDealer){ const f=bidFeatures(cards,s,isDealer), m=BID_MODEL["K"+f.K];
  return {mean:f.x.reduce((a,v,i)=>a+v*m.w[i],0), K:f.K}; }
/** Highest bid this seat is willing to make, and in which suit. */
export function safeBid(p,hand,extra,isDealer){
  const cards=extra?hand.concat([extra]):hand;
  let conf=BID_CONFIDENCE[p.seat] ?? 0.72;
  if(p.chaos>0) conf -= (Math.random()*p.chaos)/100;            // gamblers get braver on a whim
  const risk=Math.min(0.50,Math.max(0.15,Math.round((1-conf)*20)/20)).toFixed(2);
  let best={suit:"hearts",bid:0,mean:-1};
  for(const s of SUITS){ const e=expectedPts(cards,s,isDealer);
    const b=Math.floor(e.mean+BID_MODEL["K"+e.K].q[risk]);
    if(b>best.bid || (b===best.bid && e.mean>best.mean)) best={suit:s,bid:b,mean:e.mean}; }
  return best;
}
/** AI bid decision: returns the bid to make, or 0 to pass. Opens at the minimum (tested: +2 pts/hand vs jumping). */
export function aiBid(seat,limit,need,declarer){
  if(limit.bid<need) return 0;
  // don't bid over your own partner unless you're clearly stronger
  if(declarer>=0 && declarer%2===seat%2 && limit.bid < need+8) return 0;
  const j = declarer>=0 ? 0.5 : 0;          // when overcalling an opponent, jump to cut the war short
  return Math.min(RULES.maxBid, need + Math.floor((limit.bid-need)*j));
}
export function legalPlays(hand,trick,t){
  if(trick.length===0) return hand.slice();
  const led = effSuit(trick[0].card,t);
  const trumpLed = led===t;
  const follows = hand.filter(c=>effSuit(c,t)===led);
  if(RULES.mustFollowSuit) return follows.length? follows : hand.slice();
  if(trumpLed) return follows.length? follows : hand.slice();
  return hand.slice();
}
export function currentWinner(trick,t){
  const led = effSuit(trick[0].card,t);
  let best=trick[0];
  for(const e of trick) if(power(e.card,t,led) > power(best.card,t,led)) best=e;
  return best;
}
const maxBy=(a,f)=>a.reduce((b,c)=>f(c)>f(b)?c:b,a[0]);
const minBy=(a,f)=>a.length?a.reduce((b,c)=>f(c)<f(b)?c:b,a[0]):null;
export { maxBy, minBy };
export function choosePlay(p,hand,trick,t,forceTrumpLead,aceGone){
  const legal = legalPlays(hand,trick,t);
  const partner=(p.seat+2)%4;
  if(trick.length===0){
    const trumps=legal.filter(c=>isTrump(c,t));
    // Never lead a bare King of trump (30 pts) unless you also hold the Ace,
    // or the Ace has already appeared in play from anyone — lead low instead
    // to flush it out first; once it's gone, the King leads safely.
    const hasAce = trumps.some(c=>c.rank===14);
    const safeTrumps = (hasAce||aceGone) ? trumps : trumps.filter(c=>c.rank!==13);
    if(forceTrumpLead && trumps.length) return maxBy(safeTrumps.length?safeTrumps:trumps,c=>power(c,t,t));
    if(safeTrumps.length && p.aggr>0) return maxBy(safeTrumps,c=>power(c,t,t));
    if(trumps.length && p.chaos>=4) return trumps[Math.floor(Math.random()*trumps.length)];
    return minBy(legal,c=>points(c,t)*100+c.rank);
  }
  const led=effSuit(trick[0].card,t);
  const win=currentWinner(trick,t);
  const onTable=trick.reduce((a,e)=>a+points(e.card,t),0);
  const last=trick.length===3, partnerWinning=win.seat===partner;
  if(last && partnerWinning){
    const feed=legal.filter(c=>c.rank===5&&isTrump(c,t)).sort((a,b)=>points(b,t)-points(a,t))[0];
    if(feed) return {card:feed,say:"feed"};
  }
  const winners=legal.filter(c=>power(c,t,led)>power(win.card,t,led));
  if(winners.length && (onTable>0 || !partnerWinning)){
    const cheap=winners.filter(c=>c.rank!==13 && !(c.rank===5&&isTrump(c,t)));
    const pick=minBy(cheap.length?cheap:winners,c=>power(c,t,led));
    if(onTable>0) return pick;
    const freeWin=minBy(cheap.filter(c=>!isTrump(c,t)),c=>power(c,t,led));
    if(freeWin) return freeWin;
  }
  const safe=legal.filter(c=>points(c,t)===0);
  const pool=safe.length?safe:(legal.filter(c=>c.rank!==13).length?legal.filter(c=>c.rank!==13):legal);
  return minBy(pool,c=>power(c,t,led)+points(c,t)*50);
}
export function wantsPass(hand,t,aggr,chaos,isDeclarer){
  if(isDeclarer) return false;
  const n=hand.filter(c=>isTrump(c,t)).length;
  if(n<1||n>2) return false;
  return aggr + Math.floor(Math.random()*(chaos+1)) < 2;
}
export function handSort(h){
  return h.slice().sort((a,b)=>SUITS.indexOf(a.suit)*20+a.rank-(SUITS.indexOf(b.suit)*20+b.rank));
}
