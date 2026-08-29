import json,sys,os,re
SKIP={'Read','Glob','Grep','TaskCreate','TaskUpdate','ToolSearch'}   # 읽기·잡무는 내용이 없다
def txt(c):
    if isinstance(c,str): return c
    if not isinstance(c,list): return ''
    out=[]
    for b in c:
        if not isinstance(b,dict): continue
        t=b.get('type')
        if t=='text': out.append(b.get('text',''))
        elif t=='tool_use':
            n=b.get('name','')
            if n in SKIP: continue
            inp=json.dumps(b.get('input',{}),ensure_ascii=False)
            # 쓴 것·실행한 것은 내용이 자산이다. 앞부분만 남긴다.
            out.append(f"[{n}] {inp[:1200]}")
        elif t=='tool_result':
            c2=b.get('content')
            s=c2 if isinstance(c2,str) else json.dumps(c2,ensure_ascii=False)
            if s and len(s.strip())>20: out.append(f"[결과] {s[:400]}")
    return '\n'.join(x for x in out if x)
def run(path,out):
    n=wr=0
    with open(out,'w',encoding='utf-8') as w:
        w.write(f"# 대화 원문 추출 — {os.path.basename(path)}\n")
        for line in open(path,encoding='utf-8',errors='ignore'):
            n+=1
            try: d=json.loads(line)
            except: continue
            m=d.get('message') or {}
            role=m.get('role') or d.get('type')
            if role not in ('user','assistant'): continue
            t=txt(m.get('content'))
            t=re.sub(r'<system-reminder>.*?</system-reminder>','',t,flags=re.S).strip()
            if not t: continue
            ts=(d.get('timestamp') or '')[:16].replace('T',' ')
            w.write(f"\n## [{role}] {ts}\n{t[:9000]}\n"); wr+=1
    return n,wr,os.path.getsize(out)
n,wr,sz=run(sys.argv[1],sys.argv[2])
print(f"  {os.path.basename(sys.argv[1])[:14]}: {n:,}줄 → {wr:,}개 → {sz/1048576:.2f} MB")
