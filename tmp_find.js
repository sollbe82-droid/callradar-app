const fs=require('fs');
const p='C:\\Users\\류이\\AppData\\Local\\Packages\\Claude_pzs8sxrjxfjjc\\LocalCache\\Roaming\\Claude\\local-agent-mode-sessions\\3baf684f-0e1d-4a35-ac56-660aadc2e94b\\d057d5fd-b919-41d5-b1dd-4094c51b73ba\\local_6c1238af-cb09-46f6-ab1e-c52f2d70a14e\\audit.jsonl';
const lines=fs.readFileSync(p,'utf8').split('\n');
const hits=[];
lines.forEach((l,i)=>{ if(/주식회사/.test(l)) hits.push(i); });
console.log('hit lines:',hits.join(','));
for(const i of hits.slice(0,6)){
  const l=lines[i];
  // 주식회사 주변 텍스트만 추출
  let idx=0;
  while((idx=l.indexOf('주식회사',idx))>=0){
    console.log('--- line',i,'---');
    console.log(l.slice(Math.max(0,idx-500), idx+500).replace(/\\n/g,'\n'));
    idx+=4;
    break;
  }
}
