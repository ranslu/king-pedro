"use strict";
// Look-ahead card play ("imagine the hidden cards, play it out, keep the best card").
// Uses ONLY public information + the player's own hand: no peeking at other hands.
const E=require('./engine.js');
const {isTrump,points,key,freshDeck,playFrom,cloneState}=E;

/** Build one plausible layout of everyone else's hidden cards. */
function sampleWorld(seat,st,rnd){
  const t=st.trump;
  const known=new Set(st.seen);
  for(const c of st.hands[seat]) known.add(key(c));
  for(let p=0;p<4;p++) for(const k of st.knownIn[p]) known.add(k);
  const unseenT=[], unseenN=[];
  for(const c of freshDeck()){ if(known.has(key(c))) continue; (isTrump(c,t)?unseenT:unseenN).push(c); }
  const w=cloneState(st);
  const slots=[];                                   // one entry per hidden card slot
  for(let p=0;p<4;p++){
    if(p===seat){ continue; }
    const keep=st.hands[p].filter(c=>st.knownIn[p].has(key(c)));   // publicly known cards stay put
    w.hands[p]=keep.slice();
    for(let i=keep.length;i<st.hands[p].length;i++) slots.push(p);
  }
  // every trump is still in somebody's hand (discards are never trump) -> place all unseen trumps first
  for(let i=slots.length-1;i>0;i--){ const j=Math.floor(rnd()*(i+1)); [slots[i],slots[j]]=[slots[j],slots[i]]; }
  let si=0;
  const T=unseenT.slice(); for(let i=T.length-1;i>0;i--){ const j=Math.floor(rnd()*(i+1)); [T[i],T[j]]=[T[j],T[i]]; }
  for(const c of T){ if(si>=slots.length) break; w.hands[slots[si++]].push(c); }
  // remaining slots: non-trumps, favouring high ranks (players keep their best non-trumps)
  const N=unseenN.slice();
  while(si<slots.length && N.length){
    let tot=0; for(const c of N) tot+=c.rank*c.rank;
    let r=rnd()*tot, j=0; for(;j<N.length-1;j++){ r-=N[j].rank*N[j].rank; if(r<=0) break; }
    w.hands[slots[si++]].push(N.splice(j,1)[0]);
  }
  return w;
}

/** Final game-score swing for `team` given the hand's captured points and the contract. */
function utility(team,pts,st){
  const bt=st.bidTeam, made=pts[bt]>=st.bid;
  const d=[0,0];
  if(made){ d[0]=pts[0]; d[1]=pts[1]; } else { d[bt]=-st.bid; d[1-bt]=pts[1-bt]; }
  return d[team]-d[1-team];
}

/** Factory: returns a play policy. rollout = policy used for everyone after the first card. */
function makeSmartPlay({samples=40, rollout=E.oldPlay}={}){
  return function(seat,st,legal,rnd){
    if(legal.length===1) return legal[0];
    // cards that are identical in effect (same trump status, points, and power) -> only evaluate one
    const team=seat%2, score=new Array(legal.length).fill(0);
    for(let k=0;k<samples;k++){
      const world=sampleWorld(seat,st,rnd);
      for(let i=0;i<legal.length;i++){
        const w=cloneState(world); const first=legal[i];
        const pts=playFrom(w,(s,ws,lg)=> s===seat && ws.tn===st.tn && ws.step===st.step
          ? lg.find(c=>c.suit===first.suit&&c.rank===first.rank)
          : rollout(s,ws,lg,rnd));
        score[i]+=utility(team,pts,st);
      }
    }
    let best=0; for(let i=1;i<legal.length;i++) if(score[i]>score[best]) best=i;
    return legal[best];
  };
}
module.exports={makeSmartPlay,sampleWorld,utility};
