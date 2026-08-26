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
 * [v94] 자동기록(접근성 서비스) 명시적 공개·동의 화면.
 *
 * 왜 별도 화면인가 — 구글 Play 정책 요건을 그대로 옮긴 것이다:
 *   · 앱 설명·웹사이트가 아니라 **앱 자체**에 있어야 한다
 *   · 메뉴·설정으로 일부러 들어가지 않아도 **일반 사용 과정에서** 보여야 한다
 *     → 자동기록을 켜는 순간 반드시 이 화면을 거친다
 *   · AccessibilityService API로 **무엇을 접근·수집하는지** 설명해야 한다
 *   · 그 데이터를 **어떻게 쓰고 공유하는지** 설명해야 한다
 *   · 사용자가 **확실한 동의 의사**를 표현하게 해야 한다(체크 + 버튼)
 *   · **개인정보처리방침·약관에만 있으면 안 된다**
 *   · **다른 개인정보 공개와 섞으면 안 된다** → 위치정보 동의(ConsentActivity)와 분리
 *
 * 내용은 전부 실제 코드 기준이다(NaviIntentReceiver). 지어내면 그게 '명시되지 않은 용도'가 되어
 * 앱 정지·계정 해지 사유가 된다. 코드가 바뀌면 이 화면도 같이 바꿔야 한다.
 */
class AutoRecordConsentActivity : ComponentActivity() {

