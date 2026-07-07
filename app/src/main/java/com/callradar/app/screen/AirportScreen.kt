package com.callradar.app.screen

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class FlightInfo(
    val flightNo: String, val origin: String, val scheduledTime: String,
    val estimatedTime: String, val terminal: String, val gate: String,
    val carousel: String, val remark: String, val korean: Int, val foreigner: Int
)
data class PassengerCount(val hour: Int, val t1: Int, val t2: Int)
data class AirportStatus(
    val t1SeoulTaxi: Int, val t1GyeonggiTaxi: Int, val t1IncheonTaxi: Int, val t1VanTaxi: Int,
    val t2SeoulTaxi: Int, val t2GyeonggiTaxi: Int, val t2IncheonTaxi: Int, val t2VanTaxi: Int,
    val t1ImmigrationTotal: Int, val t1ImmigrationIn5min: Int, val t1ImmigrationIn10min: Int
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
    val bg = Color(0xFF0A0E1A); val card = Color(0xFF111827); val accent = Color(0xFFF59E0B)
    val green = Color(0xFF10B981); val red = Color(0xFFEF4444); val muted = Color(0xFF6B7280)
    val context = LocalContext.current
    var selectedTerminal by remember { mutableStateOf(0) }
    var selectedTab by remember { mutableStateOf(0) }
    var flights by remember { mutableStateOf<List<FlightInfo>>(emptyList()) }
    var passengers by remember { mutableStateOf<List<PassengerCount>>(emptyList()) }
    var airportStatus by remember { mutableStateOf<AirportStatus?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var lastUpdated by remember { mutableStateOf("") }
    var customPhrases by remember { mutableStateOf(loadCustomPhrases(context)) }
    var phraseLanguage by remember { mutableStateOf(0) }
    var showAddPhraseDialog by remember { mutableStateOf(false) }
    var newPhraseKorean by remember { mutableStateOf("") }
    var isTranslating by remember { mutableStateOf(false) }
    var expandedPhraseId by remember { mutableStateOf<String?>(null) }
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
    val SERVER_URL = "https://callradar-server.onrender.com"

    if (showAddPhraseDialog) {
        AlertDialog(onDismissRequest = { if (!isTranslating) { showAddPhraseDialog = false; newPhraseKorean = "" } },
            title = { Text("회화카드 추가 ✨", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Column {
                Text("한국어로 입력하면 3개국어로 번역돼요!", fontSize = 13.sp, color = muted, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(value = newPhraseKorean, onValueChange = { newPhraseKorean = it }, label = { Text("한국어 문장", color = muted) }, modifier = Modifier.fillMaxWidth(), enabled = !isTranslating,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                if (isTranslating) { Spacer(Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(color = accent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("번역 중...", fontSize = 13.sp, color = accent) } }
            } },
            confirmButton = { Button(onClick = { if (newPhraseKorean.isNotBlank() && !isTranslating) { isTranslating = true; scope.launch { val (en,zh,ja) = translateAll(newPhraseKorean); val c = PhraseCard(System.currentTimeMillis().toString(),newPhraseKorean.trim(),en,zh,ja,true); val u = customPhrases+c; customPhrases = u; saveCustomPhrases(context,u); isTranslating = false; showAddPhraseDialog = false; newPhraseKorean = "" } } }, enabled = !isTranslating && newPhraseKorean.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("번역 추가", color = Color.Black, fontWeight = FontWeight.Bold) } },
            dismissButton = { OutlinedButton(onClick = { if (!isTranslating) { showAddPhraseDialog = false; newPhraseKorean = "" } }) { Text("취소") } }, containerColor = Color(0xFF111827))
    }

    fun loadAirportData() {
        scope.launch {
            if (flights.isEmpty() && airportStatus == null) isLoading = true
            try {
                withContext(Dispatchers.IO) {
                    // 서버 캐시에서 한 번에 가져오기
                    val conn = (URL("$SERVER_URL/api/airport/cached").openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                    val raw = conn.inputStream.bufferedReader().readText(); conn.disconnect()
                    val json = JSONObject(raw)

                    // 항공편
                    val flightsArr = json.optJSONArray("flights")
                    if (flightsArr != null) {
                        val fList = mutableListOf<FlightInfo>(); val pMap = mutableMapOf<Int, Pair<Int,Int>>()
                        val terno = if (selectedTerminal == 0) "T1" else "T2"
                        for (i in 0 until flightsArr.length()) {
                            val it = flightsArr.getJSONObject(i)
                            val kr = it.optInt("korean", 0); val fr = it.optInt("foreigner", 0)
                            val terminal = it.optString("terminal", "T1")
                            fList.add(FlightInfo(
                                flightNo = it.optString("flightNo",""), origin = it.optString("origin",""),
                                scheduledTime = it.optString("scheduledTime",""), estimatedTime = it.optString("estimatedTime",""),
                                terminal = terminal, gate = it.optString("entryGate",""), carousel = "",
                                remark = "", korean = kr, foreigner = fr
                            ))
                            val hour = it.optString("estimatedTime","").split(":").getOrNull(0)?.toIntOrNull() ?: 0
                            val count = kr + fr
                            val prev = pMap[hour] ?: Pair(0,0)
                            if (terminal.contains("T1") || terminal.contains("1")) pMap[hour] = Pair(prev.first+count, prev.second)
                            else pMap[hour] = Pair(prev.first, prev.second+count)
                        }
                        flights = if (terno == "T1") fList.filter { it.terminal.contains("1") }.sortedBy { it.estimatedTime }
                                  else fList.filter { it.terminal.contains("2") }.sortedBy { it.estimatedTime }
                        passengers = pMap.map { PassengerCount(it.key, it.value.first, it.value.second) }.sortedBy { it.hour }
                    }

                    // 택시 현황
                    val statusJson = json.optJSONObject("status")
                    if (statusJson != null) {
                        val t1 = statusJson.optJSONObject("t1"); val t2 = statusJson.optJSONObject("t2")
                        airportStatus = AirportStatus(
                            t1?.optInt("waiting",0) ?: 0, 0, 0, 0,
                            t2?.optInt("waiting",0) ?: 0, 0, 0, 0,
                            0, 0, 0
                        )
                    }
                }
                lastUpdated = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())
            } catch (e: Exception) { android.util.Log.e("AirportAPI","캐시 로드 오류: ${e.message}") }
            isLoading = false
        }
    }

    LaunchedEffect(selectedTerminal) { while(true) { loadAirportData(); delay(30000L) } }

    Column(modifier = Modifier.fillMaxSize().background(bg)) {
        // 헤더
        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF111827)).padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("✈️ 인천공항", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White); if (lastUpdated.isNotEmpty()) Text("업데이트: $lastUpdated", fontSize = 11.sp, color = muted) }
                TextButton(onClick = { scope.launch { loadAirportData() } }) { Text("새로고침", fontSize = 12.sp, color = accent) }
            }
            Spacer(Modifier.height(10.dp))
            // 터미널 + 회화카드 같은 줄
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1터미널","2터미널","회화카드").forEachIndexed { index, title ->
                    if (index < 2) {
                        FilterChip(selected = selectedTerminal == index && selectedTab != 3, onClick = { if (selectedTerminal != index) { selectedTerminal = index }; selectedTab = 0 }, label = { Text(title, fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = Color(0xFF1F2937), labelColor = muted))
                    } else {
                        FilterChip(selected = selectedTab == 3, onClick = { selectedTab = 3 }, label = { Text(title, fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF7C3AED), selectedLabelColor = Color.White, containerColor = Color(0xFF1F2937), labelColor = muted))
                    }
                }
            }
            // 서브탭 (회화카드 선택시 숨김)
            if (selectedTab != 3) { Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("입출국장","도착항공편","입국자수").forEachIndexed { index, title ->
                        FilterChip(selected = selectedTab == index, onClick = { selectedTab = index }, label = { Text(title, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF1F2937), selectedLabelColor = accent, containerColor = Color.Transparent, labelColor = muted))
                    }
                }
            }
        }

        when (selectedTab) {
            0 -> { // 입출국장
                if (isLoading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = accent); Spacer(Modifier.height(12.dp)); Text("불러오는 중...", fontSize = 13.sp, color = muted) } } }
                else { LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        val tn = if (selectedTerminal==0) "T1" else "T2"
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(20.dp)) {
                                Text("🚕 $tn 택시 대기장", fontSize = 14.sp, color = muted, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp))
                                if (selectedTerminal == 0) {
                                    val mid = (airportStatus?.t1SeoulTaxi?:0)+(airportStatus?.t1GyeonggiTaxi?:0)+(airportStatus?.t1IncheonTaxi?:0); val van = airportStatus?.t1VanTaxi?:0
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(if(mid>0)"$mid" else "-",fontSize=24.sp,fontWeight=FontWeight.Bold,color=if(mid>150)red else if(mid>80)accent else green); Text("중형(통합)",fontSize=12.sp,color=muted) }
                                        Box(Modifier.width(1.dp).height(56.dp).background(Color(0xFF1F2937)))
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(if(van>0)"$van" else "-",fontSize=24.sp,fontWeight=FontWeight.Bold,color=Color(0xFF60A5FA)); Text("모범+대형",fontSize=12.sp,color=muted) }
                                    }
                                } else {
                                    val s=airportStatus?.t2SeoulTaxi?:0;val i=airportStatus?.t2IncheonTaxi?:0;val g=airportStatus?.t2GyeonggiTaxi?:0;val v=airportStatus?.t2VanTaxi?:0
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        listOf("서울" to s,"인천" to i,"경기" to g).forEachIndexed { idx,(l,c) ->
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(if(c>0)"$c" else "-",fontSize=20.sp,fontWeight=FontWeight.Bold,color=if(c>100)red else if(c>50)accent else green); Text(l,fontSize=12.sp,color=muted) }
                                            if(idx<2) Box(Modifier.width(1.dp).height(40.dp).background(Color(0xFF1F2937)))
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp)); HorizontalDivider(color=Color(0xFF1F2937))
                                    Row(Modifier.fillMaxWidth().padding(top=8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(if(v>0)"$v" else "-",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Color(0xFF60A5FA)); Text("모범+대형",fontSize=12.sp,color=muted) }
                                    }
                                }
                            }
                        }
                    }
                } }
            }
            1 -> { // 도착항공편
                if (isLoading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) } }
                else if (flights.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("✈️",fontSize=48.sp);Spacer(Modifier.height(12.dp));Text("항공편 정보가 없어요",fontSize=14.sp,color=muted) } } }
                else { LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical=12.dp)) {
                    items(flights) { f ->
                        val total = f.korean + f.foreigner
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("${f.estimatedTime}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent)
                                        Text("${f.origin} [${f.flightNo}]", fontSize = 13.sp, color = Color.White)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("내국인: ${f.korean}명, 외국인: ${f.foreigner}명 / 출구: ${f.gate}", fontSize = 11.sp, color = muted)
                                }
                                if (total > 0) Text("${total}명", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if(total>200) red else if(total>100) accent else green)
                            }
                        }
                    }
                } }
            }
            2 -> { // 입국자수
                if (isLoading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) } }
                else if (passengers.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("👥",fontSize=48.sp);Spacer(Modifier.height(12.dp));Text("입국자 정보가 없어요",fontSize=14.sp,color=muted) } } }
                else {
                    val curHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp), contentPadding = PaddingValues(vertical=12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp)) { Text("시간",fontSize=12.sp,color=muted,modifier=Modifier.weight(1f));Text("T1 (명)",fontSize=12.sp,color=muted,modifier=Modifier.weight(1f));Text("T2 (명)",fontSize=12.sp,color=muted,modifier=Modifier.weight(1f)) }
                        } }
                        items(passengers) { p ->
                            val cur = p.hour == curHour
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if(cur) Color(0xFF1F2937) else card), shape = RoundedCornerShape(10.dp)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if(cur) Box(Modifier.size(6.dp).background(accent, RoundedCornerShape(3.dp)))
                                        Text("${p.hour}시",fontSize=if(cur)15.sp else 14.sp,fontWeight=if(cur)FontWeight.Bold else FontWeight.Normal,color=if(cur)accent else Color.White)
                                    }
                                    Text(if(p.t1>0)String.format("%,d",p.t1)else"-",fontSize=if(cur)15.sp else 14.sp,fontWeight=if(cur)FontWeight.Bold else FontWeight.Normal,color=if(cur)accent else Color.White,modifier=Modifier.weight(1f))
                                    Text(if(p.t2>0)String.format("%,d",p.t2)else"-",fontSize=if(cur)15.sp else 14.sp,fontWeight=if(cur)FontWeight.Bold else FontWeight.Normal,color=if(cur)accent else Color.White,modifier=Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            3 -> { // 회화카드
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().background(Color(0xFF0D1117)).padding(horizontal=16.dp,vertical=8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("🇺🇸 영어","🇨🇳 중국어","🇯🇵 일본어").forEachIndexed { i,l -> FilterChip(selected=phraseLanguage==i,onClick={phraseLanguage=i},label={Text(l,fontSize=11.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=accent,selectedLabelColor=Color.Black,containerColor=Color(0xFF1F2937),labelColor=muted)) }
                            }
                            TextButton(onClick={newPhraseKorean="";showAddPhraseDialog=true}){Text("+ 추가",fontSize=13.sp,color=accent,fontWeight=FontWeight.Bold)}
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top=4.dp)) {
                            FilterChip(selected=ttsGender==0,onClick={ttsGender=0},label={Text("👩 여자",fontSize=11.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Color(0xFF60A5FA),selectedLabelColor=Color.White,containerColor=Color(0xFF1F2937),labelColor=muted))
                            FilterChip(selected=ttsGender==1,onClick={ttsGender=1},label={Text("👨 남자",fontSize=11.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Color(0xFF60A5FA),selectedLabelColor=Color.White,containerColor=Color(0xFF1F2937),labelColor=muted))
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=12.dp)) {
                        item{Text("기본 문장 (${DEFAULT_PHRASES.size}개)",fontSize=12.sp,color=muted,modifier=Modifier.padding(bottom=4.dp))}
                        items(DEFAULT_PHRASES){p->PhraseCardItem(p,phraseLanguage,expandedPhraseId==p.id,{expandedPhraseId=if(expandedPhraseId==p.id)null else p.id},{t->speakText(t,phraseLanguage)},null,card,accent,muted,green,red)}
                        if(customPhrases.isNotEmpty()){
                            item{Spacer(Modifier.height(4.dp));Text("내가 추가한 문장 (${customPhrases.size}개)",fontSize=12.sp,color=accent,modifier=Modifier.padding(bottom=4.dp))}
                            items(customPhrases){p->PhraseCardItem(p,phraseLanguage,expandedPhraseId==p.id,{expandedPhraseId=if(expandedPhraseId==p.id)null else p.id},{t->speakText(t,phraseLanguage)},{val u=customPhrases.filter{it.id!=p.id};customPhrases=u;saveCustomPhrases(context,u)},card,accent,muted,green,red)}
                        } else { item{Spacer(Modifier.height(8.dp));Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color(0xFF0D1117)),shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("✨",fontSize=28.sp);Spacer(Modifier.height(8.dp));Text("나만의 회화카드를 추가해보세요!",fontSize=13.sp,color=muted)}}} }
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
                Column(Modifier.weight(1f)){Text(phrase.korean,fontSize=15.sp,fontWeight=FontWeight.Bold,color=Color.White,lineHeight=22.sp);Spacer(Modifier.height(4.dp));Text(txt,fontSize=13.sp,color=accent,lineHeight=18.sp)}
                Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={onSpeak(txt)},contentPadding=PaddingValues(4.dp)){Text("🔊",fontSize=18.sp)};if(onDelete!=null){TextButton(onClick=onDelete,contentPadding=PaddingValues(4.dp)){Text("🗑️",fontSize=14.sp)}};Text(if(isExpanded)"▲"else"▼",fontSize=11.sp,color=muted,modifier=Modifier.padding(start=4.dp))}
            }
            if(isExpanded){Spacer(Modifier.height(12.dp));HorizontalDivider(color=Color(0xFF1F2937));Spacer(Modifier.height(12.dp))
                listOf("🇰🇷 한국어" to phrase.korean,"🇺🇸 English" to phrase.english,"🇨🇳 中文" to phrase.chinese,"🇯🇵 日本語" to phrase.japanese).forEachIndexed{i,(l,t)->
                    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Text(l,fontSize=11.sp,color=accent,modifier=Modifier.width(72.dp));Text(t,fontSize=13.sp,color=Color.White,modifier=Modifier.weight(1f));if(i>0){TextButton(onClick={onSpeak(t)},contentPadding=PaddingValues(4.dp)){Text("🔊",fontSize=14.sp)}}}
                }
            }
        }
    }
}