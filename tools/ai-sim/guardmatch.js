"use strict";
// Duplicate match: look-ahead play WITH counter-safety rules vs WITHOUT.
// Usage: node guardmatch.js [hands=300] [samples=20] [rules=under,swap,lead,low]
const E=require('./engine.js'); const {makeSmartPlay}=require('./smart.js');
const {heur2}=require('./heur2.js'); const {counterGuard}=require('./guard.js'); const {duplicate}=require('./match.js');
const N=+process.argv[2]||300, samples=+process.argv[3]||20;
const rules=Object.fromEntries((process.argv[4]||'under,swap,lead,low').split(',').map(k=>[k,1]));
const rollout=(s,st,lg,rnd)=> rnd()<0.5 ? heur2(s,st,lg,rnd) : E.oldPlay(s,st,lg,rnd);
const smart=makeSmartPlay({samples,rollout});
let changed=0;
const guarded=(s,st,lg,rnd)=>{ const c=smart(s,st,lg,rnd), g=counterGuard(s,st,lg,c,rules); if(g!==c) changed++; return g; };
const A={play:guarded,pass:E.oldPass}, B={play:smart,pass:E.oldPass};
console.log(`[${Object.keys(rules)}] ${N} deals x2, ${samples} samples:`, duplicate(A,B,N,7), `changed ${changed} plays`);
