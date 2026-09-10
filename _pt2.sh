for U in \
 "https://apis.data.go.kr/B551177/StatusOfTaxi" \
 "https://apis.data.go.kr/B551177/StatusOfTaxi/getTaxiStatus" \
 "https://apis.data.go.kr/" \
 "https://api.data.go.kr/" \
 "https://www.data.go.kr/"
do
  printf '%-58s ' "$U"
  curl -sS -m 12 -o /dev/null -w 'http=%{http_code} tls=%{time_appconnect} t=%{time_total} ip=%{remote_ip}\n' "$U" 2>&1 | tail -1
done