    companion object {
        /** 공개 내용이 바뀌면 올린다 → 전원 재동의 */
        const val VERSION = "2026-08-26"
        private const val KEY = "auto_consent_version"

        fun agreed(ctx: Context): Boolean =
            ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                .getString(KEY, "") == VERSION

        /** 자동기록을 켜기 전에 부른다. 이미 동의했으면 false. */
        fun needed(ctx: Context): Boolean = !agreed(ctx)

        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, AutoRecordConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val SERVER_URL = "https://callradar-server.onrender.com"
    private val bg = Color(0xFF0A0E1A); private val card = Color(0xFF111827)
    private val accent = Color(0xFFF59E0B); private val muted = Color(0xFF9CA3AF)
    private val green = Color(0xFF10B981); private val red = Color(0xFFEF4444)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CallRadarTheme { Screen() } }
    }

    private fun openTerms() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("$SERVER_URL/auto-record-terms.html")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {}
    }

    private fun agree() {
        val p = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        p.edit()
            .putString("auto_consent_version", VERSION)
            .putLong("auto_consent_at", System.currentTimeMillis())
            .putBoolean("auto_record_on", true)
            .putBoolean("auto_record_touched", true)
            .apply()
        // 동의 사실을 서버에도 남긴다(확인자료). 실패해도 진행 — 동의는 기기에 이미 기록됐다.
        Thread {
            try {
                val uid = p.getString("user_id", "") ?: ""
                val j = JSONObject().apply {
                    put("user_id", uid); put("version", VERSION); put("items", "accessibility_auto_record")
                }
                val conn = (URL("$SERVER_URL/api/consent").openConnection().apply {
                    Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
                } as HttpURLConnection).apply {
                    requestMethod = "POST"; setRequestProperty("Content-Type", "application/json")
                    doOutput = true; connectTimeout = 8000; readTimeout = 8000
                }
                conn.outputStream.write(j.toString().toByteArray()); conn.responseCode; conn.disconnect()
            } catch (e: Exception) {}
        }.start()
        try { Telemetry.log(this, "auto_consent_agree", "auto_consent") } catch (e: Exception) {}
        setResult(RESULT_OK)
        finish()
    }

    /** 거부해도 앱은 그대로 쓴다. 자동기록만 꺼진 채로 둔다 — 거부가 불이익이 되면 '자유로운 동의'가 아니다. */
    private fun decline() {
        getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("auto_record_on", false)
            .putBoolean("auto_record_touched", true)
            .apply()
        try { Telemetry.log(this, "auto_consent_decline", "auto_consent") } catch (e: Exception) {}
        android.widget.Toast.makeText(this,
            "자동 기록을 켜지 않았어요. 운행 기록 버튼으로 직접 기록할 수 있습니다.",
            android.widget.Toast.LENGTH_LONG).show()
        setResult(RESULT_CANCELED)
        finish()
    }

    @Composable
    private fun Screen() {
        var agreedCheck by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)) {

            Spacer(Modifier.height(44.dp))
            Text("자동 기록 사용 안내", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text("이 기능은 안드로이드 접근성 서비스(AccessibilityService)를 사용합니다. " +
                "켜기 전에 무엇을 읽고, 무엇을 저장하며, 무엇을 하지 않는지 알려드립니다.",
                fontSize = 13.sp, color = muted, lineHeight = 20.sp)

            Spacer(Modifier.height(20.dp))
            Section("1. 무엇을 하는 기능인가요")
            Body("운행을 시작하고 끝낼 때마다 손으로 적지 않아도 되도록, 택시 호출 앱 화면에 표시되는 " +
                "운행 정보를 읽어 운행일지를 자동으로 작성합니다.")

            Spacer(Modifier.height(18.dp))
            Section("2. 어떤 앱의 화면을 읽나요")
            Body("아래 네 개 앱만 읽습니다. 이 목록은 앱에 고정되어 있어, 다른 앱의 화면은 기술적으로 읽을 수 없습니다.")
            Bullet("카카오 T 드라이버")
            Bullet("우버 드라이버")
            Bullet("티머니GO")
            Bullet("티머니GO 내비")

            Spacer(Modifier.height(18.dp))
            Section("3. 무엇을 읽고 저장하나요")
            Body("위 앱 화면에 이미 표시되어 있는 글자 중, 운행 기록에 필요한 값만 읽습니다.")
            Bullet("요금 (미터 요금·결제 금액)")
            Bullet("통행료")
            Bullet("운행 시작·손님 탑승·운행 완료를 알리는 화면 문구")
            Bullet("호출 플랫폼 이름 (카카오T / 우버 / 티머니고)")
            Bullet("위 신호가 발생한 시각")
            Spacer(Modifier.height(6.dp))
            Body("읽은 값은 회원님의 운행 기록으로 콜레이더 서버에 저장됩니다. " +
                "출발지·목적지는 화면이 아니라 단말기 GPS 좌표를 동/구 단위 주소로 바꿔 기록합니다.")

            Spacer(Modifier.height(18.dp))
            Section("4. 하지 않는 것")
            NoBullet("화면을 캡처하거나 이미지로 저장하지 않습니다")
            NoBullet("위 네 개 앱 외의 다른 앱은 읽지 않습니다")
            NoBullet("비밀번호·문자메시지·연락처·사진을 읽지 않습니다")
            NoBullet("손님의 이름·전화번호 등 개인정보를 저장하지 않습니다")
            NoBullet("회원님을 대신해 앱을 조작하거나 무엇을 누르지 않습니다")

            Spacer(Modifier.height(18.dp))
            Section("5. 어떻게 사용하나요")
            Bullet("회원님 본인의 운행일지·매출 통계·실차율 계산")
            Bullet("개인 식별자를 제거하고 동/구 단위로 집계한 지역별 콜 수요 통계")

            Spacer(Modifier.height(18.dp))
            Section("6. 누구와 공유하나요")
            Body("제3자에게 제공하거나 판매하지 않습니다. 광고 목적으로 사용하지 않습니다. " +
                "법률에 특별한 규정이 있거나 수사기관의 적법한 요청이 있는 경우에만 예외로 합니다.")

            Spacer(Modifier.height(18.dp))
            Section("7. 언제든 끌 수 있습니다")
            Bullet("콜레이더 앱에서 '자동 기록' 스위치를 끄면 즉시 중단됩니다")
            Bullet("휴대폰 설정 → 접근성 → 콜레이더 자동기록 에서 해제할 수 있습니다")
            Bullet("끄더라도 이미 기록된 운행은 그대로 남으며, 기록 화면에서 직접 삭제할 수 있습니다")

            Spacer(Modifier.height(22.dp))
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { agreedCheck = !agreedCheck },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = agreedCheck, onCheckedChange = { agreedCheck = it },
                            colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = muted))
                        Text("위 내용을 확인했으며, 자동 기록을 위해 접근성 서비스를 사용하는 것에 동의합니다.",
                            fontSize = 13.sp, color = Color.White, lineHeight = 19.sp,
                            modifier = Modifier.weight(1f).padding(start = 2.dp))
                    }
                    Text("자동 기록 이용약관 전문 보기", fontSize = 12.sp, color = accent,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp).clickable { openTerms() })
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = { agree() }, enabled = agreedCheck,
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent, disabledContainerColor = card)) {
                Text("동의하고 자동 기록 켜기", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = if (agreedCheck) Color.Black else muted)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { decline() }, modifier = Modifier.fillMaxWidth()) {
                Text("사용하지 않기", fontSize = 13.sp, color = muted)
            }
            Spacer(Modifier.height(6.dp))
            Text("동의하지 않아도 콜레이더의 다른 기능은 그대로 사용할 수 있습니다.",
                fontSize = 11.sp, color = muted, modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp))
        }
    }

    @Composable private fun Section(t: String) {
        Text(t, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = accent)
        Spacer(Modifier.height(6.dp))
    }

    @Composable private fun Body(t: String) {
        Text(t, fontSize = 13.sp, color = Color(0xFFD1D5DB), lineHeight = 20.sp)
    }

    @Composable private fun Bullet(t: String) {
        Row(Modifier.padding(top = 4.dp)) {
            Text("•", fontSize = 13.sp, color = green, modifier = Modifier.padding(end = 8.dp))
            Text(t, fontSize = 13.sp, color = Color(0xFFD1D5DB), lineHeight = 20.sp)
        }
    }

    @Composable private fun NoBullet(t: String) {
        Row(Modifier.padding(top = 4.dp)) {
            Text("✕", fontSize = 12.sp, color = red, modifier = Modifier.padding(end = 8.dp))
            Text(t, fontSize = 13.sp, color = Color(0xFFD1D5DB), lineHeight = 20.sp)
        }
    }
}
