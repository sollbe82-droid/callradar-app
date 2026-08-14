@echo off
node -e "fetch('https://callradar-server.onrender.com/api/work-session/1').then(r=>r.text()).then(t=>console.log(t)).catch(e=>console.log('ERR '+e))" > C:\CallRadar\_test\ws.log 2>&1
