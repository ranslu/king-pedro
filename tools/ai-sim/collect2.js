// Rows for the bid model, with SMART play at every seat. usage: node collect2.js N seed out.json
const fs=require('fs'), E=require('./engine.js'), S=require('./smart.js'), {heur2}=require('./heur2.js');
const mix=(s,st,lg,r)=> r()<0.5? E.oldPlay(s,st,lg,r) : heur2(s,st,lg,r);
const play=S.makeSmartPlay({samples:30,rollout:mix});
const N=+process.argv[2], seed=+process.argv[3], rows=[];
for(let i=0;i<N;i++){
  const rnd=E.makeRng(seed*7777+i*13+1); const deck=E.shuffle(E.freshDeck(),rnd); const dealer=i%4;
  const decl=Math.floor(rnd()*4), suit=E.SUITS[Math.floor(rnd()*4)];
  const hands=[[],[],[],[]]; let idx=0;
  for(let r=0;r<3;r++) for(let o=1;o<=4;o++){ const s=(dealer+o)%4; for(let k=0;k<3;k++) hands[s].push(deck[idx++]); }
  const cards = decl===dealer ? hands[decl].concat([deck[51]]) : hands[decl];
  const T=cards.filter(c=>E.isTrump(c,suit)), has=r=>T.some(c=>c.rank===r&&c.suit===suit)?1:0;
  const f={A:has(14),K:has(13),Q:has(12),J:has(11),T10:has(10),N9:has(9),P5:has(5),O5:T.some(c=>c.rank===5&&c.suit!==suit)?1:0,D2:has(2),
    low:T.filter(c=>c.suit===suit&&[3,4,6,7,8].includes(c.rank)).length, dealer:decl===dealer?1:0};
  const r=E.playDealt({dealer,deck,declarer:decl,trump:suit,bid:36,rnd,play:[play,play,play,play],pass:[0,1,2,3].map(()=>E.oldPass)});
  rows.push({...f,pts:r.handPts[decl%2]}); if(i%1000===999) console.log(i+1);
}
fs.writeFileSync(process.argv[4],JSON.stringify(rows)); console.log('done',N);
