package com.callradar.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.callradar.app.screen.AppTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream

/**
 * [v19] 기사 디지털 명함 v1 — 단골 확보용.
 *  편집(이름·차량·전화·슬로건·SNS) → vCard QR 생성 → 전체화면(손님용) → 이미지 공유.
 *  손님이 QR 찍으면 "연락처에 추가?" 팝업(원탭 저장). 데이터는 로컬 저장(서버 불필요).
 */
class NameCardActivity : ComponentActivity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, NameCardActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NameCardScreen { finish() } }
    }
}

private fun genQr(text: String, size: Int): Bitmap? {
    return try {
        val hints = hashMapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        bmp
    } catch (e: Exception) { null }
}

// [유저요청②] 명함 다국어 — 외국인 손님(영어·중국어·일본어)에게 보여줄 문구 세트.
//  슬로건은 유저가 한국어로 쓴 것이라 그대로 번역 불가 → 언어별 검증된 기본 문구로 대체.
private data class CardL10n(
    val code: String, val chip: String,
    val driverTitle: String,          // 이름 뒤 직함
    val slogan: String,               // 언어별 기본 슬로건
    val qrHint: String,               // 카드 QR 안내
    val fsHint: String,               // 전체화면 QR 안내
    val phonePrefix: String,          // 전화 라벨
    val shareQr: String               // 공유 이미지 QR 안내
)
private val CARD_LANGS = listOf(
    CardL10n("ko", "한국어", "기사", "", "QR을 찍으면 예약·연락처 저장 페이지가 열려요", "📱 QR을 찍으면 제 연락처가 저장돼요", "📞", "QR 스캔 → 예약·연락처 저장 (앱 설치 불필요)"),
    CardL10n("en", "English", "Taxi Driver", "Safe & friendly ride. Call me anytime.", "Scan QR to book a ride or save my contact (no app needed)", "📱 Scan the QR to save my contact", "📞", "Scan QR → Book / Save contact (no app needed)"),
    CardL10n("zh", "中文", "出租车司机", "安全驾驶 · 热情服务 · 随时来电", "扫描二维码即可预约或保存联系方式(无需安装App)", "📱 扫码即可保存我的联系方式", "📞", "扫码 → 预约/保存联系方式 (无需App)"),
    CardL10n("ja", "日本語", "タクシー運転手", "安全運転・親切対応・いつでもお呼びください", "QRをスキャンすると予約・連絡先保存ページが開きます(アプリ不要)", "📱 QRをスキャンすると連絡先を保存できます", "📞", "QRスキャン → 予約/連絡先保存 (アプリ不要)")
)

// vCard 3.0 — 대부분의 폰 카메라/QR앱이 '연락처 추가'로 인식
private fun buildVCard(name: String, phone: String, slogan: String, url: String): String {
    val fn = if (name.isBlank()) "기사님" else "$name 기사"
    val sb = StringBuilder("BEGIN:VCARD\nVERSION:3.0\n")
    sb.append("FN:").append(fn).append("\n")
    sb.append("N:").append(name).append(";;;;\n")
    sb.append("ORG:콜레이더 택시\n")
    sb.append("TITLE:택시 기사\n")
    if (phone.isNotBlank()) sb.append("TEL;TYPE=CELL:").append(phone).append("\n")
    if (slogan.isNotBlank()) sb.append("NOTE:").append(slogan).append("\n")
    if (url.isNotBlank()) sb.append("URL:").append(url).append("\n")
    sb.append("END:VCARD")
    return sb.toString()
}

