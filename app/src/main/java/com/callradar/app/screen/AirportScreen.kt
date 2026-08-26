// ===== AirportScreen v4c (2026-07-07) =====
// v4c: 혼잡도 탭 개편 (5분/10분→30분내 도착예정 편수+인원 / 다음도착편 카드)
//      · 서버 v4c 대응 (upcomingCount, upcomingPax, nextFlight)
// v4b: 택시 T1/T2 구분 (T1=3개통합, T2=6개)
// v4: 서버 v4 구조 대응 + 공발이식 UI 전면개편
//     · 데이터: {t1,t2} 각각 taxi(6종류)+flights+immigration 파싱
//     · 택시대기장: 서울/시계외/인천/모범/경기/대형 6종 2열 그리드 + 승차위치
//     · 도착항공편: 카드 탭하면 상세 팝업(항공사/출도착/지연/터미널·게이트·입국장/인원)
//     · 예상혼잡도: 5분후/10분후 입국 도착예정 인원
//     · 입국심사 진행인원 카드
// v3: status 파싱 t1SeoulTaxi 평면필드
package com.callradar.app.screen

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class FlightInfo(
    val flightNo: String, val origin: String, val scheduledTime: String,
    val estimatedTime: String, val terminal: String, val gate: String,
    val entryGate: String, val korean: Int, val foreigner: Int, val isDelayed: Boolean
) { val total: Int get() = korean + foreigner }

// 택시 6종류 (서버 v4 구조)
data class TaxiStatus(
    val seoul: Int, val incheon: Int, val gyeonggi: Int,
    val intercity: Int, val best: Int, val van: Int,
    val standSeoul: String, val standIncheon: String, val standGyeonggi: String,
    val standIntercity: String, val standBestVan: String
) { val total: Int get() = seoul + incheon + gyeonggi + intercity + best + van }

data class NextFlight(val flightNo: String, val origin: String, val time: String, val pax: Int)

// 시간대별 입국 예고 (인천공항 승객예고 API) — 한 시간 구간의 T1/T2 예상 입국 승객
data class HourPax(val hour: Int, val t1: Int, val t2: Int)
// [v16] 도착항공편 기반 시간대별 입국 인원(캐시 hourly) — 승객예고 API가 빌 때 폴백 소스
data class HourlyPax(val hour: Int, val pax: Int, val count: Int)

// 터미널 하나의 전체 데이터
data class TerminalData(
    val taxi: TaxiStatus,
    val flights: List<FlightInfo>,
    val immigrationTotal: Int,
    val upcomingCount: Int,
    val upcomingPax: Int,
    val nextFlight: NextFlight?,
    val hourly: List<HourlyPax> = emptyList(),
    val in30Srv: Int = -1,   // [⑥] 서버 계산 30분 내 도착 인원 (sch 폴백 포함, -1=미제공)
    val in60Srv: Int = -1
)
data class PhraseCard(
    val id: String, val korean: String, val english: String,
    val chinese: String, val japanese: String, val isCustom: Boolean = false
)

val DEFAULT_PHRASES = listOf(
    PhraseCard("1","어디 가세요?","Where are you going?","您要去哪里？","どこへ行きますか？"),
    PhraseCard("2","목적지가 어디인가요?","What is your destination?","您的目的地是哪里？","目的地はどこですか？"),
    PhraseCard("3","안전벨트를 착용해 주세요.","Please fasten your seatbelt.","请系好安全带。","シートベルトをお締めください。"),
    PhraseCard("4","잠시만 기다려 주세요.","Please wait a moment.","请稍等。","少々お待ちください。"),
    PhraseCard("5","요금은 미터기로 나옵니다.","The fare is shown on the meter.","费用由计价器显示。","料金はメーターに表示されます。"),
    PhraseCard("6","현금 또는 카드로 결제하실 수 있어요.","You can pay by cash or card.","可以用现金或刷卡支付。","現金またはカードでお支払いいただけます。"),
    PhraseCard("7","여기서 내려드릴게요.","I'll drop you off here.","我在这里让您下车。","ここで降ろします。"),
    PhraseCard("8","도착했습니다.","We have arrived.","到了。","到着しました。"),
    PhraseCard("9","트렁크 열어 드릴게요.","I'll open the trunk for you.","我来帮您开后备箱。","トランクを開けます。"),
    PhraseCard("10","짐 있으세요?","Do you have any luggage?","您有行李吗？","お荷物はありますか？"),
    PhraseCard("11","에어컨 조절해 드릴까요?","Would you like me to adjust the AC?","需要调节空调吗？","エアコンを調整しましょうか？"),
    PhraseCard("12","길이 막혀서 돌아가겠습니다.","There's traffic, so I'll detour.","路上堵车，我绕道走。","渋滞しているので迂回します。"),
    PhraseCard("13","약 20분 걸립니다.","It will take about 20 minutes.","大约需要20分钟。","約20分かかります。"),
    PhraseCard("14","서울 시내까지 약 1시간 걸려요.","About 1 hour to central Seoul.","到首尔市区大约需要1小时。","ソウル市内まで約1時間かかります。"),
    PhraseCard("15","고속도로를 이용할게요.","I'll take the highway.","我走高速公路。","高速道路を利用します。"),
    PhraseCard("16","통행료가 추가됩니다.","There will be a toll fee.","需要额外支付过路费。","通行料が追加されます。"),
    PhraseCard("17","영수증 드릴까요?","Would you like a receipt?","需要收据吗？","領収書はいりますか？"),
    PhraseCard("18","감사합니다. 좋은 하루 되세요!","Thank you. Have a nice day!","谢谢您，祝您愉快！","ありがとうございます。良い一日を！"),
    PhraseCard("19","인천공항 제1터미널이요, 제2터미널이요?","Terminal 1 or Terminal 2?","第一航站楼还是第二航站楼？","第1ターミナルですか、第2ターミナルですか？"),
    PhraseCard("20","호텔 이름이 어떻게 되세요?","What is the name of your hotel?","您住哪家酒店？","ホテルの名前は何ですか？"),
    PhraseCard("21","지도 앱으로 목적지 보여주세요.","Show me on a map app.","请用地图软件给我看目的地。","地図アプリで目的地を見せてください。"),
    PhraseCard("22","물건을 두고 내리셨나요?","Did you leave something?","您有东西忘在车上了吗？","忘れ物はありますか？"),
    PhraseCard("23","전화번호를 적어주시겠어요?","Could you write your phone number?","能写下您的电话号码吗？","電話番号を書いていただけますか？"),
    PhraseCard("24","잠깐 여기서 기다려 주시겠어요?","Could you wait here?","请在这里稍等一下。","少しここで待っていただけますか？")
)

