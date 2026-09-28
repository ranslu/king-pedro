"use strict";
// Improved quick rules (used for fast play-outs inside the look-ahead, and as a fallback).
const E=require('./engine.js');
const {isTrump,points,power,effSuit,currentWinner,key,minBy,maxBy,freshDeck}=E;

/** Highest trump power still unplayed that this seat can't see in its own hand. */
function topOutstanding(seat,st){
  const t=st.trump, mine=new Set(st.hands[seat].map(key));
  let best=0;
  for(const c of freshDeck()) if(isTrump(c,t) && !st.seen.has(key(c)) && !mine.has(key(c))){
    const p=power(c,t,t); if(p>best) best=p; }
  return best;
}

function heur2(seat,st,legal,rnd){
  const t=st.trump, trick=st.trick, partner=(seat+2)%4;
  const trumps=legal.filter(c=>isTrump(c,t));
  const top=topOutstanding(seat,st);
  const sure=c=>isTrump(c,t) && power(c,t,t)>top;           // can't be beaten by anything unseen
  if(trick.length===0){
    const force = st.tn===1;
    const boss=trumps.filter(sure);
    if(boss.length) return maxBy(boss,c=>points(c,t)*-1+power(c,t,t)*0.01+ (c.rank===13?100:0)); // cash King if it's boss, else top boss
    if(force && trumps.length){
      const noPts=trumps.filter(c=>points(c,t)<=1);
      if(noPts.length) return maxBy(noPts,c=>power(c,t,t));
      return minBy(trumps,c=>points(c,t)*10+power(c,t,t));
    }
    const nonT=legal.filter(c=>!isTrump(c,t));
    if(nonT.length) return maxBy(nonT,c=>c.rank);          // lead a high side card, keep trumps
    const noPts=trumps.filter(c=>points(c,t)<=1);
    return noPts.length? maxBy(noPts,c=>power(c,t,t)) : minBy(trumps,c=>points(c,t)*10+power(c,t,t));
  }
  const led=effSuit(trick[0].card,t), win=currentWinner(trick,t);
  const onTable=trick.reduce((a,e)=>a+points(e.card,t),0);
  const lastToPlay = (()=>{ let n=0; for(let k=1;k<4;k++){ const s=(seat+k)%4; if(s===st.leader) break;
      if(!st.folded[s] && st.hands[s].length && !trick.some(e=>e.seat===s)) n++; } return n===0; })();
  const partnerWinning = win.seat===partner;
  const partnerSafe = partnerWinning && (lastToPlay || (isTrump(win.card,t) && power(win.card,t,t)>top));
  if(partnerSafe){                                          // feed the partner your biggest point card
    const pts=legal.filter(c=>points(c,t)>0 && c.rank!==2);
    if(pts.length) return maxBy(pts,c=>points(c,t));
  }
  const winners=legal.filter(c=>power(c,t,led)>power(win.card,t,led));
  if(winners.length && !partnerWinning){
    const secure=winners.filter(c=>lastToPlay || sure(c));
    const pool=secure.length?secure:winners;
    if(onTable>0 || secure.length){
      // win as cheaply as possible, but grab points with it if the card is safe
      if(secure.length) return maxBy(secure,c=>points(c,t)*100 - power(c,t,led));
      const noK=pool.filter(c=>c.rank!==13); return minBy(noK.length?noK:pool,c=>power(c,t,led)+points(c,t)*3);
    }
  }
  // can't / shouldn't win: throw the least valuable card, never a point card if avoidable
  const zero=legal.filter(c=>points(c,t)===0);
  if(zero.length) return minBy(zero,c=>(isTrump(c,t)?100:0)+power(c,t,led));
  const two=legal.find(c=>c.rank===2&&isTrump(c,t)); if(two) return two;   // the 2 scores for its owner anyway
  return minBy(legal,c=>points(c,t)*10+power(c,t,led));
}
module.exports={heur2,topOutstanding};
