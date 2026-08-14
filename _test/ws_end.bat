@echo off
node -e "fetch('https://callradar-server.onrender.com/api/work-session',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({user_id:1,work_start:0,paused_total:0,pause_start:0,start_fare:0})}).then(r=>r.text()).then(t=>console.log(t)).catch(e=>console.log('ERR '+e))" > C:\CallRadar\_test\ws.log 2>&1
