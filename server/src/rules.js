// Pure King Pedro rule functions — kept in lockstep with the copies embedded in
// king-pedro-table.html's <script>. No DOM/browser APIs here; safe to run on
// the Cloudflare Worker. If you change scoring/trump/bidding rules, change both.

export const RULES = { mustFollowSuit:false, surrenderWhenVoidOnTrumpLead:true, winningScore:200, minBid:30, maxBid:62 };
export const SUITS = ["hearts","diamonds","clubs","spades"];
const RED = s => s==="hearts"||s==="diamonds";
const sameColor = (a,b)=>RED(a)===RED(b);

export const DEFAULT_PLAYERS = [
  {seat:0,name:"Joe",        tag:"Aggressive", aggr:3, chaos:1},
  {seat:1,name:"Grace",      tag:"Cautious",   aggr:-2,chaos:0},
  {seat:2,name:"Jean",       tag:"Chaotic",    aggr:1, chaos:2},
  {seat:3,name:"Steve",      tag:"Gambler",    aggr:2, chaos:5},
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
export const BID_CONFIDENCE = [0.55, 0.70, 0.62, 0.50];   // Joe, Grace, Jean, Steve
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

// ============================================================
//  LOOK-AHEAD CARD PLAY — copied unchanged from king-pedro-table.html
//  (cardKey .. smartPlay, incl. aceSignal) so online AI plays exactly like
//  the device AI. If you change it in the table, copy it here too.
// ============================================================
export const cardKey=c=>c.suit[0]+c.rank;

// fast play-out of the rest of a hand from any mid-hand state (mirrors playHand's trick loop)
function simPlayFrom(st, policy){
  const t=st.trump;
  for(; st.tn<=6; st.tn++, st.step=0){
    if(st.step===0) st.trick=[];
    for(; st.step<4; st.step++){
      const s=(st.leader+st.step)%4;
      if(st.folded[s]||st.hands[s].length===0) continue;
      const trumpLed = st.trick.length>0 && isTrump(st.trick[0].card,t);
      if(RULES.surrenderWhenVoidOnTrumpLead && trumpLed && !st.hands[s].some(c=>isTrump(c,t))){
        for(const c of st.hands[s]) st.seen.add(cardKey(c));
        st.folded[s]=true; st.hands[s]=[]; continue;
      }
      const legal=legalPlays(st.hands[s],st.trick,t);
      const card=policy(s,st,legal);
      st.hands[s].splice(st.hands[s].indexOf(card),1);
      st.trick.push({seat:s,card}); st.seen.add(cardKey(card)); st.knownIn[s].delete(cardKey(card));
      if(isTrump(card,t)&&card.rank===14) st.aceGone=true;
      if(st.tn===1){
        let extras=st.hands[s].length-5;
        while(extras>0){
          const bury=minBy(st.hands[s],c=>points(c,t)*100+power(c,t,t));
          st.hands[s].splice(st.hands[s].indexOf(bury),1);
          st.trick.push({seat:s,card:bury,buried:true}); st.seen.add(cardKey(bury)); st.knownIn[s].delete(cardKey(bury));
          extras--;
        }
      }
    }
    if(st.trick.length===0) break;
    const win=currentWinner(st.trick,t);
    st.leader=win.seat;
    let trickPts=0;
    for(const e of st.trick){
      const pt=points(e.card,t); if(pt===0) continue;
      if(e.card.rank===2 && isTrump(e.card,t)) st.handPts[e.seat%2]+=1; else trickPts+=pt;
    }
    st.handPts[win.seat%2]+=trickPts;
  }
  return st.handPts;
}
function simClone(st){
  return {...st, hands:st.hands.map(h=>h.slice()), folded:st.folded.slice(), trick:st.trick.slice(),
    handPts:st.handPts.slice(), knownIn:st.knownIn.map(s=>new Set(s)), seen:new Set(st.seen)};
}
/** One plausible layout of everyone else's hidden cards, from public information only. */
function sampleWorld(seat,st){
  const t=st.trump, known=new Set(st.seen);
  for(const c of st.hands[seat]) known.add(cardKey(c));
  for(let p=0;p<4;p++) for(const k of st.knownIn[p]) known.add(k);
  const unseenT=[], unseenN=[];
  for(const c of freshDeck()){ if(known.has(cardKey(c))) continue; (isTrump(c,t)?unseenT:unseenN).push(c); }
  const w=simClone(st), slots=[];
  for(let p=0;p<4;p++){
    if(p===seat) continue;
    const keep=st.hands[p].filter(c=>st.knownIn[p].has(cardKey(c)));
    w.hands[p]=keep.slice();
    for(let i=keep.length;i<st.hands[p].length;i++) slots.push(p);
  }
  shuffle(slots); shuffle(unseenT);
  let si=0;
  for(const c of unseenT){ if(si>=slots.length) break; w.hands[slots[si++]].push(c); }   // trumps are never discarded
  while(si<slots.length && unseenN.length){                                              // players keep high side cards
    let tot=0; for(const c of unseenN) tot+=c.rank*c.rank;
    let r=Math.random()*tot, j=0; for(;j<unseenN.length-1;j++){ r-=unseenN[j].rank*unseenN[j].rank; if(r<=0) break; }
    w.hands[slots[si++]].push(unseenN.splice(j,1)[0]);
  }
  return w;
}
function handUtility(team,pts,st){
  const bt=st.bidTeam, made=pts[bt]>=st.bid, d=[0,0];
  if(made){ d[0]=pts[0]; d[1]=pts[1]; } else { d[bt]=-st.bid; d[1-bt]=pts[1-bt]; }
  return d[team]-d[1-team];
}
// quick rules used for the imagined play-outs
function topOutstanding(seat,st){
  const t=st.trump, mine=new Set(st.hands[seat].map(cardKey)); let best=0;
  for(const c of freshDeck()) if(isTrump(c,t) && !st.seen.has(cardKey(c)) && !mine.has(cardKey(c))){ const p=power(c,t,t); if(p>best) best=p; }
  return best;
}
function quickPlay(seat,st,legal){
  const t=st.trump, trick=st.trick, partner=(seat+2)%4;
  const trumps=legal.filter(c=>isTrump(c,t)), top=topOutstanding(seat,st);
  const sure=c=>isTrump(c,t) && power(c,t,t)>top;
  if(trick.length===0){
    const boss=trumps.filter(sure);
    if(boss.length) return maxBy(boss,c=>-points(c,t)+power(c,t,t)*0.01+(c.rank===13?100:0));
    if(st.tn===1 && trumps.length){
      const np=trumps.filter(c=>points(c,t)<=1);
      return np.length? maxBy(np,c=>power(c,t,t)) : minBy(trumps,c=>points(c,t)*10+power(c,t,t));
    }
    const nonT=legal.filter(c=>!isTrump(c,t));
    if(nonT.length) return maxBy(nonT,c=>c.rank);
    const np=trumps.filter(c=>points(c,t)<=1);
    return np.length? maxBy(np,c=>power(c,t,t)) : minBy(trumps,c=>points(c,t)*10+power(c,t,t));
  }
  const led=effSuit(trick[0].card,t), win=currentWinner(trick,t);
  const onTable=trick.reduce((a,e)=>a+points(e.card,t),0);
  let others=0; for(let k=1;k<4;k++){ const s=(seat+k)%4; if(s===st.leader) break;
    if(!st.folded[s] && st.hands[s].length && !trick.some(e=>e.seat===s)) others++; }
  const lastToPlay=others===0, partnerWinning=win.seat===partner;
  if(partnerWinning && (lastToPlay || (isTrump(win.card,t) && power(win.card,t,t)>top))){
    const pts=legal.filter(c=>points(c,t)>0 && c.rank!==2); if(pts.length) return maxBy(pts,c=>points(c,t));
  }
  const winners=legal.filter(c=>power(c,t,led)>power(win.card,t,led));
  if(winners.length && !partnerWinning){
    const secure=winners.filter(c=>lastToPlay || sure(c));
    if(secure.length) return maxBy(secure,c=>points(c,t)*100-power(c,t,led));
    if(onTable>0){ const noK=winners.filter(c=>c.rank!==13); return minBy(noK.length?noK:winners,c=>power(c,t,led)+points(c,t)*3); }
  }
  const zero=legal.filter(c=>points(c,t)===0);
  if(zero.length) return minBy(zero,c=>(isTrump(c,t)?100:0)+power(c,t,led));
  const two=legal.find(c=>c.rank===2&&isTrump(c,t)); if(two) return two;
  return minBy(legal,c=>points(c,t)*10+power(c,t,led));
}
function rolloutPlay(s,st,legal){
  if(Math.random()<0.5) return quickPlay(s,st,legal);
  const r=choosePlay(DEFAULT_PLAYERS[s],st.hands[s],st.trick,st.trump,st.tn===1&&st.trick.length===0,st.aceGone);
  const c=r&&r.card?r.card:r;
  return legal.includes(c)?c:legal[0];
}
const SMART_SAMPLES=60;
// The declarer's partner shows the Ace of trump (Randy's partnership signal): lead it rather
// than a lower trump, and play it on the declarer's trump lead. Measured cost-free in duplicate
// matches (tools/ai-sim/acematch.js answer,lead,decl: -0.1 ± 0.6 pts/hand); see docs/BAD-PLAYS.md.
export function aceSignal(seat,st,legal,card,declarer){
  const t=st.trump, ace=legal.find(c=>c.suit===t&&c.rank===14);
  if(!ace || card===ace || declarer!==(seat+2)%4) return card;
  const trick=st.trick.filter(e=>!e.buried);
  if(trick.length ? (trick[0].seat===declarer && isTrump(trick[0].card,t)) : isTrump(card,t)) return ace;
  // about to play any other trump (even trumping an off-suit lead): play the Ace so the declarer learns
  // where it is early (Randy's reports R1,R2,R4,R5; acematch.js answer,lead,show,decl: no cost)
  if(isTrump(card,t)) return ace;
  return card;
}
export function smartPlay(seat,st,legal){
  if(legal.length===1) return legal[0];
  const team=seat%2, score=new Array(legal.length).fill(0);
  for(let k=0;k<SMART_SAMPLES;k++){
    const world=sampleWorld(seat,st);
    for(let i=0;i<legal.length;i++){
      const w=simClone(world), first=legal[i];
      const pts=simPlayFrom(w,(s,ws,lg)=> s===seat && ws.tn===st.tn && ws.step===st.step
        ? lg.find(c=>c.suit===first.suit&&c.rank===first.rank) : rolloutPlay(s,ws,lg));
      score[i]+=handUtility(team,pts,st);
    }
  }
  let best=0; for(let i=1;i<legal.length;i++) if(score[i]>score[best]) best=i;
  return legal[best];
}