@Composable
private fun NameCardScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    val accent = Color(0xFFF59E0B); val muted = Color(0xFF9CA3AF)

    var name by remember { mutableStateOf(prefs.getString("card_name", prefs.getString("nickname", "") ?: "") ?: "") }
    var car by remember { mutableStateOf(prefs.getString("card_car", prefs.getString("car_number", "") ?: "") ?: "") }
    var phone by remember { mutableStateOf(prefs.getString("card_phone", "") ?: "") }
    var slogan by remember { mutableStateOf(prefs.getString("card_slogan", "안전운행 · 친절 · 언제든 다시 불러주세요") ?: "") }
    var kakao by remember { mutableStateOf(prefs.getString("card_kakao", "") ?: "") }
    var insta by remember { mutableStateOf(prefs.getString("card_insta", "") ?: "") }
    var wechat by remember { mutableStateOf(prefs.getString("card_wechat", "") ?: "") }
    // [유저요청④] 라인(일본·동남아)·와츠앱(글로벌) 추가
    var lineId by remember { mutableStateOf(prefs.getString("card_line", "") ?: "") }
    var whatsapp by remember { mutableStateOf(prefs.getString("card_wa", "") ?: "") }
    // [유저요청②] 명함 언어 (ko/en/zh/ja)
    var lang by remember { mutableStateOf(prefs.getString("card_lang", "ko") ?: "ko") }
    val L = CARD_LANGS.firstOrNull { it.code == lang } ?: CARD_LANGS[0]
    val dispName = if (name.isBlank()) "기사님" else (if (lang == "ko") "$name 기사" else "$name · ${L.driverTitle}")
    val dispSlogan = if (lang == "ko" || L.slogan.isBlank()) slogan else L.slogan
    var edit by remember { mutableStateOf(name.isBlank()) }
    var fullscreen by remember { mutableStateOf(false) }

    fun save() {
        prefs.edit().putString("card_name", name).putString("card_car", car).putString("card_phone", phone)
            .putString("card_slogan", slogan).putString("card_kakao", kakao).putString("card_insta", insta).putString("card_wechat", wechat)
            .putString("card_line", lineId).putString("card_wa", whatsapp).putString("card_lang", lang).apply()
    }
    fun openUrl(u: String) { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {} }

    // [v20] QR = 무설치 예약 링크 (스캔→그 기사 예약페이지). userId 없으면 vCard 폴백.
    val myUserId = prefs.getString("user_id", "") ?: ""
    val bookingUrl = if (myUserId.isNotBlank()) "https://callradar-server.onrender.com/book/$myUserId?name=" + java.net.URLEncoder.encode(name.ifBlank { "기사" }, "UTF-8") + (if (phone.isNotBlank()) "&tel=" + java.net.URLEncoder.encode(phone, "UTF-8") else "") else ""
    val vcard = buildVCard(name, phone, slogan, kakao.ifBlank { insta })
    val qrText = if (bookingUrl.isNotBlank()) bookingUrl else vcard
    val qr = remember(qrText) { genQr(qrText, 640) }

    // 명함을 한 장 이미지로 렌더 → 공유
    fun shareCard() {
        try {
            val w = 720; val h = 1000
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.drawColor(android.graphics.Color.WHITE)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = android.graphics.Color.parseColor("#F59E0B"); c.drawRect(0f, 0f, w.toFloat(), 12f, p)
            p.color = android.graphics.Color.parseColor("#111827"); p.textAlign = Paint.Align.CENTER
            p.textSize = 54f; p.isFakeBoldText = true; c.drawText(dispName, w / 2f, 110f, p)
            p.textSize = 30f; p.isFakeBoldText = false; p.color = android.graphics.Color.parseColor("#6B7280")
            if (car.isNotBlank()) c.drawText(car, w / 2f, 160f, p)
            p.textSize = 28f; p.color = android.graphics.Color.parseColor("#F59E0B")
            c.drawText(dispSlogan.take(28), w / 2f, 220f, p)
            qr?.let { val qs = 460; val left = (w - qs) / 2; c.drawBitmap(Bitmap.createScaledBitmap(it, qs, qs, false), left.toFloat(), 280f, null) }
            p.textSize = 26f; p.color = android.graphics.Color.parseColor("#111827")
            c.drawText(L.shareQr, w / 2f, 810f, p)
            if (phone.isNotBlank()) { p.textSize = 30f; p.isFakeBoldText = true; c.drawText("📞 $phone", w / 2f, 870f, p) }
            p.textSize = 22f; p.isFakeBoldText = false; p.color = android.graphics.Color.parseColor("#9CA3AF")
            p.color = android.graphics.Color.parseColor("#F59E0B"); p.isFakeBoldText = true; p.textSize = 30f
            c.drawText("🚕 콜레이더 · CallRadar", w / 2f, 950f, p)
            val dir = File(ctx.cacheDir, "shares").apply { mkdirs() }
            val f = File(dir, "namecard_${System.currentTimeMillis()}.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            val share = Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            ctx.startActivity(Intent.createChooser(share, "명함 공유").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: Exception) {}
    }

    // 전체화면 QR (손님에게 보여주기)
    if (fullscreen) {
        Column(Modifier.fillMaxSize().background(Color.White).clickable { fullscreen = false }.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(dispName, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
            Spacer(Modifier.height(6.dp))
            Text(dispSlogan, fontSize = 16.sp, color = accent, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            qr?.let { Image(it.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(300.dp)) }
            Spacer(Modifier.height(20.dp))
            Text(L.fsHint, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
            if (phone.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text("📞 $phone", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827)) }
            Spacer(Modifier.height(24.dp)); Text("(화면을 누르면 닫혀요)", fontSize = 12.sp, color = muted)
        }
        return
    }

    Column(Modifier.fillMaxSize().background(AppTheme.bg).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📇 기사 명함", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("닫기", color = muted) }
        }
        Text("단골·공항·외국인 손님이 다시 불러주도록 내 명함을 만들어 QR로 건네세요.", fontSize = 12.sp, color = muted, modifier = Modifier.padding(vertical = 6.dp))

        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = edit, onClick = { edit = true }, label = { Text("✏️ 편집") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
            FilterChip(selected = !edit, onClick = { save(); edit = false }, label = { Text("👤 명함 보기") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
        }

        if (edit) {
            @Composable fun field(label: String, value: String, onV: (String) -> Unit, hint: String = "") {
                OutlinedTextField(value = value, onValueChange = onV, label = { Text(label, color = muted) }, placeholder = { if (hint.isNotBlank()) Text(hint, color = Color(0xFF4B5563), fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
            }
            field("이름/닉네임", name, { name = it })
            field("차량번호", car, { car = it }, "서울 12가 3456")
            field("전화번호", phone, { phone = it.filter { c -> c.isDigit() || c == '-' } }, "010-1234-5678")
            field("한 줄 슬로건/명언", slogan, { slogan = it }, "안전운행 · 친절 보장")
            field("카카오채널/오픈챗 링크", kakao, { kakao = it }, "https://pf.kakao.com/...")
            field("인스타그램(링크/아이디)", insta, { insta = it }, "https://instagram.com/...")
            field("위챗 ID(중국 손님용)", wechat, { wechat = it })
            field("라인 ID/링크(일본 손님용)", lineId, { lineId = it }, "https://line.me/ti/p/... 또는 ID")
            field("와츠앱 번호(외국 손님용)", whatsapp, { whatsapp = it.filter { c -> c.isDigit() || c == '+' } }, "+821012345678")
            Button(onClick = { save(); edit = false }, modifier = Modifier.fillMaxWidth().height(50.dp).padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(12.dp)) {
                Text("저장하고 명함 보기", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        } else {
            // [유저요청②] 언어 선택 칩 — 외국인 손님에게 보여줄 때 탭 한 번으로 전환
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CARD_LANGS.forEach { l ->
                    FilterChip(selected = lang == l.code, onClick = { lang = l.code; prefs.edit().putString("card_lang", l.code).apply() }, label = { Text(l.chip, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                }
            }
            // 명함 미리보기 카드
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dispName, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    if (car.isNotBlank()) { Spacer(Modifier.height(2.dp)); Text(car, fontSize = 14.sp, color = muted) }
                    Spacer(Modifier.height(6.dp)); Text(dispSlogan, fontSize = 14.sp, color = accent, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    qr?.let { Image(it.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(200.dp)) }
                    Spacer(Modifier.height(8.dp)); Text(L.qrHint, fontSize = 12.sp, color = muted, textAlign = TextAlign.Center)
                    if (phone.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text("📞 $phone", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTheme.text) }
                }
            }
            // SNS 원탭 버튼 (있는 것만) — [유저요청④] 라인·와츠앱 포함
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (phone.isNotBlank()) OutlinedButton(onClick = { openUrl("tel:$phone") }, modifier = Modifier.weight(1f)) { Text("📞 전화", color = accent) }
                if (kakao.isNotBlank()) OutlinedButton(onClick = { openUrl(kakao) }, modifier = Modifier.weight(1f)) { Text("💬 카톡", color = accent) }
            }
            if (insta.isNotBlank() || wechat.isNotBlank()) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (insta.isNotBlank()) OutlinedButton(onClick = { openUrl(if (insta.startsWith("http")) insta else "https://instagram.com/${insta.trimStart('@')}") }, modifier = Modifier.weight(1f)) { Text("📷 인스타", color = accent) }
                    if (wechat.isNotBlank()) OutlinedButton(onClick = { }, modifier = Modifier.weight(1f)) { Text("위챗: $wechat", color = accent, fontSize = 12.sp) }
                }
            }
            if (lineId.isNotBlank() || whatsapp.isNotBlank()) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lineId.isNotBlank()) OutlinedButton(onClick = { openUrl(if (lineId.startsWith("http")) lineId else "https://line.me/ti/p/~${lineId.trim()}") }, modifier = Modifier.weight(1f)) { Text("🟢 라인", color = accent) }
                    if (whatsapp.isNotBlank()) OutlinedButton(onClick = { openUrl("https://wa.me/${whatsapp.trim().removePrefix("+")}") }, modifier = Modifier.weight(1f)) { Text("📲 와츠앱", color = accent) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { fullscreen = true }, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(12.dp)) {
                Text("🔍 손님에게 QR 크게 보여주기", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { shareCard() }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                Text("📤 명함 이미지로 공유(카톡·문자)", color = accent, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            // [v20] 담백한 1인칭 홍보 문구 + 예약링크 → 공유시트(카톡·당근·카페). AI 티 안 나게, 편집은 공유앱에서.
            OutlinedButton(onClick = {
                val txt = buildString {
                    append(if (name.isBlank()) "안녕하세요, 안전운행 기사입니다." else "안녕하세요, ${name} 기사입니다.")
                    if (slogan.isNotBlank()) { append("\n"); append(slogan) }
                    append("\n\n공항 가실 때, 장거리, 늦은 밤 귀가까지 편하게 불러주세요. 시간 맞춰 안전하게 모시겠습니다.")
                    if (bookingUrl.isNotBlank()) { append("\n\n📅 예약은 앱 설치 없이 1분이면 돼요 (다음엔 정보가 기억됩니다)\n"); append(bookingUrl) }
                    if (phone.isNotBlank()) { append("\n☎ 문의·예약: ${phone}") }
                    append("\n\n믿고 불러주시면 정성껏 모시겠습니다. 감사합니다 🙏")
                }
                try { val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager; cm.setPrimaryClip(android.content.ClipData.newPlainText("홍보문구", txt)); android.widget.Toast.makeText(ctx, "홍보 문구 복사됨! 카톡·당근에 붙여넣기(길게 눌러 붙여넣기) 하세요", android.widget.Toast.LENGTH_LONG).show() } catch (e: Exception) {}
                try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, txt) }, "홍보 문구 공유").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {}
            }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                Text("📢 홍보 문구 복사+공유(카톡·당근)", color = accent, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