fun loadCustomPhrases(context: Context): List<PhraseCard> {
    val prefs = context.getSharedPreferences("callradar_phrases", Context.MODE_PRIVATE)
    val json = prefs.getString("custom_phrases", "[]") ?: "[]"
    return try {
        val arr = JSONArray(json); val list = mutableListOf<PhraseCard>()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); list.add(PhraseCard(o.optString("id","$i"),o.optString("korean",""),o.optString("english",""),o.optString("chinese",""),o.optString("japanese",""),true)) }
        list
    } catch (e: Exception) { emptyList() }
}
fun saveCustomPhrases(context: Context, phrases: List<PhraseCard>) {
    val arr = JSONArray(); phrases.forEach { p -> arr.put(JSONObject().apply { put("id",p.id);put("korean",p.korean);put("english",p.english);put("chinese",p.chinese);put("japanese",p.japanese) }) }
    context.getSharedPreferences("callradar_phrases", Context.MODE_PRIVATE).edit().putString("custom_phrases", arr.toString()).apply()
}
suspend fun translateWithGoogle(text: String, targetLang: String): String {
    return withContext(Dispatchers.IO) { try {
        val encoded = java.net.URLEncoder.encode(text, "UTF-8")
        // [보안 2026-08-26] 우리 세션 토큰을 외부 번역 API에 붙여 보내고 있었다.
        //  Auth 헤더를 붙이는 코드가 자사 서버 호출용으로 복사되며 여기까지 딸려 온 것.
        //  mymemory는 인증을 요구하지도 않는데 토큰만 국외 제3자에게 넘어갔다 → 제거한다.
        //  (Cloudinary·Nominatim 호출에는 이미 토큰을 빼는 방어가 있었는데 여기만 빠져 있었음)
        val conn = (URL("https://api.mymemory.translated.net/get?q=$encoded&langpair=ko|$targetLang").openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 15000 }
        val json = JSONObject(conn.inputStream.bufferedReader().readText()); json.getJSONObject("responseData").getString("translatedText")
    } catch (e: Exception) { "(번역 실패)" } }
}
suspend fun translateAll(korean: String): Triple<String, String, String> {
    return withContext(Dispatchers.IO) { try { Triple(translateWithGoogle(korean,"en"),translateWithGoogle(korean,"zh-CN"),translateWithGoogle(korean,"ja")) } catch (e: Exception) { Triple("(실패)","(실패)","(실패)") } }
}

fun parseItems(body: JSONObject?): JSONArray {
    val raw = body?.opt("items") ?: return JSONArray()
    return when (raw) { is JSONArray -> raw; is JSONObject -> raw.optJSONArray("item") ?: JSONArray(); else -> JSONArray() }
}
fun formatTime(raw: String): String {
    if (raw.length < 12) return raw
    return "${raw.substring(8,10)}:${raw.substring(10,12)}"
}

