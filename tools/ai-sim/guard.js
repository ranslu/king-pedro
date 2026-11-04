"use strict";
// Counter-safety guard (same logic as counterGuard() in king-pedro-table.html).
// Runs after the look-ahead picks a card. A "counter" is a trump worth 5+ (5s, 9, 10, K).
// Each rule can be switched on separately so it can be measured on its own:
//   under  - following: don't throw a counter under an opponent who is winning the trick
//   swap   - following: a counter that could still be beaten -> a safe card that surely wins
//   lead   - leading: a counter that could be beaten -> a safe card that surely wins
//   low    - leading: a counter that could be beaten -> the lowest non-counter trump
const E=require('./engine.js');
const {isTrump,points,power,effSuit,currentWinner,minBy}=E;
const {topOutstanding}=require('./heur2.js');

function counterGuard(seat,st,legal,card,rules={under:1,swap:1,lead:1,low:1}){
  const t=st.trump, trick=st.trick, isCounter=c=>points(c,t)>=5;
  if(!isCounter(card) || legal.length<2) return card;
  const top=topOutstanding(seat,st), partner=(seat+2)%4;
  let others=0;
  for(let k=1;k<4;k++){ const s=(seat+k)%4; if(s===st.leader) break;
    if(!st.folded[s] && st.hands[s].length && !trick.some(e=>e.seat===s)) others++; }
  const last=others===0;
  const led=trick.length? effSuit(trick[0].card,t) : t;
  const win=trick.length? currentWinner(trick,t) : null;
  const beats=c=>!win || power(c,t,led)>power(win.card,t,led);
  const sure=c=>beats(c) && (last || (isTrump(c,t) && power(c,t,t)>top));
  if(sure(card)) return card;
  const safe=legal.filter(c=>!isCounter(c));
  if(!safe.length) return card;
  const sureSafe=safe.filter(sure);
  if(win){
    if(win.seat%2===seat%2) return card;                       // own side holds the trick: leave it
    if(rules.under && !beats(card)){                            // throwing points under an opponent
      const b=safe.filter(beats);
      return b.length ? minBy(b,c=>power(c,t,led)) : minBy(safe,c=>points(c,t)*100+power(c,t,led));
    }
    if(rules.swap && sureSafe.length) return minBy(sureSafe,c=>power(c,t,led));
    return card;
  }
  if(rules.lead && sureSafe.length) return minBy(sureSafe,c=>power(c,t,t));
  if(rules.low){ const lowT=safe.filter(c=>isTrump(c,t)); if(lowT.length) return minBy(lowT,c=>power(c,t,t)); }
  return card;
}
module.exports={counterGuard};
