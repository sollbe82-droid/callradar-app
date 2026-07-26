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
    var edit by remember { mutableStateOf(name.isBlank()) }
    var fullscreen by remember { mutableStateOf(false) }

    fun save() {
        prefs.edit().putString("card_name", name).putString("card_car", car).putString("card_phone", phone)
            .putString("card_slogan", slogan).putString("card_kakao", kakao).putString("card_insta", insta).putString("card_wechat", wechat).apply()
    }
    fun openUrl(u: String) { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {} }

    val vcard = buildVCard(name, phone, slogan, kakao.ifBlank { insta })
    val qr = remember(vcard) { genQr(vcard, 640) }

    // 명함을 한 장 이미지로 렌더 → 공유
    fun shareCard() {
        try {
            val w = 720; val h = 1000
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.drawColor(android.graphics.Color.WHITE)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = android.graphics.Color.parseColor("#F59E0B"); c.drawRect(0f, 0f, w.toFloat(), 12f, p)
            p.color = android.graphics.Color.parseColor("#111827"); p.textAlign = Paint.Align.CENTER
            p.textSize = 54f; p.isFakeBoldText = true; c.drawText(if (name.isBlank()) "기사님" else "$name 기사", w / 2f, 110f, p)
            p.textSize = 30f; p.isFakeBoldText = false; p.color = android.graphics.Color.parseColor("#6B7280")
            if (car.isNotBlank()) c.drawText(car, w / 2f, 160f, p)
            p.textSize = 28f; p.color = android.graphics.Color.parseColor("#F59E0B")
            c.drawText(slogan.take(24), w / 2f, 220f, p)
            qr?.let { val qs = 460; val left = (w - qs) / 2; c.drawBitmap(Bitmap.createScaledBitmap(it, qs, qs, false), left.toFloat(), 280f, null) }
            p.textSize = 26f; p.color = android.graphics.Color.parseColor("#111827")
            c.drawText("QR을 찍으면 연락처가 저장돼요", w / 2f, 810f, p)
            if (phone.isNotBlank()) { p.textSize = 30f; p.isFakeBoldText = true; c.drawText("📞 $phone", w / 2f, 870f, p) }
            p.textSize = 22f; p.isFakeBoldText = false; p.color = android.graphics.Color.parseColor("#9CA3AF")
            c.drawText("콜레이더 기사 명함", w / 2f, 950f, p)
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
            Text(if (name.isBlank()) "기사님" else "$name 기사", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
            Spacer(Modifier.height(6.dp))
            Text(slogan, fontSize = 16.sp, color = accent, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            qr?.let { Image(it.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(300.dp)) }
            Spacer(Modifier.height(20.dp))
            Text("📱 QR을 찍으면 제 연락처가 저장돼요", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
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
            Button(onClick = { save(); edit = false }, modifier = Modifier.fillMaxWidth().height(50.dp).padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(12.dp)) {
                Text("저장하고 명함 보기", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        } else {
            // 명함 미리보기 카드
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (name.isBlank()) "기사님" else "$name 기사", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    if (car.isNotBlank()) { Spacer(Modifier.height(2.dp)); Text(car, fontSize = 14.sp, color = muted) }
                    Spacer(Modifier.height(6.dp)); Text(slogan, fontSize = 14.sp, color = accent, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    qr?.let { Image(it.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(200.dp)) }
                    Spacer(Modifier.height(8.dp)); Text("QR을 찍으면 연락처가 저장돼요", fontSize = 12.sp, color = muted)
                    if (phone.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text("📞 $phone", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTheme.text) }
                }
            }
            // SNS 원탭 버튼 (있는 것만)
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
            Spacer(Modifier.height(12.dp))
            Button(onClick = { fullscreen = true }, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(12.dp)) {
                Text("🔍 손님에게 QR 크게 보여주기", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { shareCard() }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                Text("📤 명함 이미지로 공유(카톡·문자)", color = accent, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
