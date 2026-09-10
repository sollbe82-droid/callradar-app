D=$(date +%Y%m%d)
curl -sS -m 25 -X POST "https://www.airport.kr/arr/ap_ko/getArrPasSchList.do" \
 -H 'Content-Type: application/x-www-form-urlencoded; charset=UTF-8' \
 -H 'X-Requested-With: XMLHttpRequest' \
 -H 'Referer: https://www.airport.kr/ap_ko/872/subview.do' -A 'Mozilla/5.0' \
 --data "intg=&keyWord=&curDate=$D&startTime=0800&endTime=1159&airPort=&todayDate=$D&todayTime=0800&curStime=0800&curEtime=1159&layout=61705f6b6f40403837324040666e637432&siteId=ap_ko&langSe=ko&airplane=" \
 -o /data/local/tmp/a8.json
echo "size=$(wc -c < /data/local/tmp/a8.json)"
