const fs=require('fs');
const p='C:\\Users\\류이\\AppData\\Local\\Packages\\Claude_pzs8sxrjxfjjc\\LocalCache\\Roaming\\Claude\\local-agent-mode-sessions\\3baf684f-0e1d-4a35-ac56-660aadc2e94b\\d057d5fd-b919-41d5-b1dd-4094c51b73ba\\local_6c1238af-cb09-46f6-ab1e-c52f2d70a14e\\audit.jsonl';
const lines=fs.readFileSync(p,'utf8').split('\n');
lines.forEach((l,i)=>{
  const m=l.indexOf('이름좀 만들어');
  if(m>=0) console.log('>>> 유저요청 line',i);
});
// 이름 후보 응답 탐색: "상호" 또는 "후보" + 콜레이더 관련
lines.forEach((l,i)=>{
  if(/후보/.test(l) && /(상호|사명|네이밍|이름)/.test(l) && l.includes('"type":"assistant"')) console.log('cand line',i);
});