@Composable
fun AirportScreen() {
    val bg = AppTheme.bg; val card = AppTheme.card; val accent = Color(0xFFF59E0B)
    val green = Color(0xFF10B981); val red = Color(0xFFEF4444); val muted = Color(0xFF6B7280)
    val context = LocalContext.current
    var selectedTerminal by remember { mutableStateOf(0) }
    var selectedTab by remember { mutableStateOf(0) }
    var t1Data by remember { mutableStateOf<TerminalData?>(null) }
    var t2Data by remember { mutableStateOf<TerminalData?>(null) }
    var selectedFlight by remember { mutableStateOf<FlightInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var lastUpdated by remember { mutableStateOf("") }
    var forecast by remember { mutableStateOf<List<HourPax>>(emptyList()) }
    var customPhrases by remember { mutableStateOf(loadCustomPhrases(context)) }
    var phraseLanguage by remember { mutableStateOf(0) }
    var showAddPhraseDialog by remember { mutableStateOf(false) }
    var newPhraseKorean by remember { mutableStateOf("") }
    var isTranslating by remember { mutableStateOf(false) }
    var expandedPhraseId by remember { mutableStateOf<String?>(null) }
    // [공항 도착 예측] 내 이동시간(분) — '앞으로 도착 손님' 30분 버킷에서 내가 도착할 칸 강조용. prefs 저장.
    val airportPrefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    var airportTravelMin by remember { mutableStateOf(airportPrefs.getInt("airport_travel_min", 45)) }
    var ttsGender by remember { mutableStateOf(0) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { val t = TextToSpeech(context) { if (it == TextToSpeech.SUCCESS) ttsReady = true }; tts = t; onDispose { t.shutdown() } }
    fun speakText(text: String, lang: Int) {
        if (!ttsReady) return
        val hasJp = text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }
        val hasCn = text.any { it in '\u4E00'..'\u9FFF' } && !hasJp
        tts?.language = when { hasJp -> Locale.JAPAN; hasCn -> Locale.CHINA; text.any { it in 'a'..'z' || it in 'A'..'Z' } -> Locale.US; else -> when(lang){0->Locale.US;1->Locale.CHINA;2->Locale.JAPAN;else->Locale.US} }
        tts?.setPitch(if (ttsGender == 1) 0.75f else 1.1f)
        tts?.setSpeechRate(0.85f); tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }
    val scope = rememberCoroutineScope()
    val SERVER_URL = Config.SERVER_URL

    if (showAddPhraseDialog) {
        AlertDialog(onDismissRequest = { if (!isTranslating) { showAddPhraseDialog = false; newPhraseKorean = "" } },
            title = { Text("회화카드 추가 ✨", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column {
                Text("한국어로 입력하면 3개국어로 번역돼요!", fontSize = 13.sp, color = muted, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(value = newPhraseKorean, onValueChange = { newPhraseKorean = it }, label = { Text("한국어 문장", color = muted) }, modifier = Modifier.fillMaxWidth(), enabled = !isTranslating,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                if (isTranslating) { Spacer(Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(color = accent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("번역 중...", fontSize = 13.sp, color = accent) } }
            } },
            confirmButton = { Button(onClick = { if (newPhraseKorean.isNotBlank() && !isTranslating) { isTranslating = true; scope.launch { val (en,zh,ja) = translateAll(newPhraseKorean); val c = PhraseCard(System.currentTimeMillis().toString(),newPhraseKorean.trim(),en,zh,ja,true); val u = customPhrases+c; customPhrases = u; saveCustomPhrases(context,u); isTranslating = false; showAddPhraseDialog = false; newPhraseKorean = "" } } }, enabled = !isTranslating && newPhraseKorean.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("번역 추가", color = Color.Black, fontWeight = FontWeight.Bold) } },
            dismissButton = { OutlinedButton(onClick = { if (!isTranslating) { showAddPhraseDialog = false; newPhraseKorean = "" } }) { Text("취소") } }, containerColor = AppTheme.card)
    }

    // ===== 항공편 상세 팝업 (공발이 스타일) =====
    selectedFlight?.let { f ->
        Dialog(onDismissRequest = { selectedFlight = null }) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1F2E))) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(f.flightNo, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    Spacer(Modifier.height(16.dp)); HorizontalDivider(color = Color(0xFF2D3446)); Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.Start) { Text("출발", fontSize = 12.sp, color = muted); Spacer(Modifier.height(4.dp)); Text(f.origin, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppTheme.text) }
                        Text("→", fontSize = 24.sp, color = accent)
                        Column(horizontalAlignment = Alignment.End) { Text("도착", fontSize = 12.sp, color = muted); Spacer(Modifier.height(4.dp)); Text("인천", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppTheme.text) }
                    }
                    Spacer(Modifier.height(20.dp))
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF232A3B))) {
                        Column(Modifier.padding(vertical = 16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("도착 시간", fontSize = 13.sp, color = muted); Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (f.isDelayed && f.scheduledTime != f.estimatedTime) Text(f.scheduledTime, fontSize = 18.sp, color = muted, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                                Text(f.estimatedTime, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (f.isDelayed) red else green)
                            }
                            if (f.isDelayed) { Spacer(Modifier.height(4.dp)); Text("지연됨", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = red) }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("터미널" to f.terminal, "게이트" to f.gate.ifEmpty { "-" }, "입국장" to f.entryGate.ifEmpty { "-" }).forEach { (l, v) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text(l, fontSize = 12.sp, color = muted); Spacer(Modifier.height(6.dp))
                                Text(v, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (l == "게이트") accent else AppTheme.text)
                            }
                        }
                    }
                    if (f.total > 0) {
                        Spacer(Modifier.height(16.dp)); HorizontalDivider(color = Color(0xFF2D3446)); Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("내국인", fontSize = 12.sp, color = muted); Text("${f.korean}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.text) }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("외국인", fontSize = 12.sp, color = muted); Text("${f.foreigner}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.text) }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("합계", fontSize = 12.sp, color = muted); Text("${f.total}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = accent) }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { selectedFlight = null }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))) { Text("확인", color = AppTheme.text, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    fun loadAirportData() {
        scope.launch {
            if (t1Data == null && t2Data == null) isLoading = true
            try {
                withContext(Dispatchers.IO) {
                    val conn = (URL("$SERVER_URL/api/airport/cached").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                    val raw = conn.inputStream.bufferedReader().readText(); conn.disconnect()
                    val json = JSONObject(raw)

                    fun parseTerminal(key: String): TerminalData? {
                        val obj = json.optJSONObject(key) ?: return null
                        val tx = obj.optJSONObject("taxi") ?: JSONObject()
                        val stand = tx.optJSONObject("stand") ?: JSONObject()
                        val taxi = TaxiStatus(
                            seoul = tx.optInt("seoul",0), incheon = tx.optInt("incheon",0),
                            gyeonggi = tx.optInt("gyeonggi",0), intercity = tx.optInt("intercity",0),
                            best = tx.optInt("best",0), van = tx.optInt("van",0),
                            standSeoul = stand.optString("seoul",""), standIncheon = stand.optString("incheon",""),
                            standGyeonggi = stand.optString("gyeonggi",""), standIntercity = stand.optString("intercity",""),
                            standBestVan = stand.optString("bestVan","")
                        )
                        val fArr = obj.optJSONArray("flights") ?: JSONArray()
                        val fList = mutableListOf<FlightInfo>()
                        for (i in 0 until fArr.length()) {
                            val it = fArr.getJSONObject(i)
                            fList.add(FlightInfo(
                                flightNo = it.optString("flightNo",""), origin = it.optString("origin",""),
                                scheduledTime = it.optString("scheduledTime",""), estimatedTime = it.optString("estimatedTime",""),
                                terminal = it.optString("terminal",""), gate = it.optString("gate",""),
                                entryGate = it.optString("entryGate",""), korean = it.optInt("korean",0),
                                foreigner = it.optInt("foreigner",0), isDelayed = it.optBoolean("isDelayed",false)
                            ))
                        }
                        val nf = obj.optJSONObject("nextFlight")
                        val nextFlight = if (nf != null) NextFlight(
                            flightNo = nf.optString("flightNo",""), origin = nf.optString("origin",""),
                            time = nf.optString("time",""), pax = nf.optInt("pax",0)
                        ) else null
                        // [v16] 캐시 hourly(도착항공편 기반 시간대별 입국 인원) 파싱
                        val hArr = obj.optJSONArray("hourly") ?: JSONArray()
                        val hList = mutableListOf<HourlyPax>()
                        for (i in 0 until hArr.length()) { val h = hArr.getJSONObject(i); hList.add(HourlyPax(h.optInt("hour",0), h.optInt("pax",0), h.optInt("count",0))) }
                        return TerminalData(taxi, fList,
                            obj.optInt("immigrationTotal",0), obj.optInt("upcomingCount",0),
                            obj.optInt("upcomingPax",0), nextFlight, hList,
                            obj.optInt("in30Pax",-1), obj.optInt("in60Pax",-1))
                    }

                    t1Data = parseTerminal("t1")
                    t2Data = parseTerminal("t2")

                    // 시간대별 입국 예고 (승객예고)
                    try {
                        val pConn = (URL("$SERVER_URL/api/airport/passengers").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                        val pRaw = pConn.inputStream.bufferedReader().readText(); pConn.disconnect()
                        val pArr = JSONArray(pRaw)
                        val fList = mutableListOf<HourPax>()
                        for (i in 0 until pArr.length()) { val o = pArr.getJSONObject(i); fList.add(HourPax(o.optInt("hour",0), o.optInt("t1",0), o.optInt("t2",0))) }
                        forecast = fList
                    } catch (e: Exception) { android.util.Log.e("AirportAPI","승객예고 로드 오류: ${e.message}") }
                }
                lastUpdated = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())
            } catch (e: Exception) { android.util.Log.e("AirportAPI","캐시 로드 오류: ${e.message}") }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { while(true) { loadAirportData(); delay(30000L) } }

    Column(modifier = Modifier.fillMaxSize().background(bg)) {
        // 헤더
        Column(modifier = Modifier.fillMaxWidth().background(AppTheme.card).padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("✈️ 인천공항", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppTheme.text); if (lastUpdated.isNotEmpty()) Text("업데이트: $lastUpdated", fontSize = 11.sp, color = muted) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CaptureButton()   // [v92] 입국 수요 화면 캡처
                    TextButton(onClick = { scope.launch { loadAirportData() } }) { Text("새로고침", fontSize = 12.sp, color = accent) }
                }
            }
            Spacer(Modifier.height(10.dp))
            // 터미널 + 회화카드 같은 줄
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1터미널","2터미널","회화카드").forEachIndexed { index, title ->
                    if (index < 2) {
                        FilterChip(selected = selectedTerminal == index && selectedTab != 3, onClick = { if (selectedTerminal != index) { selectedTerminal = index }; selectedTab = 0 }, label = { Text(title, fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                    } else {
                        FilterChip(selected = selectedTab == 3, onClick = { selectedTab = 3 }, label = { Text(title, fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF7C3AED), selectedLabelColor = Color.White, containerColor = AppTheme.surface2, labelColor = muted))
                    }
                }
            }
            // 서브탭 (회화카드 선택시 숨김)
            if (selectedTab != 3) { Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("입출국장","도착항공편","예상혼잡도").forEachIndexed { index, title ->
                        FilterChip(selected = selectedTab == index, onClick = { selectedTab = index }, label = { Text(title, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppTheme.surface2, selectedLabelColor = accent, containerColor = Color.Transparent, labelColor = muted))
                    }
                }
            }
        }

        val curData = if (selectedTerminal == 0) t1Data else t2Data
        val tn = if (selectedTerminal == 0) "T1" else "T2"

        when (selectedTab) {
            0 -> { // 입출국장 (택시 대기장 + 입국심사)
                if (isLoading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = accent); Spacer(Modifier.height(12.dp)); Text("불러오는 중...", fontSize = 13.sp, color = muted) } } }
                else { LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // 택시 대기장 카드 (6종류 2열 그리드)
                    item {
                        val tx = curData?.taxi
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.padding(20.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("제${if(selectedTerminal==0)"1" else "2"}여객터미널 택시대기장", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                    Text("🚕", fontSize = 20.sp)
                                }
                                Spacer(Modifier.height(14.dp)); HorizontalDivider(color = AppTheme.surface2); Spacer(Modifier.height(14.dp))
                                val items = if (selectedTerminal == 0) {
                                    // T1: 서울/인천/경기가 같은 승차장(통합) → 일반(중형)으로 합산
                                    listOf(
                                        Triple("일반(중형)", (tx?.seoul ?: 0) + (tx?.incheon ?: 0) + (tx?.gyeonggi ?: 0), green),
                                        Triple("모범", tx?.best ?: 0, accent),
                                        Triple("대형", tx?.van ?: 0, Color(0xFF60A5FA))
                                    )
                                } else {
                                    // T2: 지역별 승차장 분리 → 6종류
                                    listOf(
                                        Triple("서울", tx?.seoul ?: 0, green), Triple("시계외", tx?.intercity ?: 0, green),
                                        Triple("인천", tx?.incheon ?: 0, green), Triple("모범", tx?.best ?: 0, accent),
                                        Triple("경기", tx?.gyeonggi ?: 0, green), Triple("대형", tx?.van ?: 0, Color(0xFF60A5FA))
                                    )
                                }
                                items.chunked(2).forEach { row ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        row.forEach { (label, cnt, col) ->
                                            Row(Modifier.weight(1f).background(Color(0xFF1B2233), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(label, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                                Text("$cnt", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (cnt == 0) Color(0xFF9AA4B2) else col)
                                            }
                                        }
                                        // T1은 홀수(3개)라 마지막 줄 한 칸 비는 것 방지
                                        if (row.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                                if (selectedTerminal == 0 && !tx?.standSeoul.isNullOrEmpty()) {
                                    Spacer(Modifier.height(10.dp)); Text("승차위치: ${tx?.standSeoul}", fontSize = 11.sp, color = muted)
                                } else if (selectedTerminal == 1 && !tx?.standSeoul.isNullOrEmpty()) {
                                    Spacer(Modifier.height(10.dp)); Text("승차위치: 서울 ${tx?.standSeoul} · 시외 ${tx?.standIntercity} · 인천 ${tx?.standIncheon}", fontSize = 11.sp, color = muted)
                                }
                            }
                        }
                    }
                    // 입국심사 진행 인원 카드
                    item {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.padding(20.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("🛂", fontSize = 18.sp)
                                    Text("$tn 입국심사 진행 인원", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("${String.format("%,d", curData?.immigrationTotal ?: 0)} 명", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = green)
                                Spacer(Modifier.height(6.dp))
                                Text("현재 입국장으로 들어오는 승객 규모입니다", fontSize = 11.sp, color = muted)
                                // [예측] 도착 항공편 예정시각+인원을 합산해 30분·1시간 후 유입 예상 표시 (약속 사양)
                                run {
                                    val calF = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"))
                                    val nowMinF = calF.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calF.get(java.util.Calendar.MINUTE)
                                    var in30 = 0; var in60 = 0
                                    // [유저제보⑥] 미래편은 estimatedTime이 빈 경우가 많아 항상 0으로 나오던 버그
                                    //  → 서버가 sch 폴백 포함해 계산한 in30Pax/in60Pax 우선 사용, 없으면(구서버) 로컬 계산(est→sch 폴백)
                                    if ((curData?.in30Srv ?: -1) >= 0) { in30 = curData!!.in30Srv; in60 = curData!!.in60Srv }
                                    else (curData?.flights ?: emptyList()).forEach { f ->
                                        val tstr = if (f.estimatedTime.isNotBlank()) f.estimatedTime else f.scheduledTime
                                        val ps = tstr.split(":")
                                        val fh = ps.getOrNull(0)?.trim()?.toIntOrNull(); val fm = ps.getOrNull(1)?.trim()?.take(2)?.toIntOrNull()
                                        if (fh != null && fm != null && f.total > 0) {
                                            var diff = fh * 60 + fm - nowMinF
                                            if (diff < -720) diff += 1440   // 자정 넘김 보정
                                            if (diff in 0..29) { in30 += f.total; in60 += f.total }
                                            else if (diff in 30..59) in60 += f.total
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    HorizontalDivider(color = AppTheme.surface2)
                                    Spacer(Modifier.height(10.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column {
                                            Text("30분 내 도착 예정", fontSize = 11.sp, color = muted)
                                            Text("+${String.format("%,d", in30)} 명", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (in30 > 300) red else accent)
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("1시간 내 도착 예정", fontSize = 11.sp, color = muted)
                                            Text("+${String.format("%,d", in60)} 명", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (in60 > 600) red else accent)
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("도착편 예정시각·탑승인원 기준 예상치예요", fontSize = 10.sp, color = muted)
                                }
                            }
                        }
                    }
                } }
            }
            1 -> { // 도착항공편
                val flights = curData?.flights ?: emptyList()
                if (isLoading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) } }
                else if (flights.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("✈️",fontSize=48.sp);Spacer(Modifier.height(12.dp));Text("항공편 정보가 없어요",fontSize=14.sp,color=muted) } } }
                else { LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical=12.dp)) {
                    items(flights) { f ->
                        Card(Modifier.fillMaxWidth().clickable { selectedFlight = f }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(f.estimatedTime, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (f.isDelayed) red else accent)
                                        if (f.isDelayed) Text("지연", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = red, modifier = Modifier.background(Color(0x33EF4444), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp))
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("${f.origin}  [${f.flightNo}]", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                    Spacer(Modifier.height(3.dp))
                                    Text("내국인 ${f.korean} · 외국인 ${f.foreigner} · 입국장 ${f.entryGate}", fontSize = 11.sp, color = muted)
                                }
                                if (f.total > 0) Column(horizontalAlignment = Alignment.End) {
                                    Text("${f.total}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if(f.total>200) red else if(f.total>100) accent else green)
                                    Text("명", fontSize = 11.sp, color = muted)
                                }
                            }
                        }
                    }
                } }
            }
            2 -> { // 입국장 예상 혼잡도 (30분 내 도착)
                if (isLoading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) } }
                else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // [공항 도착 예측] 앞으로 도착 손님 — 30분 단위(향후 3시간). 도착 항공편의 예정시각+인원을 버킷팅.
                        //  "지금"이 아니라 "내가 도착할 때"를 보게: 이동시간 칩으로 내 도착 칸 강조. 과거편은 인원0이라 안전.
                        run {
                            val cal0 = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"))
                            val nowMin0 = cal0.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal0.get(java.util.Calendar.MINUTE)
                            val paxBk = IntArray(6); val flBk = IntArray(6)
                            (curData?.flights ?: emptyList()).forEach { f ->
                                val et = f.estimatedTime
                                val ps = et.split(":")
                                val fh = ps.getOrNull(0)?.trim()?.toIntOrNull(); val fm = ps.getOrNull(1)?.trim()?.take(2)?.toIntOrNull()
                                if (fh != null && fm != null) {
                                    var mm = (fh * 60 + fm) - nowMin0
                                    if (mm < -180) mm += 1440   // 자정 직전 현재 → 새벽 도착편 보정
                                    if (mm in 0 until 180) { val bi = (mm / 30).coerceIn(0, 5); paxBk[bi] += f.total; flBk[bi] += 1 }
                                }
                            }
                            val maxBk = (paxBk.maxOrNull() ?: 0).coerceAtLeast(1)
                            val myBk = (airportTravelMin / 30).coerceIn(0, 5)
                            val totalAhead = paxBk.sum()
                            val bkLabels = listOf("지금~30분", "30~60분", "60~90분", "90~120분", "120~150분", "150~180분")
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
                                Column(Modifier.padding(20.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("🛬", fontSize = 18.sp); Text("$tn 앞으로 도착 손님 (30분 단위)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                    }
                                    Text("내 이동시간을 고르면 도착할 때 손님 규모가 강조돼요", fontSize = 11.sp, color = muted)
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(30, 45, 60).forEach { v ->
                                            FilterChip(selected = airportTravelMin == v, onClick = { airportTravelMin = v; airportPrefs.edit().putInt("airport_travel_min", v).apply() },
                                                label = { Text(if (airportTravelMin == v) "${v}분 · 내 도착" else "${v}분", fontSize = 11.sp) },
                                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                                        }
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    if (totalAhead == 0) {
                                        Text("앞으로 3시간 도착 예정 손님이 거의 없어요.\n(심야엔 항공편이 적어요)", fontSize = 12.sp, color = muted, modifier = Modifier.padding(vertical = 6.dp))
                                    } else {
                                        for (i in 0 until 6) {
                                            val pax = paxBk[i]; val isMine = i == myBk
                                            val strong = pax >= maxBk * 0.75f
                                            val barCol = if (isMine) accent else if (strong) Color(0xFFFBBF24) else Color(0xFF3A4256)
                                            val frac = (pax.toFloat() / maxBk).coerceIn(0.03f, 1f)
                                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(bkLabels[i], fontSize = 11.sp, color = if (isMine) accent else muted, fontWeight = if (isMine) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(64.dp))
                                                Box(Modifier.weight(1f).height(20.dp).background(Color(0xFF1B2233), RoundedCornerShape(5.dp))) {
                                                    Box(Modifier.fillMaxWidth(frac).height(20.dp).background(barCol, RoundedCornerShape(5.dp)))
                                                }
                                                Column(Modifier.width(66.dp), horizontalAlignment = Alignment.End) {
                                                    Text(String.format("%,d명", pax), fontSize = 12.sp, color = AppTheme.text, fontWeight = FontWeight.Medium)
                                                    Text("${flBk[i]}편", fontSize = 10.sp, color = muted)
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text("도착편 예정시각 기준 · 노란 줄이 내가 도착할 시간대", fontSize = 11.sp, color = muted)
                                    }
                                }
                            }
                        }
                        // [v16] 시간대별 입국 예고 — 승객예고(passengers)가 있으면 그걸, 비면 도착항공편 기반 hourly로 폴백. 둘 다 없으면 안내.
                        run {
                            val nowHour = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul")).get(java.util.Calendar.HOUR_OF_DAY)
                            val fromForecast = forecast.isNotEmpty()
                            val bars: List<Pair<Int, Int>> = if (fromForecast)
                                forecast.map { it.hour to (if (selectedTerminal == 0) it.t1 else it.t2) }
                            else
                                (curData?.hourly ?: emptyList()).map { it.hour to it.pax }
                            val maxPax = (bars.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
                            Card(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onLongPress = { val sb = StringBuilder("🚕 콜레이더 · " + tn + " 시간대별 입국 예고 (인천공항)\n"); bars.sortedByDescending { it.second }.take(5).forEach { (h, p) -> sb.append(String.format("%02d시 · %,d명\n", h, p)) }; sb.append("\n공항 손님 몰리는 시간 참고! · 콜레이더"); context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, sb.toString()) }, "공유")) }) }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
                                Column(Modifier.padding(20.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("📊", fontSize = 18.sp); Text("$tn 시간대별 입국 예고 (꾹 눌러 공유)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                    }
                                    Text(if (fromForecast) "오늘 예상 입국 승객 (인천공항 승객예고)" else "앞으로 도착 예정 항공편 기준 시간대별 입국 인원", fontSize = 11.sp, color = muted)
                                    Spacer(Modifier.height(14.dp))
                                    if (bars.isEmpty()) {
                                        Text("입국 예고 데이터를 불러오지 못했어요.\n위 새로고침을 눌러 주세요. (서버 준비에 몇 초 걸릴 수 있어요)", fontSize = 12.sp, color = muted, modifier = Modifier.padding(vertical = 8.dp))
                                    } else {
                                        bars.forEach { (hour, pax) ->
                                            val isNow = hour == nowHour
                                            val ratio = (pax.toFloat() / maxPax).coerceIn(0.02f, 1f)
                                            val barColor = if (pax >= maxPax * 0.8f) red else if (pax >= maxPax * 0.5f) accent else green
                                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(String.format("%02d시", hour), fontSize = 12.sp, color = if (isNow) accent else muted, fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(42.dp))
                                                Box(Modifier.weight(1f).height(18.dp).background(Color(0xFF1B2233), RoundedCornerShape(4.dp))) {
                                                    Box(Modifier.fillMaxWidth(ratio).height(18.dp).background(barColor, RoundedCornerShape(4.dp)))
                                                    if (isNow) Text("● 현재", fontSize = 9.sp, color = AppTheme.text, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp))
                                                }
                                                Text(String.format("%,d", pax), fontSize = 12.sp, color = AppTheme.text, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(52.dp))
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text("막대가 길수록 그 시간대 입국 손님이 많아요 · 지금은 " + nowHour + "시", fontSize = 11.sp, color = muted)
                                    }
                                }
                            }
                        }
                        // (기존 '30분 내 도착 예정' 단일 카드는 위 '앞으로 도착 손님' 30분 버킷 카드로 대체됨)
                        // 다음 도착 편 카드
                        curData?.nextFlight?.let { nf ->
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
                                Column(Modifier.padding(20.dp)) {
                                    Text("✈️ 다음 도착 편", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = muted)
                                    Spacer(Modifier.height(12.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column {
                                            Text(nf.time, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = accent)
                                            Spacer(Modifier.height(2.dp))
                                            Text("${nf.origin} [${nf.flightNo}]", fontSize = 14.sp, color = AppTheme.text)
                                        }
                                        if (nf.pax > 0) Column(horizontalAlignment = Alignment.End) {
                                            Text("${nf.pax}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = green); Text("명 입국", fontSize = 11.sp, color = muted)
                                        }
                                    }
                                }
                            }
                        }
                        Text("현재 시각 기준 30분 이내 도착 예정 항공편입니다", fontSize = 11.sp, color = muted, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
            3 -> { // 회화카드
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().background(Color(0xFF0D1117)).padding(horizontal=16.dp,vertical=8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("🇺🇸 영어","🇨🇳 중국어","🇯🇵 일본어").forEachIndexed { i,l -> FilterChip(selected=phraseLanguage==i,onClick={phraseLanguage=i},label={Text(l,fontSize=11.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=accent,selectedLabelColor=Color.Black,containerColor=AppTheme.surface2,labelColor=muted)) }
                            }
                            TextButton(onClick={newPhraseKorean="";showAddPhraseDialog=true}){Text("+ 추가",fontSize=13.sp,color=accent,fontWeight=FontWeight.Bold)}
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top=4.dp)) {
                            FilterChip(selected=ttsGender==0,onClick={ttsGender=0},label={Text("👩 여자",fontSize=11.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Color(0xFF60A5FA),selectedLabelColor=Color.White,containerColor=AppTheme.surface2,labelColor=muted))
                            FilterChip(selected=ttsGender==1,onClick={ttsGender=1},label={Text("👨 남자",fontSize=11.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Color(0xFF60A5FA),selectedLabelColor=Color.White,containerColor=AppTheme.surface2,labelColor=muted))
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=12.dp)) {
                        if(customPhrases.isNotEmpty()){
                            item{Text("⭐ 내가 추가한 문장 (${customPhrases.size}개) · 맨 위 고정",fontSize=12.sp,color=accent,modifier=Modifier.padding(bottom=4.dp))}
                            items(customPhrases){p->PhraseCardItem(p,phraseLanguage,expandedPhraseId==p.id,{expandedPhraseId=if(expandedPhraseId==p.id)null else p.id},{t->speakText(t,phraseLanguage)},{val u=customPhrases.filter{it.id!=p.id};customPhrases=u;saveCustomPhrases(context,u)},card,accent,muted,green,red)}
                            item{Spacer(Modifier.height(10.dp))}
                        } else { item{ Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color(0xFF0D1117)),shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("✨",fontSize=28.sp);Spacer(Modifier.height(8.dp));Text("나만의 회화카드를 추가하면 맨 위에 표시됩니다!",fontSize=13.sp,color=muted)}}; Spacer(Modifier.height(10.dp)) } }
                        item{Text("기본 문장 (${DEFAULT_PHRASES.size}개)",fontSize=12.sp,color=muted,modifier=Modifier.padding(bottom=4.dp))}
                        items(DEFAULT_PHRASES){p->PhraseCardItem(p,phraseLanguage,expandedPhraseId==p.id,{expandedPhraseId=if(expandedPhraseId==p.id)null else p.id},{t->speakText(t,phraseLanguage)},null,card,accent,muted,green,red)}
                        item{Spacer(Modifier.height(20.dp))}
                    }
                }
            }
        }
    }
}

