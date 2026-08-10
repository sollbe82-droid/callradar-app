package com.callradar.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.callradar.app.ui.theme.CallRadarTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// 미터기 "당일상세거래내역" 영수증 한 장으로 하루 매출을 앱과 자동 대조·보정하는 정산 기능.
//   촬영/갤러리 → ML Kit OCR → 포맷 전용 파서 → 서버 /api/receipt/reconcile → 요약 → 반영.
class ReceiptReconcileActivity : ComponentActivity() {

    private val SERVER_URL = "https://callradar-server.onrender.com"
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    private var status by mutableStateOf("미터기 '당일상세거래내역' 영수증을 촬영하세요")
    private var busy by mutableStateOf(false)
    private var plan by mutableStateOf<JSONObject?>(null)     // reconcile 미리보기 결과
    private var parsedDate by mutableStateOf<String?>(null)
    private var parsedTotal by mutableStateOf(0)
    private var parsedTxns by mutableStateOf<JSONArray?>(null)

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ReceiptReconcileActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION") val bmp = r.data?.extras?.get("data") as? Bitmap
            if (bmp != null) runOcr(bmp)
        }
    }
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            Thread {
                try { val bmp = decodeDownsampled(it, 2200); runOnUiThread { if (bmp != null) runOcr(bmp) else toast("이미지를 불러오지 못했어요") } }
                catch (e: Throwable) { runOnUiThread { toast("이미지가 너무 커요 · 다른 사진으로") } }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CallRadarTheme { Screen() } }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun decodeDownsampled(uri: android.net.Uri, maxDim: Int): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1; while (longEdge / sample > maxDim) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun runOcr(bitmap: Bitmap) {
        busy = true; status = "영수증 분석 중.."; plan = null
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { vt -> parseAndReconcile(vt.text) }
            .addOnFailureListener { busy = false; status = "인식 실패 — 다시 촬영하세요" }
    }

    // ── 파서: 당일상세거래내역 → (섹션/플랫폼, 콜ID, 시각, 금액) 트랜잭션 목록 ──
    private fun parseReceipt(raw: String): Triple<String?, Int, JSONArray> {
        val lines = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        var date: String? = null
        var total = 0
        val txns = JSONArray()
        var section = ""   // 현재 섹션(플랫폼)

        val reTime = Regex("(\\d{1,2}):(\\d{2})")
        val reCallId = Regex("(?<!\\d)(\\d{10,14})(?!\\d)")           // 콤마 없는 긴 숫자 = 콜ID/카드번호
        val reAmount = Regex("(-?\\d{1,3}(?:,\\d{3})+|-?\\d{3,6})\\s*원")   // 콤마금액 또는 원 붙은 숫자
        val reDate = Regex("(20\\d{2})[./-](\\d{1,2})[./-](\\d{1,2})")

        for (ln in lines) {
            // 날짜(집계시간)
            if (date == null) reDate.find(ln)?.let { m -> date = "%04d-%02d-%02d".format(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
            // 총합계
            if (ln.contains("총합계") || ln.contains("총 합계") || ln.contains("합계")) {
                reAmount.find(ln)?.let { total = maxOf(total, it.groupValues[1].replace(",", "").toIntOrNull() ?: 0) }
                if (!ln.contains("총")) { /* 섹션 소계 '합계'는 무시 */ }
            }
            // 섹션 전환(헤더 키워드)
            when {
                ln.contains("교통카드") -> section = "교통카드"
                ln.contains("신용카드") -> section = "신용카드"
                ln.contains("카카오") -> section = "카카오택시"
                ln.contains("티머니") || ln.contains("londa", true) -> section = "티머니"
                ln.contains("우버") || ln.contains("uber", true) -> section = "우버"
            }
            // 상세행 = 시각이 있는 줄만 트랜잭션으로
            val tm = reTime.find(ln) ?: continue
            val amtM = reAmount.find(ln) ?: continue
            val amount = amtM.groupValues[1].replace(",", "").toIntOrNull() ?: continue
            if (amount == 0) continue
            // 시각으로 섹션소계/집계시간 줄 배제(집계시간엔 '~' 범위, 금액 없음이라 이미 걸러짐)
            val callId = reCallId.find(ln.replace(amtM.value, ""))?.groupValues?.get(1) ?: ""
            txns.put(JSONObject().apply {
                put("section", section)
                put("platform", section)
                put("call_id", callId)
                put("time", "%02d:%02d".format(tm.groupValues[1].toInt(), tm.groupValues[2].toInt()))
                put("amount", amount)
            })
        }
        return Triple(date, total, txns)
    }

    private fun parseAndReconcile(raw: String) {
        val (date, total, txns) = parseReceipt(raw)
        parsedDate = date; parsedTotal = total; parsedTxns = txns
        if (txns.length() == 0) { busy = false; status = "거래내역을 못 읽었어요 — 영수증 전체가 선명하게 나오게 다시 촬영하세요"; return }
        status = "대조 중.. (${txns.length()}건 인식)"
        val userId = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("user_id", null)
        Thread {
            try {
                val body = JSONObject().apply {
                    put("user_id", userId); date?.let { put("date", it) }; put("receipt_total", total)
                    put("transactions", txns); put("apply", false)
                }
                val resp = postJson("$SERVER_URL/api/receipt/reconcile", body)
                runOnUiThread { busy = false; if (resp != null && resp.optBoolean("ok")) { plan = resp; status = "대조 완료" } else status = "서버 대조 실패 — 다시 시도" }
            } catch (e: Exception) { runOnUiThread { busy = false; status = "네트워크 오류: ${e.message}" } }
        }.start()
    }

    private fun applyPlan(deleteGhosts: Boolean) {
        busy = true; status = "반영 중.."
        val userId = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("user_id", null)
        val txns = parsedTxns ?: return
        Thread {
            try {
                val body = JSONObject().apply {
                    put("user_id", userId); parsedDate?.let { put("date", it) }; put("receipt_total", parsedTotal)
                    put("transactions", txns); put("apply", true); put("delete_ghosts", deleteGhosts)
                }
                val resp = postJson("$SERVER_URL/api/receipt/reconcile", body)
                runOnUiThread {
                    busy = false
                    val a = resp?.optJSONObject("applied")
                    if (a != null) { status = "반영 완료 · 채움 ${a.optInt("filled")} · 교정 ${a.optInt("fixed")} · 추가 ${a.optInt("created")} · 유령삭제 ${a.optInt("ghosted")}"; plan = null; toast("정산 반영됐어요") }
                    else status = "반영 실패 — 다시 시도"
                }
            } catch (e: Exception) { runOnUiThread { busy = false; status = "네트워크 오류: ${e.message}" } }
        }.start()
    }

    private fun postJson(url: String, body: JSONObject): JSONObject? {
        val conn = (URL(url).openConnection().apply {
            com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
        } as HttpURLConnection).apply {
            requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doOutput = true; connectTimeout = 20000; readTimeout = 40000
        }
        conn.outputStream.write(body.toString().toByteArray(Charsets.UTF_8))
        val code = conn.responseCode
        val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        return try { JSONObject(txt) } catch (e: Exception) { null }
    }

    @Composable
    private fun Screen() {
        val bg = Color(0xFF0E1524); val card = Color(0xFF16203A); val accent = Color(0xFF5DCAA5)
        val muted = Color(0xFF9aa6b6)
        Surface(color = bg, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("🧾 매출 영수증 자동정산", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text("미터기에서 '당일상세거래내역'을 출력·촬영하면 앱 기록과 대조해 빠진 금액을 채우고 틀린 걸 고쳐줍니다.", fontSize = 13.sp, color = muted)
                Spacer(Modifier.height(16.dp))
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent) ; Spacer(Modifier.height(10.dp)) }
                Text(status, fontSize = 14.sp, color = accent)
                Spacer(Modifier.height(16.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { cameraLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE)) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("📷 촬영") }
                    OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("🖼 갤러리", color = accent) }
                }

                plan?.let { p ->
                    Spacer(Modifier.height(20.dp))
                    val counts = p.optJSONObject("counts")
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            val rt = p.optInt("receiptTotal"); val at = p.optInt("appTotal"); val df = p.optInt("diff")
                            Text("영수증 총합 ${"%,d".format(rt)}원", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("앱 현재 합계 ${"%,d".format(at)}원  (차이 ${"%,d".format(df)}원)", fontSize = 13.sp, color = if (df == 0) accent else Color(0xFFF6C453))
                            Spacer(Modifier.height(10.dp))
                            Text("• 빠진 금액 채움: ${counts?.optInt("fill") ?: 0}건", fontSize = 14.sp, color = Color.White)
                            Text("• 금액 교정: ${counts?.optInt("fix") ?: 0}건", fontSize = 14.sp, color = Color.White)
                            Text("• 앱에 없어 추가: ${counts?.optInt("missing") ?: 0}건", fontSize = 14.sp, color = Color.White)
                            Text("• 유령(0원) 정리후보: ${counts?.optInt("ghost") ?: 0}건", fontSize = 14.sp, color = muted)
                            Text("• 이미 일치: ${counts?.optInt("matched") ?: 0}건", fontSize = 13.sp, color = muted)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { applyPlan(false) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("이대로 반영 (유령은 남김)") }
                    if ((counts?.optInt("ghost") ?: 0) > 0) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { applyPlan(true) }, modifier = Modifier.fillMaxWidth()) { Text("반영 + 유령 0원 삭제", color = Color(0xFFEF4444)) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("반영 전 기록탭에서 검토할 수 있어요. 현금(길빵)은 영수증에 없어도 지우지 않습니다.", fontSize = 11.sp, color = muted)
                }
            }
        }
    }
}
