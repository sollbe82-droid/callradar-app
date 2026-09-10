echo "== apis.data.go.kr (KT phone) =="
S=$(date +%s)
curl -sS -m 20 -o /dev/null -w 'http=%{http_code} tls=%{time_appconnect} total=%{time_total} ip=%{remote_ip}\n' https://apis.data.go.kr/B551177/StatusOfTaxi/getTaxiStatus 2>&1
echo "elapsed=$(( $(date +%s) - S ))s"
echo "== www.data.go.kr =="
curl -sS -m 20 -o /dev/null -w 'http=%{http_code} total=%{time_total} ip=%{remote_ip}\n' https://www.data.go.kr/ 2>&1
echo "== control: onrender =="
curl -sS -m 20 -o /dev/null -w 'http=%{http_code} total=%{time_total}\n' https://callradar-server.onrender.com/health 2>&1
