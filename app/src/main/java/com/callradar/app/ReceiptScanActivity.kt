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
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, it)
            processReceipt(bitmap)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openCamera()
        else Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CallRadarTheme {
                ReceiptScanScreen()
            }
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
            try {
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
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.outputStream.write(json.toString().toByteArray())
                Log.d("CallRadar", "영수증 전송: ${conn.responseCode}")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("CallRadar", "영수증 전송 실패: ${e.message}")
            }
        }.start()

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