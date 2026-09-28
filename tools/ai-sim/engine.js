"use strict";
// Standalone King Pedro engine mirroring king-pedro-table.html's playHand() after bidding,
// with pluggable per-seat play / pass policies so different AIs can be compared on identical deals.

const SUITS = ["hearts","diamonds","clubs","spades"];
const RED = s => s==="hearts"||s==="diamonds";
const sameColor = (a,b)=>RED(a)===RED(b);
const RULES = { mustFollowSuit:false, surrenderWhenVoidOnTrumpLead:true, winningScore:200, minBid:30, maxBid:62 };
function isTrump(c,t){ return c.suit===t || (c.rank===5 && c.suit!==t && sameColor(c.suit,t)); }
function effSuit(c,t){ return isTrump(c,t)? t : c.suit; }
function points(c,t){
  if(!isTrump(c,t)) return 0;
  switch(c.rank){ case 13:return 30; case 10:return 10; case 9:return 9; case 5:return 5;
    case 14:case 11:case 2:return 1; default:return 0; }
}
function power(c,t,led){
  if(isTrump(c,t)){
    let r;
    if(c.rank===5 && c.suit===t) r=5; else if(c.rank===5) r=4; else if(c.rank<=4) r=c.rank-1; else r=c.rank;
    return 200+r;
  }
  return c.suit===led ? c.rank : 0;
}
function freshDeck(){ const d=[]; for(const s of SUITS) for(let r=2;r<=14;r++) d.push({suit:s,rank:r}); return d; }
function makeRng(seed){ let x=seed>>>0||1; return ()=>{ x^=x<<13; x>>>=0; x^=x>>>17; x^=x<<5; x>>>=0; return x/4294967296; }; }
function shuffle(a,rnd){ for(let i=a.length-1;i>0;i--){ const j=Math.floor(rnd()*(i+1)); [a[i],a[j]]=[a[j],a[i]]; } return a; }
const maxBy=(a,f)=>a.reduce((b,c)=>f(c)>f(b)?c:b,a[0]);
const minBy=(a,f)=>a.length?a.reduce((b,c)=>f(c)<f(b)?c:b,a[0]):null;
const key=c=>c.suit[0]+c.rank;

function legalPlays(hand,trick,t){
  if(trick.length===0) return hand.slice();
  const led = effSuit(trick[0].card,t);
  const follows = hand.filter(c=>effSuit(c,t)===led);
  if(RULES.mustFollowSuit) return follows.length? follows : hand.slice();
  if(led===t) return follows.length? follows : hand.slice();
  return hand.slice();
}
function currentWinner(trick,t){
  const led = effSuit(trick[0].card,t);
  let best=trick[0];
  for(const e of trick) if(power(e.card,t,led) > power(best.card,t,led)) best=e;
  return best;
}

const PLAYERS = [
  {seat:0,name:"Iron Mike",  aggr:3, chaos:1},
  {seat:1,name:"Prof. Elena",aggr:-2,chaos:0},
  {seat:2,name:"Bohdan",     aggr:1, chaos:2},
  {seat:3,name:"Lucky Lou",  aggr:2, chaos:5},
];

