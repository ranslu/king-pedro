"use strict";
// Duplicate match: look-ahead play WITH an Ace-of-trump rule vs WITHOUT.
// Usage: node acematch.js [hands=1500] [samples=20] [rules=answer,lead,decl]
const E=require('./engine.js'); const {makeSmartPlay}=require('./smart.js');
const {heur2}=require('./heur2.js'); const {aceRule}=require('./ace.js'); const {duplicate}=require('./match.js');
const N=+process.argv[2]||1500, samples=+process.argv[3]||20;
const rules=Object.fromEntries((process.argv[4]||'answer').split(',').map(k=>[k,1]));
const rollout=(s,st,lg,rnd)=> rnd()<0.5 ? heur2(s,st,lg,rnd) : E.oldPlay(s,st,lg,rnd);
const smart=makeSmartPlay({samples,rollout});
let changed=0;
const withRule=(s,st,lg,rnd)=>{ const c=smart(s,st,lg,rnd), g=aceRule(s,st,lg,c,rules); if(g!==c) changed++; return g; };
console.log(`[${Object.keys(rules)}] ${N} deals x2:`, duplicate({play:withRule,pass:E.oldPass},{play:smart,pass:E.oldPass},N,11), `changed ${changed} plays`);
