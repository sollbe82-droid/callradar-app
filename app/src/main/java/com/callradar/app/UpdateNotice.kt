package com.callradar.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 업데이트 안내 팝업.
 *
 * ★ 왜 만들었나 (2026-09-07)
 *   v100 에서 고친 **late_cancel**(장거리 운행이 통째로 삭제되던 것)이 **09-05 에도 발생**했다.
 *   코드는 고쳤는데 기사 폰에 안 갔다. 실측 버전 분포 —
 *     3.0.3 계열 65명 · 3.0.0 계열 21명 · 2.9.9 10명 · 2.9.7 14명(가드 없음)
 *   "고쳤다"와 "기사에게 갔다"는 다른 말이다. 그 간격을 메우는 게 이 파일이다.
 *
 * ★ 잠그지 않는다.
 *   원스토어는 심사에 며칠 걸린다. 새 버전을 올린 직후 잠그면 업데이트할 방법이 없는데
 *   앱만 막힌다. 운행 중에 막히면 그날 기록이 통째로 날아간다 —
 *   기사에게 운행기록은 돈이고, 그걸 우리가 막는 건 고치려던 문제보다 나쁘다.
 *   그래서 기본은 '나중에'를 누를 수 있다. 서버 `force` 로만 그 버튼을 감춘다(최후 수단).
 *
 * ★ 하루 한 번만 뜬다.
 *   매번 뜨면 기사가 내용을 안 읽고 반사적으로 닫는다. 그러면 안 띄운 것과 같다.
 */
object UpdateNotice {

    private const val PKG = "com.callradar.app"

    /** 오늘 이미 닫았으면 다시 안 띄운다(강제 모드는 예외). */
    private fun dismissedToday(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA)
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul") }
            .format(java.util.Date())
        return p.getString("upd_dismissed_day", "") == day
    }

    private fun markDismissed(ctx: Context) {
        val p = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA)
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul") }
            .format(java.util.Date())
        p.edit().putString("upd_dismissed_day", day).apply()
    }

    /**
     * 설치된 스토어로 보낸다.
     * ★ 설치 출처가 아니라 **빌드 flavor** 로 판단한다. 원스토어 APK 를 받은 기사에게
     *   플레이를 열어주면 "이 앱을 사용할 수 없습니다"가 뜬다(플레이에 그 서명이 없다).
     */
    fun openStore(ctx: Context, store: String) {
        val onestore = store == "onestore" || BuildConfig.FLAVOR == "onestore"
        val tries = if (onestore) listOf(
            "onestore://common/product/$PKG",
            "https://m.onestore.co.kr/mobilepoc/apps/appsDetail.omp?prodId=$PKG"
        ) else listOf(
            "market://details?id=$PKG",
            "https://play.google.com/store/apps/details?id=$PKG"
        )
        for (u in tries) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) { /* 다음 후보로 */ }
        }
    }

    /**
     * 홈·간편홈에서 호출. 조건이 안 맞으면 아무것도 안 그린다.
     * 서버 안내(`upd_required`)가 없으면 조용하다 — 기본값은 '안 띄움'이다.
     */
    @Composable
    fun Popup(ctx: Context) {
        val prefs = remember { ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE) }
        val required = prefs.getString("upd_required", "") ?: ""
        val force = prefs.getBoolean("upd_force", false)
        if (required.isBlank()) return
        if (!force && dismissedToday(ctx)) return

        var open by remember { mutableStateOf(true) }
        if (!open) return

        val reason = prefs.getString("upd_reason", "") ?: ""
        val store = prefs.getString("upd_store", "play") ?: "play"
        val cur = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "" }

        AlertDialog(
            onDismissRequest = { },   // 바깥 탭으로 닫히면 못 보고 지나친다 — 버튼으로만
            title = { Text("업데이트가 있어요", fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B)) },
            text = {
                Text(
                    buildString {
                        append(reason)
                        append("\n\n지금 버전 ").append(cur)
                        append("   →   새 버전 ").append(required)
                        append(if (store == "onestore") "\n원스토어에서 받을 수 있어요."
                               else "\n구글플레이에서 받을 수 있어요.")
                    },
                    fontSize = 14.sp, lineHeight = 21.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { openStore(ctx, store) }) {
                    Text("지금 업데이트", fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                }
            },
            dismissButton = {
                // 강제 모드가 아닐 때만 닫을 수 있다.
                if (!force) TextButton(onClick = { markDismissed(ctx); open = false }) { Text("나중에") }
            }
        )
    }
}
