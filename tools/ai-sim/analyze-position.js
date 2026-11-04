"use strict";
// Deep check of one recorded play: rebuilds the position from a backed-up game
// (data/archive/games/<id>.json) and scores every legal card over many imagined layouts
// of the hidden cards (public information only), using the same play-outs as the table AI.
// Usage: node analyze-position.js <game.json> <hand#> <trick#> <seat> [samples=2000]
const fs=require('fs'); const E=require('./engine.js');
const {sampleWorld,utility}=require('./smart.js'); const {heur2}=require('./heur2.js');
const {key,legalPlays,playFrom,cloneState,points}=E;
const [file,hn,tk,seatArg,smp]=process.argv.slice(2);
const g=JSON.parse(fs.readFileSync(file)); const seat=+seatArg, samples=+smp||2000;
const ev=g.hands[+hn-1].events;
let st=null, tn=0, target=null;
for(const e of ev){
  if(e.type==="trump") st={trump:e.trump,declarer:e.seat,bid:e.bid,bidTeam:e.seat%2,aceGone:false,handPts:[0,0],
    seen:new Set(),knownIn:[new Set(),new Set(),new Set(),new Set()],folded:[false,false,false,false]};
  if(!st) continue;
  if(e.type==="passt") for(const c of e.cards) st.knownIn[e.partner].add(key(c));
  if(e.type==="settle"){ st.hands=e.hands.map(h=>h.slice()); st.folded=(e.folded||st.folded).slice(); }
  if(e.type==="trickStart"){ tn=e.num; st.tn=tn; st.leader=e.leader; st.trick=[]; }
  if(e.type==="surrender"){ for(const c of e.cards) st.seen.add(key(c)); st.folded[e.seat]=true; st.hands[e.seat]=[]; }
  if(e.type==="play"){
    if(tn===+tk && e.seat===seat && !e.buried){ target=e.card; break; }
    const h=st.hands[e.seat]; const i=h.findIndex(c=>key(c)===key(e.card)); if(i>=0) h.splice(i,1);
    st.trick.push({seat:e.seat,card:e.card,buried:e.buried}); st.seen.add(key(e.card)); st.knownIn[e.seat].delete(key(e.card));
    if(E.isTrump(e.card,st.trump)&&e.card.rank===14) st.aceGone=true;
  }
  if(e.type==="trickEnd") st.handPts=e.captured.slice();
}
if(!target) throw new Error("play not found");
st.step=(seat-st.leader+4)%4;
const legal=legalPlays(st.hands[seat],st.trick,st.trump);
const rnd=E.makeRng(12345);
const rollout=(s,ws,lg)=> rnd()<0.5 ? heur2(s,ws,lg,rnd) : E.oldPlay(s,ws,lg,rnd);
const tot=legal.map(()=>0), sq=legal.map(()=>0);
for(let k=0;k<samples;k++){
  const world=sampleWorld(seat,st,rnd);
  legal.forEach((first,i)=>{
    const w=cloneState(world);
    const pts=playFrom(w,(s,ws,lg)=> s===seat && ws.tn===st.tn && ws.step===st.step ? lg.find(c=>key(c)===key(first)) : rollout(s,ws,lg));
    const u=utility(seat%2,pts,st); tot[i]+=u; sq[i]+=u*u;
  });
}
const S={2:"2",3:"3",4:"4",5:"5",6:"6",7:"7",8:"8",9:"9",10:"10",11:"J",12:"Q",13:"K",14:"A"},SS={hearts:"♥",diamonds:"♦",clubs:"♣",spades:"♠"};
const rows=legal.map((c,i)=>({card:S[c.rank]+SS[c.suit],avg:tot[i]/samples,played:key(c)===key(target)})).sort((a,b)=>b.avg-a.avg);
console.log(`${g.players[seat]} hand ${hn} trick ${tk} (trump ${st.trump}, ${samples} layouts):`);
for(const r of rows) console.log(`  ${r.card.padEnd(4)} ${r.avg.toFixed(1).padStart(6)}${r.played?"   <- played":""}`);