// ---------------- the table's CURRENT card-play AI (verbatim logic) ----------------
function oldChoosePlay(p,hand,trick,t,forceTrumpLead,aceGone,rnd){
  const legal = legalPlays(hand,trick,t);
  const partner=(p.seat+2)%4;
  if(trick.length===0){
    const trumps=legal.filter(c=>isTrump(c,t));
    const hasAce = trumps.some(c=>c.rank===14);
    const safeTrumps = (hasAce||aceGone) ? trumps : trumps.filter(c=>c.rank!==13);
    if(forceTrumpLead && trumps.length) return maxBy(safeTrumps.length?safeTrumps:trumps,c=>power(c,t,t));
    if(safeTrumps.length && p.aggr>0) return maxBy(safeTrumps,c=>power(c,t,t));
    if(trumps.length && p.chaos>=4) return trumps[Math.floor(rnd()*trumps.length)];
    return minBy(legal,c=>points(c,t)*100+c.rank);
  }
  const led=effSuit(trick[0].card,t);
  const win=currentWinner(trick,t);
  const onTable=trick.reduce((a,e)=>a+points(e.card,t),0);
  const last=trick.length===3, partnerWinning=win.seat===partner;
  if(last && partnerWinning){
    const feed=legal.filter(c=>c.rank===5&&isTrump(c,t)).sort((a,b)=>points(b,t)-points(a,t))[0];
    if(feed) return feed;
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
function oldWantsPass(hand,t,aggr,chaos,isDeclarer,rnd){
  if(isDeclarer) return false;
  const n=hand.filter(c=>isTrump(c,t)).length;
  if(n<1||n>2) return false;
  return aggr + Math.floor(rnd()*(chaos+1)) < 2;
}

// ---------------- trick play: runs from any mid-hand state ----------------
// state: {hands[4], folded[4], trick[], leader, tn, step, handPts[2], aceGone, trump, knownIn[4](Set of keys), seen(Set)}
// policy(seat, state, legal) -> card
function playFrom(st, policy){
  const t=st.trump;
  for(; st.tn<=6; st.tn++, st.step=0){
    if(st.step===0) st.trick=[];
    for(; st.step<4; st.step++){
      const s=(st.leader+st.step)%4;
      if(st.folded[s]||st.hands[s].length===0) continue;
      const trumpLed = st.trick.length>0 && isTrump(st.trick[0].card,t);
      if(trumpLed && !st.hands[s].some(c=>isTrump(c,t))){
        for(const c of st.hands[s]) st.seen.add(key(c));
        st.folded[s]=true; st.hands[s]=[]; continue;
      }
      const legal=legalPlays(st.hands[s],st.trick,t);
      const card=policy(s,st,legal);
      st.hands[s].splice(st.hands[s].indexOf(card),1);
      st.trick.push({seat:s,card}); st.seen.add(key(card)); st.knownIn[s].delete(key(card));
      if(isTrump(card,t)&&card.rank===14) st.aceGone=true;
      if(st.tn===1){
        let extras=st.hands[s].length-5;
        while(extras>0){
          const bury=minBy(st.hands[s],c=>points(c,t)*100+power(c,t,t));
          st.hands[s].splice(st.hands[s].indexOf(bury),1);
          st.trick.push({seat:s,card:bury,buried:true}); st.seen.add(key(bury)); st.knownIn[s].delete(key(bury));
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
      if(e.card.rank===2) st.handPts[e.seat%2]+=1; else trickPts+=pt;
    }
    st.handPts[win.seat%2]+=trickPts;
  }
  return st.handPts;
}
function cloneState(st){
  return {...st, hands:st.hands.map(h=>h.slice()), folded:st.folded.slice(), trick:st.trick.slice(),
    handPts:st.handPts.slice(), knownIn:st.knownIn.map(s=>new Set(s)), seen:new Set(st.seen)};
}

// ---------------- a full hand after bidding ----------------
// cfg: {dealer, deck(shuffled 52), declarer, trump, bid, play[4](seat,st,legal,rnd)->card, pass[4](seat,hand,t,isDecl,rnd)->bool, rnd}
function playDealt(cfg){
  const {dealer,deck,declarer,trump,rnd}=cfg;
  const hands=[[],[],[],[]];
  let idx=0;
  for(let r=0;r<3;r++) for(let o=1;o<=4;o++){ const s=(dealer+o)%4; for(let k=0;k<3;k++) hands[s].push(deck[idx++]); }
  const stub=deck.slice(idx);
  let si=0;
  for(let o=1;o<=3;o++){ const s=(dealer+o)%4; for(let k=0;k<4;k++) hands[s].push(stub[si++]); }
  for(let k=0;k<4;k++) hands[dealer].push(stub[si++]);
  for(let s=0;s<4;s++){
    const tr=hands[s].filter(c=>isTrump(c,trump));
    const nt=hands[s].filter(c=>!isTrump(c,trump)).sort((a,b)=>b.rank-a.rank);
    let nh=tr.slice(); const room=6-nh.length; if(room>0) nh=nh.concat(nt.slice(0,room));
    hands[s]=nh;
  }
  const folded=[false,false,false,false];
  const knownIn=[new Set(),new Set(),new Set(),new Set()], seen=new Set();
  for(let s=0;s<4;s++){
    const tr=hands[s].filter(c=>isTrump(c,trump));
    if(tr.length===0){ folded[s]=true; hands[s]=[]; continue; }
    const doPass = (s!==declarer && tr.length<=2) ? cfg.pass[s](s,hands[s],trump,false,rnd,{declarer,bid:cfg.bid,hands}) : false;
    if(doPass){
      const partner=(s+2)%4;
      if(!folded[partner]){
        hands[partner]=hands[partner].concat(tr);
        for(const c of tr) knownIn[partner].add(key(c));
        for(let n=0;n<tr.length;n++){
          const toss=minBy(hands[partner].filter(c=>points(c,trump)===0),c=>power(c,trump,trump))
                  || minBy(hands[partner],c=>points(c,trump));
          if(toss){ const i=hands[partner].indexOf(toss); hands[partner].splice(i,1); knownIn[partner].delete(key(toss)); }
        }
        folded[s]=true; hands[s]=[];
      }
    }
  }
  const st={hands,folded,trick:[],leader:declarer,tn:1,step:0,handPts:[0,0],aceGone:false,trump,knownIn,seen,
            declarer,bid:cfg.bid,bidTeam:declarer%2};
  playFrom(st,(s,state,legal)=>cfg.play[s](s,state,legal,rnd));
  const made = st.handPts[st.bidTeam] >= cfg.bid;
  const delta=[0,0];
  if(made){ delta[0]=st.handPts[0]; delta[1]=st.handPts[1]; }
  else { delta[st.bidTeam]=-cfg.bid; delta[1-st.bidTeam]=st.handPts[1-st.bidTeam]; }
  return {handPts:st.handPts, made, delta, bidTeam:st.bidTeam};
}

// adapters so the old AI can be used as a play/pass policy
const oldPlay=(s,st,legal,rnd)=>oldChoosePlay(PLAYERS[s],st.hands[s],st.trick,st.trump,st.tn===1&&st.trick.length===0,st.aceGone,rnd);
const oldPass=(s,hand,t,isDecl,rnd)=>oldWantsPass(hand,t,PLAYERS[s].aggr,PLAYERS[s].chaos,isDecl,rnd);

module.exports={SUITS,RULES,isTrump,effSuit,points,power,freshDeck,makeRng,shuffle,maxBy,minBy,key,legalPlays,
  currentWinner,PLAYERS,oldChoosePlay,oldWantsPass,playFrom,cloneState,playDealt,oldPlay,oldPass};
