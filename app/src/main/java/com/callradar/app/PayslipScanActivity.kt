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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * [명세서 스펙 v31+] 급여명세서 촬영 → 전 항목 OCR → 명세서 그대로 표시 + 역산 → /api/payslip 업로드.
 * 역산식: 차인지급액 = 발생금액 − 공제계 + 부가세경감액 (server/payslip.js 와 동일, PayslipOcr 온디바이스 계산).
 */
class PayslipScanActivity : ComponentActivity() {

    private val SERVER_URL = "https://callradar-server.onrender.com"
    private val ocr = PayslipOcr()
    private var result by mutableStateOf<PayslipOcr.Result?>(null)
    private var isProcessing by mutableStateOf(false)
    private var statusMessage by mutableStateOf("급여명세서를 촬영하세요")
    private var saveMessage by mutableStateOf("")

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, PayslipScanActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            (r.data?.extras?.get("data") as? Bitmap)?.let { runOcr(it) }
        }
    }
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            // [크래시 방지] 큰 사진 메인스레드 통짜 디코드 → OOM/ANR. 백그라운드 다운샘플 디코드.
            Thread {
                try {
                    val bmp = decodeDownsampledBitmap(it, 1600)
                    runOnUiThread { if (bmp != null) runOcr(bmp) else android.widget.Toast.makeText(this, "이미지를 불러오지 못했어요", android.widget.Toast.LENGTH_SHORT).show() }
                } catch (e: Throwable) { runOnUiThread { android.widget.Toast.makeText(this, "이미지가 너무 커요 · 다른 사진으로 시도해 주세요", android.widget.Toast.LENGTH_SHORT).show() } }
            }.start()
        }
    }

    private fun decodeDownsampledBitmap(uri: android.net.Uri, maxDim: Int): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1; while (longEdge / sample > maxDim) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CallRadarTheme { PayslipScanScreen() } }
    }

    private fun openCamera() = cameraLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE))

    private fun runOcr(bitmap: Bitmap) {
        isProcessing = true; statusMessage = "명세서 분석 중.."; result = null; saveMessage = ""
        ocr.process(bitmap) { r ->
            runOnUiThread {
                isProcessing = false
                if (r != null && r.uploadItems.isNotEmpty()) {
                    result = r
                    statusMessage = if (r.matched) "분석 완료 — 역산 일치 ✅" else "분석 완료 (숫자 확인 권장)"
                } else {
                    statusMessage = "인식 실패 — 밝은 곳에서 반듯하게 다시 촬영하세요"
                }
            }
        }
    }

    private fun upload(r: PayslipOcr.Result) {
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", null)
        if (userId.isNullOrBlank()) { saveMessage = "로그인이 필요합니다"; return }
        saveMessage = "저장 중.."
        Thread {
            try {
                val items = JSONArray()
                for ((label, amount) in r.uploadItems) items.put(JSONObject().apply { put("label", label); put("amount", amount) })
                val body = JSONObject().apply {
                    put("user_id", userId)
                    if (r.company.isNotEmpty()) put("company", r.company)
                    if (r.yearMonth.isNotEmpty()) put("year_month", r.yearMonth)
                    put("items", items)
                    if (r.takeHome > 0) put("take_home", r.takeHome)
                    put("raw_text", r.rawText.take(4000))
                }
                val conn = (URL("$SERVER_URL/api/payslip").openConnection().apply {
                    com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
                } as HttpURLConnection).apply {
                    requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true; connectTimeout = 10000; readTimeout = 10000
                }
                conn.outputStream.write(body.toString().toByteArray(Charsets.UTF_8))
                val code = conn.responseCode
                Log.d("CallRadar", "명세서 업로드: $code")
                conn.disconnect()
                runOnUiThread {
                    saveMessage = if (code in 200..299) "저장 완료 — 명세서가 쌓일수록 예상급여가 정확해져요" else "저장 실패 ($code) — 잠시 후 다시 시도"
                    if (code in 200..299) {
                        val res = Intent().apply {
                            putExtra("company", r.company); putExtra("year_month", r.yearMonth)
                            putExtra("take_home", if (r.takeHome > 0) r.takeHome else r.computedTakeHome)
                        }
                        setResult(Activity.RESULT_OK, res)
                    }
                }
            } catch (e: Exception) {
                Log.e("CallRadar", "명세서 업로드 실패: ${e.message}")
                runOnUiThread { saveMessage = "저장 실패 — 네트워크 확인 (서버가 깨어나는 중일 수 있어요)" }
            }
        }.start()
    }

    /** 스캔 결과의 회사·근무형태·기본급으로 회사 프로필 upsert + 활성화 → 프로필 화면으로. */
    private fun saveToProfile(r: PayslipOcr.Result) {
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val baseSalary = r.items.firstOrNull { it.group == "earning" && it.label.replace(" ", "").contains("기본급") }?.amount ?: 0
        val key = "${r.company.trim()}|${r.workType.trim()}"
        val existing = CompanyProfile.all(prefs).firstOrNull { it.key() == key }
        val profile = CompanyProfile(
            company = r.company,
            workType = r.workType,
            sanapDaily = existing?.sanapDaily ?: 0,
            gasBearer = existing?.gasBearer ?: "기사",
            overRate = existing?.overRate ?: 1.0,
            baseSalary = if (baseSalary > 0) baseSalary else (existing?.baseSalary ?: 0)
        )
        CompanyProfile.upsert(prefs, profile)
        CompanyProfile.setActive(prefs, profile.key())
        CompanyProfileActivity.start(this)
    }

    // ── UI ──
    private val bg = Color(0xFF0A0E1A); private val card = Color(0xFF111827)
    private val accent = Color(0xFFF59E0B); private val green = Color(0xFF10B981)
    private val red = Color(0xFFEF4444); private val blue = Color(0xFF3B82F6); private val muted = Color(0xFF6B7280)

    @Composable
    fun PayslipScanScreen() {
        Column(modifier = Modifier.fillMaxSize().background(bg).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("급여명세서 스캔", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            Text(statusMessage, fontSize = 13.sp, color = muted, modifier = Modifier.padding(bottom = 12.dp))

            if (isProcessing) {
                CircularProgressIndicator(color = accent, modifier = Modifier.padding(16.dp))
                Text("전 항목 인식 중..", color = muted, fontSize = 13.sp)
            }

            result?.let { r ->
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    ReverseCalcCard(r)
                    Spacer(Modifier.height(10.dp))
                    ItemsCard(r)
                    if (saveMessage.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(saveMessage, fontSize = 12.sp, color = if (saveMessage.startsWith("저장 완료")) green else if (saveMessage.startsWith("저장 중")) muted else red)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { upload(r) }, modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(12.dp)) {
                        Text("저장하기", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { saveToProfile(r) }, modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = green)) {
                        Text("🏢 이 회사 프로필로 저장 → 예상급여", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { result = null; saveMessage = ""; statusMessage = "급여명세서를 촬영하세요" },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text("다시 촬영", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(16.dp))
                }
            }

            if (!isProcessing && result == null) {
                Spacer(Modifier.weight(1f))
                Text("명세서 전체가 보이게, 밝은 곳에서 반듯하게 찍어주세요", fontSize = 12.sp, color = muted, modifier = Modifier.padding(bottom = 14.dp))
                Button(onClick = {
                    if (ContextCompat.checkSelfPermission(this@PayslipScanActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
                    else permissionLauncher.launch(Manifest.permission.CAMERA)
                }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(14.dp)) {
                    Text("명세서 촬영하기", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) {
                    Text("갤러리에서 선택", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    @Composable
    private fun ReverseCalcCard(r: PayslipOcr.Result) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("📄 " + (r.company.ifEmpty { "급여명세서" }) + (if (r.workType.isNotEmpty()) " · ${r.workType}" else ""), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(r.yearMonth, fontSize = 12.sp, color = muted)
                }
                Spacer(Modifier.height(14.dp))
                Text("역산 실수령(차인지급액)", fontSize = 13.sp, color = muted)
                Text("${r.computedTakeHome.won()}원", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = green)
                Spacer(Modifier.height(10.dp)); HorizontalDivider(color = Color(0xFF1F2937)); Spacer(Modifier.height(10.dp))
                Calc("발생금액", r.earning, Color.White)
                Calc("− 공제계", r.deduction, red)
                Calc("+ 부가세경감", r.vatRelief, blue)
                if (r.takeHome > 0) {
                    Spacer(Modifier.height(8.dp)); HorizontalDivider(color = Color(0xFF1F2937)); Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("명세서 인쇄값", fontSize = 13.sp, color = muted)
                        Text("${r.takeHome.won()}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(if (r.matched) "✅ 역산 == 명세서 (검증 통과)" else "⚠ 차이 ${(r.computedTakeHome - r.takeHome).won()}원 — 일부 항목 미인식일 수 있어요",
                        fontSize = 12.sp, color = if (r.matched) green else accent)
                }
                Spacer(Modifier.height(6.dp))
                Text("차인지급액 = 발생금액 − 공제계 + 부가세경감액", fontSize = 10.sp, color = muted)
            }
        }
    }

    @Composable
    private fun Calc(label: String, value: Int, color: Color) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 14.sp, color = muted)
            Text("${value.won()}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }

    @Composable
    private fun ItemsCard(r: PayslipOcr.Result) {
        val groups = listOf(
            "earning" to "지급 항목", "deduction" to "공제 항목", "vat_relief" to "부가세경감", "info" to "참고(총입금)", "unknown" to "미분류(확인 필요)"
        )
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("명세서 전 항목 (${r.items.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                var any = false
                for ((g, title) in groups) {
                    val rows = r.items.filter { it.group == g }
                    if (rows.isEmpty()) continue
                    any = true
                    Text(title, fontSize = 12.sp, color = accent, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    rows.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.label, fontSize = 13.sp, color = Color(0xFF9CA3AF))
                            Text("${item.amount.won()}원", fontSize = 13.sp, color = if (g == "unknown") accent else Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (!any) Text("항목을 못 읽었어요. 다시 촬영해 주세요.", fontSize = 12.sp, color = muted)
            }
        }
    }

    private fun Int.won(): String = String.format("%,d", this)
}
