"use strict";
// Duplicate match: every deal is played twice with the SAME cards and contract —
// once with policy A on team 0 (Mike/Bohdan), once with A on team 1 (Elena/Lou).
// Luck cancels out; what's left is the skill difference.
const fs=require('fs'); const E=require('./engine.js');
const nb=new Function('isTrump','SUITS','PLAYERS','RULES',
  fs.readFileSync(__dirname+'/newbid3_nojump.js','utf8')+'\nreturn {safeBid,aiBid};')(E.isTrump,E.SUITS,E.PLAYERS,E.RULES);

function contractFor(deck,dealer){
  const hands=[[],[],[],[]]; let idx=0;
  for(let r=0;r<3;r++) for(let o=1;o<=4;o++){ const s=(dealer+o)%4; for(let k=0;k<3;k++) hands[s].push(deck[idx++]); }
  const peeked=deck[51];
  let highBid=0,declarer=-1; const passed=[0,0,0,0]; let turn=(dealer+1)%4,acts=0;
  const lim=[0,1,2,3].map(s=>nb.safeBid(s,hands[s],s===dealer?peeked:null,s===dealer));
  while(passed.filter(Boolean).length<3&&highBid<62&&acts<80){ if(!passed[turn]&&turn!==declarer){ const need=Math.max(30,highBid+1);
    const b=nb.aiBid(turn,lim[turn],need,declarer); if(b){highBid=b;declarer=turn;} else passed[turn]=1; } turn=(turn+1)%4; acts++; }
  if(declarer<0){declarer=dealer;highBid=30;}
  return {declarer,bid:highBid,trump:lim[declarer].suit};
}

/** A, B: {play, pass} policy objects. Returns avg per-hand game-score edge of A over B. */
function duplicate(A,B,N,seed=1){
  let edge=0, sq=0, aMade=0, aDecl=0;
  for(let i=0;i<N;i++){
    const rnd0=E.makeRng(seed*1000003+i*7919+1);
    const deck=E.shuffle(E.freshDeck(),rnd0); const dealer=i%4;
    const c=contractFor(deck,dealer);
    let handEdge=0;
    for(const aTeam of [0,1]){
      const pol=s=>(s%2===aTeam?A:B);
      const rnd=E.makeRng(seed*31+i*131+aTeam+7);
      const r=E.playDealt({dealer,deck,declarer:c.declarer,trump:c.trump,bid:c.bid,rnd,
        play:[0,1,2,3].map(s=>pol(s).play), pass:[0,1,2,3].map(s=>pol(s).pass)});
      handEdge += (r.delta[aTeam]-r.delta[1-aTeam])/2;
      if(r.bidTeam===aTeam){ aDecl++; if(r.made) aMade++; }
    }
    edge+=handEdge; sq+=handEdge*handEdge;
  }
  const mean=edge/N, sd=Math.sqrt(sq/N-mean*mean);
  return {edgePerHand:+mean.toFixed(2), stderr:+(sd/Math.sqrt(N)).toFixed(2), aMadeRate:+(aMade/aDecl*100).toFixed(1)};
}
module.exports={duplicate,contractFor};
if(require.main===module){
  const OLD={play:E.oldPlay,pass:E.oldPass};
  console.time('old vs old'); console.log('old vs old (should be ~0):', duplicate(OLD,OLD,20000)); console.timeEnd('old vs old');
}
