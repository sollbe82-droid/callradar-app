package com.callradar.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callradar.app.ui.theme.CallRadarTheme
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * [위치정보법 동의] 위치정보 수집 전 필수 동의 게이트.
 *  - 위치기반서비스 이용약관 + 개인정보 수집·이용 + 서비스 이용약관에 명시 동의를 받고,
 *    동의 버전·시각을 로컬 저장 + 서버 기록(확인자료 보관 의무).
 *  - 약관 개정 시 CONSENT_VERSION을 올리면 전원 재동의.
 *  - 필수 거부 시 서비스 이용 불가 → 종료.
 */
class ConsentActivity : ComponentActivity() {

    private val SERVER_URL = "https://callradar-server.onrender.com"

    companion object {
        const val CONSENT_VERSION = "2026-08-15"   // 약관/처리방침 개정일. 바뀌면 재동의 유도.
        fun needed(ctx: Context): Boolean =
            ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                .getString("consent_version", "") != CONSENT_VERSION
    }

    private val bg = Color(0xFF0A0E1A); private val card = Color(0xFF111827)
    private val accent = Color(0xFFF59E0B); private val muted = Color(0xFF9CA3AF); private val line = Color(0xFF232C3B)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CallRadarTheme { Screen() } }
    }

    private fun openUrl(path: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$SERVER_URL/$path")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
    }

    private fun agree() {
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("consent_version", CONSENT_VERSION).putLong("consent_at", System.currentTimeMillis()).apply()
        // [확인자료 보관] 동의 사실을 서버에 기록 (best-effort, 실패해도 진행)
        Thread {
            try {
                val uid = prefs.getString("user_id", "") ?: ""
                val json = JSONObject().apply { put("user_id", uid); put("version", CONSENT_VERSION); put("items", "location,privacy,tos") }
                val conn = (URL("$SERVER_URL/api/consent").openConnection().apply { com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") } } as HttpURLConnection).apply {
                    requestMethod = "POST"; setRequestProperty("Content-Type", "application/json")
                    doOutput = true; connectTimeout = 8000; readTimeout = 8000
                }
                conn.outputStream.write(json.toString().toByteArray()); conn.responseCode; conn.disconnect()
            } catch (e: Exception) {}
        }.start()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun decline() {
        android.widget.Toast.makeText(this, "필수 항목에 동의해야 콜레이더를 이용할 수 있어요.", android.widget.Toast.LENGTH_LONG).show()
        finishAffinity()
    }

    @Composable
    private fun Screen() {
        var cLoc by remember { mutableStateOf(false) }
        var cPriv by remember { mutableStateOf(false) }
        var cTos by remember { mutableStateOf(false) }
        val all = cLoc && cPriv && cTos
        Column(Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(48.dp))
            Text("콜레이더 이용 동의", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Text("콜레이더는 운행 기록·매출 통계·콜 수요 레이더 제공을 위해 위치정보(정밀 좌표)를 수집·이용·저장합니다. 서비스 이용을 위해 아래 필수 항목에 동의해 주세요.",
                fontSize = 13.sp, color = muted, lineHeight = 20.sp)
            Spacer(Modifier.height(22.dp))

            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    AgreeRow("전체 동의", all, bold = true, linkPath = null) { on -> cLoc = on; cPriv = on; cTos = on }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(line))
                    AgreeRow("[필수] 위치기반서비스 이용약관", cLoc, linkPath = "location-terms.html") { cLoc = it }
                    AgreeRow("[필수] 개인정보 수집·이용 동의", cPriv, linkPath = "privacy-policy.html") { cPriv = it }
                    AgreeRow("[필수] 서비스 이용약관", cTos, linkPath = "terms-of-service.html") { cTos = it }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("· 수집 항목: 실시간 GPS 위치(위도·경도), 운행 경로(궤적) 좌표\n· 목적: 출발지·목적지 자동 기록, 매출 통계, 콜 수요·핫존 분석(익명 집계)\n· 보유: 회원 탈퇴 시까지(탈퇴 후 30일 내 파기)",
                fontSize = 11.sp, color = muted, lineHeight = 18.sp)

            Spacer(Modifier.height(24.dp))
            Button(onClick = { agree() }, enabled = all, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent, disabledContainerColor = card)) {
                Text("동의하고 시작", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (all) Color.Black else muted)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { decline() }, modifier = Modifier.fillMaxWidth()) { Text("동의하지 않고 종료", fontSize = 13.sp, color = muted) }
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun AgreeRow(label: String, checked: Boolean, bold: Boolean = false, linkPath: String?, onCheck: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().clickable { onCheck(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheck, colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = muted))
            Text(label, fontSize = if (bold) 15.sp else 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = Color.White, modifier = Modifier.weight(1f))
            if (linkPath != null) Text("보기", fontSize = 13.sp, color = accent, modifier = Modifier.clickable { openUrl(linkPath) }.padding(8.dp))
        }
    }
}
