import numpy as np, wave, struct
SR=44100
def sec(n): return int(SR*n)

def env(n, a, r):
    e=np.ones(n); ai=sec(a); ri=sec(r)
    if ai>0: e[:ai]=np.linspace(0,1,ai)
    if ri>0: e[-ri:]=np.linspace(1,0,ri)
    return e

def pad(freqs, dur, amp=0.09, detune=0.4):
    n=sec(dur); t=np.arange(n)/SR; out=np.zeros(n)
    for i,f in enumerate(freqs):
        lfo=1+0.004*np.sin(2*np.pi*(0.07+0.013*i)*t)          # slow drift
        trem=0.85+0.15*np.sin(2*np.pi*(0.11+0.02*i)*t)        # slow swell
        out+=np.sin(2*np.pi*f*lfo*t)*trem
        out+=0.5*np.sin(2*np.pi*(f+detune)*lfo*t)*trem        # detuned twin
    out/= (len(freqs)*1.5)
    return out*amp*env(n,3.0,3.0)

def blip(f=880, dur=0.5, amp=0.05):
    n=sec(dur); t=np.arange(n)/SR
    e=np.exp(-t*9)
    return (np.sin(2*np.pi*f*t)*0.7 + np.sin(2*np.pi*f*2*t)*0.2)*e*amp

def thump(dur=0.5, amp=0.10):
    n=sec(dur); t=np.arange(n)/SR
    f=np.linspace(85,42,n)
    return np.sin(2*np.pi*np.cumsum(f)/SR)*np.exp(-t*7)*amp

TOT=100.0
buf=np.zeros(sec(TOT))
# chords: Am -> F -> Am -> G  (25s each)
CH=[[110.00,164.81,220.00,261.63],
    [ 87.31,130.81,174.61,220.00],
    [110.00,164.81,220.00,261.63],
    [ 98.00,146.83,196.00,246.94]]
for i,c in enumerate(CH):
    seg=pad(c,26.0)
    st=sec(i*25.0)
    buf[st:st+len(seg)]+=seg[:len(buf)-st]
# heartbeat every 2s, radar blip every 4s (offset)
for k in range(int(TOT/2)):
    st=sec(k*2.0); s=thump()
    buf[st:st+len(s)]+=s[:len(buf)-st]
for k in range(int(TOT/4)):
    st=sec(1.0+k*4.0); s=blip(880 if k%2==0 else 1174)
    buf[st:st+len(s)]+=s[:len(buf)-st]

buf*=env(len(buf),1.5,4.0)
buf=np.tanh(buf*1.4)*0.85
peak=np.max(np.abs(buf)); buf=buf/peak*0.72
pcm=(buf*32767).astype('<i2')
st=np.repeat(pcm[:,None],2,axis=1).tobytes()
w=wave.open('bgm_brief.wav','wb'); w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
w.writeframes(st); w.close()
print("ok", len(buf)/SR, "sec")
