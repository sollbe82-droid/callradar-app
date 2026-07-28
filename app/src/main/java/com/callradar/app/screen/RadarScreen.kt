// ===== RadarScreen v1 (2026-07) — AI + 지도 통합 '레이더' 탭 =====
// 근무세션(work_start)이 지도의 '모드'를 조종한다:
//  · 근무중(work_start>0) → 라이브 코파일럿
//  · 대기/퇴근후(work_start==0) → 작전/회고 지도
// 1차: 세션 모드 배너 + AI 브리핑 스트립(모드별 안내) + 내 운행 지도(임베드).
package com.callradar.app.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RadarScreen(userId: String) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    // 근무세션 상태로 모드 결정 (HomeScreen이 저장하는 work_start 키 공유)
    val workStart = remember { prefs.getLong("work_start", 0L) }
    val live = workStart > 0L
    LaunchedEffect(Unit) { com.callradar.app.Telemetry.log(ctx, "open_screen", "radar", meta = if (live) "live" else "plan") }

    val accent = Color(0xFFF5A623)
    val green = Color(0xFF10B981)
    val muted = Color(0xFF9CA3AF)

    Column(Modifier.fillMaxSize().background(AppTheme.bg)) {
        // 모드 배너
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📡 레이더", color = AppTheme.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .background((if (live) green else muted).copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    if (live) "🟢 근무중 · 코파일럿" else "⚪ 대기 · 작전 지도",
                    color = if (live) green else muted, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        // AI 브리핑 스트립 (모드별 안내 — 데이터 기반 브리핑은 다음 단계에서 연동)
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.card),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🤖", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (live)
                        "근무 중이에요. 공차가 길어지면 아래 지도의 밀집 구역으로 이동해 보세요."
                    else
                        "출근하면 오늘의 작전 지도(어제 돈 되는 곳 + 오늘 이벤트 파장)가 켜집니다.",
                    color = muted, fontSize = 12.sp
                )
            }
        }

        // 지도 (내 운행 밀도 히트맵) — 탭 임베드 모드
        Box(Modifier.weight(1f)) {
            DriverMapScreen(userId = userId, onBack = {}, embedded = true)
        }
    }
}
