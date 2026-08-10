package com.callradar.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.callradar.app.ui.theme.CallRadarTheme
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ReceiptScanActivity : ComponentActivity() {

    private val SERVER_URL = "https://callradar-server.onrender.com"
    private val ocrService = ReceiptOcrService()
    private var scanResult by mutableStateOf<ReceiptOcrService.ReceiptResult?>(null)
    private var isProcessing by mutableStateOf(false)
    private var statusMessage by mutableStateOf("영수증을 촬영해주세요")

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val bitmap = result.data?.extras?.get("data") as? Bitmap
            if (bitmap != null) processReceipt(bitmap)
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            // [크래시 방지] 큰 사진을 메인스레드에서 통째로 디코드하면 OOM/ANR → 백그라운드에서 다운샘플 디코드.
            Thread {
                try {
                    val bmp = decodeDownsampledBitmap(it, 1600)
                    runOnUiThread { if (bmp != null) processReceipt(bmp) else Toast.makeText(this, "이미지를 불러오지 못했어요", Toast.LENGTH_SHORT).show() }
                } catch (e: Throwable) { runOnUiThread { Toast.makeText(this, "이미지가 너무 커요 · 다른 사진으로 시도해 주세요", Toast.LENGTH_SHORT).show() } }
            }.start()
        }
    }

    // 긴 변 maxDim 이하로 표본추출 디코드(메모리 안전). OOM 크래시 방지 + OCR엔 충분.
    private fun decodeDownsampledBitmap(uri: android.net.Uri, maxDim: Int): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1; while (longEdge / sample > maxDim) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openCamera()
        else Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_SHORT).show()
    }

    private var preset: String = ""   // [v31] "가스"/"전기" 원터치 프리셋

    companion object {
        fun start(context: Context, preset: String = "") {
            context.startActivity(Intent(context, ReceiptScanActivity::class.java).apply {
                if (preset.isNotEmpty()) putExtra("preset", preset)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preset = intent.getStringExtra("preset") ?: ""
        if (preset == "가스") statusMessage = "⛽ 가스(LPG) 영수증을 촬영하세요"
        else if (preset == "전기") statusMessage = "🔌 전기충전 영수증을 촬영하세요"
        else if (preset == "지출") statusMessage = "🧾 지출 영수증을 촬영하세요 (자동 분류)"
        setContent {
            CallRadarTheme {
                ReceiptScanScreen()
            }
        }
        // 원터치: 바로 카메라 열기
        if (preset == "가스" || preset == "전기" || preset == "지출") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
            else permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        cameraLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
    }

    private fun processReceipt(bitmap: Bitmap) {
        isProcessing = true
        statusMessage = "영수증 분석 중.."
        scanResult = null
        ocrService.processReceipt(bitmap) { result ->
            runOnUiThread {
                isProcessing = false
                if (result != null) {
                    scanResult = result
                    statusMessage = "분석 완료!"
                } else {
                    statusMessage = "분석 실패 - 다시 촬영해주세요"
                }
            }
        }
    }

    private fun saveResult(result: ReceiptOcrService.ReceiptResult) {
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", null)

        Thread {
            var savedOk = false
            try {
                if (preset == "가스" || preset == "전기" || preset == "지출") {
                    // [v31] 원터치 지출 — 정식 expenses 경로(기록 캘린더에 반영)
                    // [v32] 일반 '지출'은 OCR 자동 분류한 카테고리로 저장(식비/세차/주차/정비/통행료/LPG…)
                    val cat = when (preset) {
                        "가스" -> "LPG"
                        "전기" -> "전기충전"
                        else -> when (result.type) {
                            ReceiptOcrService.ReceiptType.LPG -> "LPG"
                            ReceiptOcrService.ReceiptType.FOOD -> "식비"
                            ReceiptOcrService.ReceiptType.WASH -> "세차"
                            ReceiptOcrService.ReceiptType.PARKING -> "주차"
                            ReceiptOcrService.ReceiptType.REPAIR -> "정비"
                            ReceiptOcrService.ReceiptType.HIPASS -> "통행료"
                            else -> "기타"
                        }
                    }
                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA).format(java.util.Date())
                    // [v31] 영수증 OCR 날짜 사용(yyyy-MM-dd로 정규화됨). 못 읽으면 오늘.
                    val expDate = if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(result.date)) result.date else today
                    val memo = when (preset) { "가스" -> "가스(LPG) 영수증"; "전기" -> "전기충전 영수증"; else -> "${result.typeName} 영수증(자동분류)" }
                    val cuid = java.util.UUID.randomUUID().toString()   // 멱등키(온라인 POST=오프라인 큐 동일) → 재전송 중복 방지
                    val litersD = result.liters.toDouble(); val ppl = result.pricePerLiter
                    val json = JSONObject().apply {
                        put("user_id", userId)
                        put("category", cat)
                        put("amount", result.amount)
                        put("expense_type", "business")
                        put("memo", memo)
                        if (litersD > 0) put("liters", litersD)
                        if (ppl > 0) put("price_per_liter", ppl)
                        put("expense_date", expDate)
                        put("client_uuid", cuid)
                    }
                    var ok = false
                    try {
                        val conn = (URL("$SERVER_URL/api/expenses").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                            requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 20000; readTimeout = 30000  // [v44] Render 콜드스타트(30~50s) 대응 — 예전 15s는 타임아웃 남발
                        }
                        conn.outputStream.write(json.toString().toByteArray(Charsets.UTF_8))
                        ok = conn.responseCode in 200..299
                        conn.disconnect()
                    } catch (e: Exception) { ok = false }
                    // [유실 방지] 전송 실패(오프라인 등) 시 로컬 큐에 저장 → 다음 실행 때 재전송(같은 uuid로 중복 없음).
                    if (!ok) { try { com.callradar.app.LocalTripDatabase.getInstance(this).savePendingExpense(userId, cat, result.amount, "business", memo, litersD, ppl, true, expDate, cuid) } catch (e: Exception) {} }
                    savedOk = ok
                } else {
                    val json = JSONObject().apply {
                        put("user_id", userId)
                        put("type", result.type.name)
                        put("type_name", result.typeName)
                        put("amount", result.amount)
                        put("receipt_date", result.date)
                        put("receipt_time", result.time)
                        put("memo", result.memo)
                        put("liters", result.liters)
                        put("price_per_liter", result.pricePerLiter)
                        put("distance", result.distance)
                        put("duration", result.duration)
                        put("raw_text", result.rawText.take(500))
                    }
                    val url = URL("$SERVER_URL/api/receipts")
                    val conn = url.openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.outputStream.write(json.toString().toByteArray())
                    savedOk = conn.responseCode in 200..299
                    Log.d("CallRadar", "영수증 전송: ${conn.responseCode}")
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.e("CallRadar", "저장 실패: ${e.message}")
            }
            // [v44] 전송 완료까지 기다린 뒤 결과 안내 후 닫기 — 예전엔 전송을 백그라운드로 던지고 즉시 닫아
            //  콜드스타트(30~50s) 시 타임아웃→로컬큐로만 남고 기록에 안 떠 '저장 안 됨'처럼 보였음.
            val isExpense = (preset == "가스" || preset == "전기" || preset == "지출")
            runOnUiThread {
                if (isExpense) {
                    if (result.amount <= 0) android.widget.Toast.makeText(this, "금액을 못 읽었어요 — 기록에서 수정해 주세요", android.widget.Toast.LENGTH_LONG).show()
                    else android.widget.Toast.makeText(this, if (savedOk) "지출 저장됨 ✓" else "오프라인 저장 — 연결되면 자동 전송돼요", android.widget.Toast.LENGTH_SHORT).show()
                }
                val intent = Intent().apply {
                    putExtra("type", result.type.name)
                    putExtra("typeName", result.typeName)
                    putExtra("amount", result.amount)
                    putExtra("time", result.time)
                    putExtra("date", result.date)
                    putExtra("memo", result.memo)
                }
                setResult(Activity.RESULT_OK, intent)
                finish()
            }
        }.start()
    }

    @Composable
    fun ReceiptScanScreen() {
        val bg = Color(0xFF0A0E1A)
        val card = Color(0xFF111827)
        val accent = Color(0xFFF59E0B)
        val green = Color(0xFF10B981)
        val red = Color(0xFFEF4444)
        val muted = Color(0xFF6B7280)

        Column(
            modifier = Modifier.fillMaxSize().background(bg).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("영수증 자동 입력", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(top = 16.dp, bottom = 16.dp))
            Text(statusMessage, fontSize = 14.sp, color = muted, modifier = Modifier.padding(bottom = 16.dp))

            if (isProcessing) {
                CircularProgressIndicator(color = accent, modifier = Modifier.padding(16.dp))
                Text("OCR 분석 중..", color = muted, fontSize = 13.sp)
            }

            scanResult?.let { result ->
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    // 카카오택시 정산 전용 카드
                    if (result.type == ReceiptOcrService.ReceiptType.KAKAO_TAXI) {
                        KakaoTaxiResultCard(result, card, accent, green, red, muted)
                    } else {
                        NormalReceiptCard(result, card, accent, green, red, muted)
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = { saveResult(result) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("저장하기", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp) }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { scanResult = null; statusMessage = "영수증을 촬영해주세요" },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
                    ) { Text("다시 촬영", fontWeight = FontWeight.Bold) }
                }
            }

            if (!isProcessing && scanResult == null) {
                Spacer(Modifier.weight(1f))

                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(this@ReceiptScanActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                            openCamera()
                        else permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("영수증 촬영하기", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp) }

                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
                ) { Text("갤러리에서 선택", fontWeight = FontWeight.Bold, fontSize = 15.sp) }

                Spacer(Modifier.height(20.dp))
            }
        }
    }

    @Composable
    fun KakaoTaxiResultCard(result: ReceiptOcrService.ReceiptResult, card: Color, accent: Color, green: Color, red: Color, muted: Color) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("🚖 카카오택시 정산", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(result.date, fontSize = 12.sp, color = muted)
                }

                Spacer(Modifier.height(16.dp))

                // 총 매출
                Text("총 매출", fontSize = 13.sp, color = muted)
                Text("${result.amount.formatAmount()}원", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = green)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF1F2937))
                Spacer(Modifier.height(12.dp))

                // 콜 건수
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("총 콜 건수", fontSize = 14.sp, color = muted)
                    Text("${result.callCount}건", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent)
                }

                if (result.callCount > 0) {
                    val avgAmount = result.amount / result.callCount
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("콜당 평균", fontSize = 14.sp, color = muted)
                        Text("${avgAmount.formatAmount()}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                // 콜 상세
                if (result.callDetails.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF1F2937))
                    Spacer(Modifier.height(8.dp))
                    Text("콜 상세", fontSize = 13.sp, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                    result.callDetails.forEach { detail ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(detail.time, fontSize = 13.sp, color = Color(0xFF9CA3AF))
                            Text("${detail.amount.formatAmount()}원", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun NormalReceiptCard(result: ReceiptOcrService.ReceiptResult, card: Color, accent: Color, green: Color, red: Color, muted: Color) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(getTypeEmoji(result.type) + " " + result.typeName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(result.date, fontSize = 12.sp, color = muted)
                }
                Spacer(Modifier.height(12.dp))
                Text("${result.amount.formatAmount()}원", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = if (result.type == ReceiptOcrService.ReceiptType.TMONEYGO) green else red)
                Spacer(Modifier.height(8.dp))
                if (result.time.isNotEmpty()) DetailRow("시간", result.time, muted)
                if (result.memo.isNotEmpty() && result.memo != "확인필요") DetailRow("내용", result.memo, muted)
                if (result.liters > 0) DetailRow("충전량", "${result.liters}L", muted)
                if (result.pricePerLiter > 0) DetailRow("단가", "${result.pricePerLiter}원/L", muted)
            }
        }
    }

    @Composable
    fun DetailRow(label: String, value: String, muted: Color) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, color = muted)
            Text(value, fontSize = 13.sp, color = Color(0xFFE5E7EB))
        }
    }

    private fun getTypeEmoji(type: ReceiptOcrService.ReceiptType): String {
        return when (type) {
            ReceiptOcrService.ReceiptType.KAKAO_TAXI -> "🚖"
            ReceiptOcrService.ReceiptType.TMONEYGO  -> "🚖"
            ReceiptOcrService.ReceiptType.LPG       -> "⛽"
            ReceiptOcrService.ReceiptType.HIPASS    -> "🛣️"
            ReceiptOcrService.ReceiptType.FOOD      -> "🍱"
            ReceiptOcrService.ReceiptType.REPAIR    -> "🔧"
            ReceiptOcrService.ReceiptType.WASH      -> "🚿"
            ReceiptOcrService.ReceiptType.PARKING   -> "🅿️"
            ReceiptOcrService.ReceiptType.UNKNOWN   -> "📄"
        }
    }

    private fun Int.formatAmount(): String = String.format("%,d", this)
}