@Composable
fun PhraseCardItem(phrase: PhraseCard,language:Int,isExpanded:Boolean,onToggle:()->Unit,onSpeak:(String)->Unit,onDelete:(()->Unit)?,card:Color,accent:Color,muted:Color,green:Color,red:Color) {
    val txt = when(language){0->phrase.english;1->phrase.chinese;2->phrase.japanese;else->phrase.english}
    Card(Modifier.fillMaxWidth().clickable{onToggle()},colors=CardDefaults.cardColors(containerColor=if(isExpanded)Color(0xFF1A2035)else card),shape=RoundedCornerShape(12.dp)){
        Column(Modifier.padding(16.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text(phrase.korean,fontSize=15.sp,fontWeight=FontWeight.Bold,color=AppTheme.text,lineHeight=22.sp);Spacer(Modifier.height(4.dp));Text(txt,fontSize=13.sp,color=accent,lineHeight=18.sp)}
                Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={onSpeak(txt)},contentPadding=PaddingValues(4.dp)){Text("🔊",fontSize=18.sp)};if(onDelete!=null){TextButton(onClick=onDelete,contentPadding=PaddingValues(4.dp)){Text("🗑️",fontSize=14.sp)}};Text(if(isExpanded)"▲"else"▼",fontSize=11.sp,color=muted,modifier=Modifier.padding(start=4.dp))}
            }
            if(isExpanded){Spacer(Modifier.height(12.dp));HorizontalDivider(color=AppTheme.surface2);Spacer(Modifier.height(12.dp))
                listOf("🇰🇷 한국어" to phrase.korean,"🇺🇸 English" to phrase.english,"🇨🇳 中文" to phrase.chinese,"🇯🇵 日本語" to phrase.japanese).forEachIndexed{i,(l,t)->
                    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Text(l,fontSize=11.sp,color=accent,modifier=Modifier.width(72.dp));Text(t,fontSize=13.sp,color=AppTheme.text,modifier=Modifier.weight(1f));if(i>0){TextButton(onClick={onSpeak(t)},contentPadding=PaddingValues(4.dp)){Text("🔊",fontSize=14.sp)}}}
                }
            }
        }
    }
}