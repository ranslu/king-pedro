// Refit the bid model on hands played with SMART card play.
const fs=require('fs');
const all=[...require('./rowsA.json'),...require('./rowsB.json')];
const F=['A','Q','J','T10','N9','P5','O5','D2','low','dealer'];
const X=r=>[1,...F.map(f=>r[f])];
function ols(rows){ const p=F.length+1, A=Array.from({length:p},()=>Array(p+1).fill(0));
  for(const r of rows){ const x=X(r); for(let a=0;a<p;a++){ for(let b=0;b<p;b++) A[a][b]+=x[a]*x[b]; A[a][p]+=x[a]*r.pts; } }
  for(let c=0;c<p;c++){ let m=c; for(let r=c+1;r<p;r++) if(Math.abs(A[r][c])>Math.abs(A[m][c])) m=r; [A[c],A[m]]=[A[m],A[c]];
    for(let r=0;r<p;r++) if(r!==c){ const f=A[r][c]/A[c][c]; for(let k=c;k<=p;k++) A[r][k]-=f*A[c][k]; } }
  return A.map((row,i)=>+(row[p]/row[i]).toFixed(2)); }
const cut=Math.floor(all.length*0.8), train=all.slice(0,cut), test=all.slice(cut);
const model={};
for(const k of [0,1]){
  const rows=train.filter(r=>r.K===k), w=ols(rows);
  const res=rows.map(r=>r.pts-X(r).reduce((a,x,j)=>a+x*w[j],0)).sort((a,b)=>a-b);
  const q={}; for(let p=0.15;p<=0.5001;p+=0.05) q[p.toFixed(2)]=+res[Math.floor(p*res.length)].toFixed(1);
  model['K'+k]={w,q};
}
console.log('rows',all.length,'avg pts',(all.reduce((a,r)=>a+r.pts,0)/all.length).toFixed(1));
const mean=r=>{const m=model['K'+r.K]; return X(r).reduce((a,x,j)=>a+x*m.w[j],0);};
for(const k of [0,1]) console.log('K'+k, JSON.stringify(model['K'+k]));
// calibration: bid exactly at the safe level for a target confidence -> how often is it made?
for(const t of [0.55,0.62,0.68,0.75,0.8]){ const qk=(Math.round((1-t)*20)/20).toFixed(2);
  let n=0,m=0,sum=0; for(const r of test){ const b=Math.floor(mean(r)+model['K'+r.K].q[qk]); if(b>=30){ n++; sum+=b; if(r.pts>=b) m++; } }
  console.log('target',t,'-> bids on',(n/test.length*100).toFixed(1)+'% of hands, made',(m/n*100).toFixed(1)+'%, avg bid',(sum/n).toFixed(1)); }
fs.writeFileSync('model3.json',JSON.stringify(model));
