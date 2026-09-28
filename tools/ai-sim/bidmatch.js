// Duplicate match where each TEAM bids with its own bid code (smart card play everywhere).
const fs=require('fs'), E=require('./engine.js'), S=require('./smart.js'), {heur2}=require('./heur2.js');
const load=(f,conf)=>new Function('isTrump','SUITS','PLAYERS','RULES',fs.readFileSync(f,'utf8').replace(/const BID_CONFIDENCE = \[.*\];/,`const BID_CONFIDENCE = [${conf}];`)+'\nreturn {safeBid,aiBid};')(E.isTrump,E.SUITS,E.PLAYERS,E.RULES);
const mix=(s,st,lg,r)=> r()<0.5? E.oldPlay(s,st,lg,r) : heur2(s,st,lg,r);
const play=S.makeSmartPlay({samples:30,rollout:mix});
function auction(deck,dealer,bidders){ const hands=[[],[],[],[]]; let idx=0;
  for(let r=0;r<3;r++) for(let o=1;o<=4;o++){ const s=(dealer+o)%4; for(let k=0;k<3;k++) hands[s].push(deck[idx++]); }
  let hb=0,dc=-1; const passed=[0,0,0,0]; let turn=(dealer+1)%4,acts=0;
  const lim=[0,1,2,3].map(s=>bidders[s].safeBid(s,hands[s],s===dealer?deck[51]:null,s===dealer));
  while(passed.filter(Boolean).length<3&&hb<62&&acts<80){ if(!passed[turn]&&turn!==dc){ const need=Math.max(30,hb+1);
    const b=bidders[turn].aiBid(turn,lim[turn],need,dc); if(b){hb=b;dc=turn;} else passed[turn]=1; } turn=(turn+1)%4; acts++; }
  if(dc<0){dc=dealer;hb=30;} return {declarer:dc,bid:hb,trump:lim[dc].suit}; }
const [,, fa, ca, fb, cb, N, seed]=process.argv;
const A=load(fa,ca), B=load(fb,cb);
let edge=0,sq=0; const made={A:[0,0],B:[0,0]};
for(let i=0;i<+N;i++){ const deck=E.shuffle(E.freshDeck(),E.makeRng(+seed*99991+i*17+3)); const dealer=i%4; let he=0;
  for(const aTeam of [0,1]){ const bidders=[0,1,2,3].map(s=>s%2===aTeam?A:B); const c=auction(deck,dealer,bidders);
    const r=E.playDealt({dealer,deck,declarer:c.declarer,trump:c.trump,bid:c.bid,rnd:E.makeRng(i*7+aTeam),play:[play,play,play,play],pass:[0,1,2,3].map(()=>E.oldPass)});
    he+=(r.delta[aTeam]-r.delta[1-aTeam])/2; const who=r.bidTeam===aTeam?'A':'B'; made[who][1]++; if(r.made) made[who][0]++; }
  edge+=he; sq+=he*he; }
const m=edge/N, sd=Math.sqrt(sq/N-m*m);
console.log(`A(${fa} conf ${ca}) vs B(${fb} conf ${cb}): edge ${m.toFixed(2)} ± ${(sd/Math.sqrt(N)).toFixed(2)} per hand | A made ${(made.A[0]/made.A[1]*100).toFixed(0)}% of ${made.A[1]} | B made ${(made.B[0]/made.B[1]*100).toFixed(0)}% of ${made.B[1]}`);
