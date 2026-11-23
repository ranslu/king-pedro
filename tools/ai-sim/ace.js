"use strict";
// "Show your partner the Ace of trump" rules, run after the look-ahead picks a card.
//   answer - partner led a trump: play the Ace of trump on it (Randy's partnership signal)
//   lead   - you are leading a trump: lead the Ace rather than a lower trump
//   show   - you are about to play ANY trump (even following an off-suit lead): play the Ace instead,
//            so partner learns where the Ace is early (reports R1,R2,R4,R5)
//   decl   - apply the rules only when your partner is the declarer
const {isTrump}=require('./engine.js');
function aceRule(seat,st,legal,card,rules){
  const t=st.trump, partner=(seat+2)%4;
  const ace=legal.find(c=>c.suit===t && c.rank===14);
  if(!ace || card===ace) return card;
  if(rules.decl && st.declarer!==partner) return card;
  const trick=st.trick.filter(e=>!e.buried);
  if(rules.answer && trick.length && trick[0].seat===partner && isTrump(trick[0].card,t)) return ace;
  if(rules.lead && !trick.length && isTrump(card,t)) return ace;
  if(rules.show && isTrump(card,t)) return ace;
  return card;
}
module.exports={aceRule};
