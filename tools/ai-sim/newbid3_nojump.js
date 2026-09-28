// ---- NEW BIDDING MODEL (data-fitted from 300k simulated hands) ----
const BID_MODEL = {
  // weights: [base, A, Q, J, 10, 9, Pedro5, off5, 2, each low trump(3,4,6,7,8), dealer]
  // refit on 18,000 hands played with the look-ahead card play
  K0:{w:[22.29, 7.4, 2.2, 2.21, 2.5, 2.57, 1.4, 1.34, 1.88, 0.96, -1.29],
      q:{"0.15":-20.7,"0.20":-18.1,"0.25":-16.1,"0.30":-14.2,"0.35":-12.3,"0.40":-10.4,"0.45":-8,"0.50":-5.1}},
  K1:{w:[39.21, 9.01, 3.02, 3.17, 3.38, 3.62, 0.85, 0.66, 3.29, 2.1, -2.2],
      q:{"0.15":-9.8,"0.20":-7.3,"0.25":-5.4,"0.30":-3.9,"0.35":-2.3,"0.40":-1.2,"0.45":0.5,"0.50":1.6}}
};
// how sure each character wants to be before bidding (probability of making it)
const BID_CONFIDENCE = [0.55, 0.70, 0.62, 0.50];   // Mike, Elena, Bohdan, Lou
function bidFeatures(cards,s,isDealer){
  const T=cards.filter(c=>isTrump(c,s)), has=r=>T.some(c=>c.rank===r&&c.suit===s)?1:0;
  return {K:has(13), x:[1,has(14),has(12),has(11),has(10),has(9),has(5),
    T.some(c=>c.rank===5&&c.suit!==s)?1:0, has(2),
    T.filter(c=>c.suit===s&&[3,4,6,7,8].includes(c.rank)).length, isDealer?1:0]};
}
function expectedPts(cards,s,isDealer){ const f=bidFeatures(cards,s,isDealer), m=BID_MODEL["K"+f.K];
  return {mean:f.x.reduce((a,v,i)=>a+v*m.w[i],0), K:f.K}; }
/** Highest bid this seat is willing to make, and in which suit. */
function safeBid(seat,hand,extra,isDealer){
  const cards=extra?hand.concat([extra]):hand;
  const p=PLAYERS[seat]; let conf=BID_CONFIDENCE[seat] ?? 0.72;
  if(p.chaos>0) conf -= (Math.random()*p.chaos)/100;            // gamblers get braver on a whim
  const risk=Math.min(0.50,Math.max(0.15,Math.round((1-conf)*20)/20)).toFixed(2);
  let best={suit:"hearts",bid:0,mean:-1};
  for(const s of SUITS){ const e=expectedPts(cards,s,isDealer);
    const b=Math.floor(e.mean+BID_MODEL["K"+e.K].q[risk]);
    if(b>best.bid || (b===best.bid && e.mean>best.mean)) best={suit:s,bid:b,mean:e.mean}; }
  return best;
}
// How far each character jumps past the minimum when they bid (0 = minimum raise, 1 = straight to their limit)
const BID_JUMP = [0,0,0,0];   // Lou (-1) = random every time
/** AI bid decision: returns the bid to make, or 0 to pass. */
function aiBid(seat,limit,need,declarer){
  if(limit.bid<need) return 0;
  // don't bid over your own partner unless you're clearly stronger
  if(declarer>=0 && declarer%2===seat%2 && limit.bid < need+8) return 0;
  let j=BID_JUMP[seat] ?? 0.3; if(j<0) j=Math.random();
  if(declarer>=0) j=Math.max(j,0.5);        // when overcalling an opponent, jump to cut the war short
  return Math.min(RULES.maxBid, need + Math.floor((limit.bid-need)*j));
}
