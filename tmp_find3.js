const fs=require('fs');
const p='C:\\Users\\류이\\AppData\\Local\\Packages\\Claude_pzs8sxrjxfjjc\\LocalCache\\Roaming\\Claude\\local-agent-mode-sessions\\3baf684f-0e1d-4a35-ac56-660aadc2e94b\\d057d5fd-b919-41d5-b1dd-4094c51b73ba\\local_6c1238af-cb09-46f6-ab1e-c52f2d70a14e\\audit.jsonl';
const lines=fs.readFileSync(p,'utf8').split('\n');
for(const i of [20683,20701,20728]){
  const l=lines[i]||'';
  try{
    const o=JSON.parse(l);
    const c=o?.message?.content;
    if(Array.isArray(c)) c.forEach(b=>{ if(b.type==='text') console.log('=== line',i,'===\n'+b.text.slice(0,2500)); });
    else if(o.result) console.log('=== line',i,'(result) ===\n'+String(o.result).slice(0,2500));
  }catch(e){ console.log('line',i,'parse fail'); }
}
