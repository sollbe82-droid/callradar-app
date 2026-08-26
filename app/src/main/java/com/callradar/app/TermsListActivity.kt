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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callradar.app.ui.theme.CallRadarTheme

/**
 * [v94] 이용약관 모음 — 더보기에서 들어오는 상시 열람 화면.
 *
 * 왜 필요한가:
 *  · 그동안 약관은 최초 동의 화면(ConsentActivity)에서만 볼 수 있었다. 한 번 동의하고 나면
 *    다시 찾아볼 방법이 없었다. 기사가 "내 위치를 어디까지 가져가나" 궁금해도 확인할 데가 없었다.
 *  · 구글 Play·원스토어 모두 앱 내에서 약관·처리방침에 상시 접근할 수 있기를 요구한다.
 *  · 자동기록(접근성) 공개는 정책상 다른 동의와 섞으면 안 되므로 목록에서도 따로 구분해 둔다.
 */
class TermsListActivity : ComponentActivity() {

    companion object {
        private const val SERVER_URL = "https://callradar-server.onrender.com"
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, TermsListActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val bg = Color(0xFF0A0E1A); private val card = Color(0xFF111827)
    private val accent = Color(0xFFF59E0B); private val muted = Color(0xFF9CA3AF)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CallRadarTheme { Screen() } }
    }

    private fun open(path: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$SERVER_URL/$path"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "브라우저를 열 수 없어요", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    private fun Screen() {
        val p = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val consentAt = p.getLong("consent_at", 0L)
        val autoAt = p.getLong("auto_consent_at", 0L)
        val fmt = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.KOREA)
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul") }

        Column(Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(top = 44.dp, start = 14.dp, end = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("‹", fontSize = 30.sp, color = Color.White,
                    modifier = Modifier.clickable { finish() }.padding(horizontal = 10.dp))
                Text("이용약관", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                Item("서비스 이용약관", "terms-of-service.html")
                Item("개인정보 처리방침", "privacy-policy.html")
                Item(
                    "위치기반 서비스 이용약관", "location-terms.html",
                    if (consentAt > 0) "동의 ${fmt.format(java.util.Date(consentAt))}" else null
                )
                Spacer(Modifier.height(12.dp))

                // 접근성 공개는 다른 동의와 섞지 않는다(구글 정책). 목록에서도 구분해 둔다.
                Text("자동 기록", fontSize = 12.sp, color = muted,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                Item(
                    "자동 기록(접근성) 이용약관", "auto-record-terms.html",
                    if (autoAt > 0) "동의 ${fmt.format(java.util.Date(autoAt))}" else "미동의"
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
                    Text(
                        "자동 기록이 어떤 앱의 무엇을 읽는지 다시 보려면 아래를 누르세요.",
                        fontSize = 11.sp, color = muted, modifier = Modifier.weight(1f)
                    )
                }
                Text("자동 기록 사용 안내 다시 보기", fontSize = 13.sp, color = accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                        .clickable { AutoRecordConsentActivity.start(this@TermsListActivity) })

                Spacer(Modifier.height(16.dp))
                Item("계정·데이터 삭제 안내", "account-deletion.html")

                Spacer(Modifier.height(24.dp))
                Text(
                    "시그널랩 · 대표 이영진\n" +
                    "서울특별시 서초구 강남대로 327, 지하1층 405-18호\n" +
                    "개인정보·위치정보 관리책임자: 이영진 (sollbe82@gmail.com)",
                    fontSize = 11.sp, color = muted, lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 40.dp)
                )
            }
        }
    }

    @Composable
    private fun Item(title: String, path: String, badge: String? = null) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { open(path) },
            colors = CardDefaults.cardColors(containerColor = card),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    if (badge != null) Text(badge, fontSize = 11.sp, color = muted,
                        modifier = Modifier.padding(top = 3.dp))
                }
                Text("›", fontSize = 22.sp, color = muted)
            }
        }
    }
}
