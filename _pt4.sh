D=$(date +%Y%m%d); H=$(date +%H)
S="${H}00"; E="${H}59"
curl -sS -m 20 -X POST "https://www.airport.kr/arr/ap_ko/getArrPasSchList.do" \
 -H 'Content-Type: application/x-www-form-urlencoded; charset=UTF-8' \
 -H 'X-Requested-With: XMLHttpRequest' \
 -H 'Referer: https://www.airport.kr/ap_ko/872/subview.do' \
 -A 'Mozilla/5.0' \
 --data "intg=&keyWord=&curDate=$D&startTime=$S&endTime=$E&airPort=&todayDate=$D&todayTime=${H}00&curStime=$S&curEtime=$E&layout=61705f6b6f40403837324040666e637432&siteId=ap_ko&langSe=ko&airplane=" \
 -o /data/local/tmp/arr2.json -w 'http=%{http_code} size=%{size_download} t=%{time_total}\n'
