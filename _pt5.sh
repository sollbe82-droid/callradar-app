D=$(date +%Y%m%d)
for H in 08 09 10; do
  R=$(curl -sS -m 20 -X POST "https://www.airport.kr/arr/ap_ko/getArrPasSchList.do" \
   -H 'Content-Type: application/x-www-form-urlencoded; charset=UTF-8' \
   -H 'X-Requested-With: XMLHttpRequest' \
   -H 'Referer: https://www.airport.kr/ap_ko/872/subview.do' -A 'Mozilla/5.0' \
   --data "intg=&keyWord=&curDate=$D&startTime=${H}00&endTime=${H}59&airPort=&todayDate=$D&todayTime=${H}00&curStime=${H}00&curEtime=${H}59&layout=61705f6b6f40403837324040666e637432&siteId=ap_ko&langSe=ko&airplane=")
  N=$(echo "$R" | grep -o '"fnumber"' | wc -l)
  echo "${H}시대 도착편(공항공사): $N 편"
done
