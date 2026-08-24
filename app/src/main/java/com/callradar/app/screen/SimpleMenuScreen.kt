package com.callradar.app.screen

// [심플 모드 메뉴 · B안] 무탭 모드의 '메뉴'. B 스타일 아이콘 그리드 + 전체 메뉴(기존 더보기)로 폴백 + 홈 모드 전환.
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

private data class MenuTile(val id: String, val icon: String, val label: String)

@Composable
fun SimpleMenuScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onFullMenu: () -> Unit,
    onSwitchClassic: () -> Unit
) {
    val context = LocalContext.current
    val accent = Color(0xFFF59E0B); val muted = Color(0xFF6B7280)
    // [v91] 캡처 버튼 표시 토글 — 간편모드에서도 바로 끌 수 있게.
    //  고급 설정 안에만 두면 '메뉴 → 고급설정 → 스크롤'로 두 단계라 거슬려서 끄려는 사람에겐 너무 멀다.
    var shotOn by remember {
        mutableStateOf(context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            .getBoolean("floating_shot", false))
    }

    // [3그룹 리빌딩] 기능명 나열 대신 기사의 하루 언어로: 오늘 일 / 더 벌기 / 내 살림
    val groups = listOf(
        "오늘 일" to listOf(
            MenuTile("records", "📋", "기록·정산"),
            MenuTile("expense", "🧾", "지출촬영"),
            MenuTile("track", "🗺️", "궤적")
        ),
        "더 벌기" to listOf(
            MenuTile("radar", "📡", "레이더"),
            MenuTile("airport", "✈️", "공항"),
            MenuTile("events", "🎪", "행사"),
            MenuTile("stats", "📊", "분석"),
            MenuTile("ranking", "🏆", "랭킹")
        ),
        "내 살림" to listOf(
            MenuTile("salary", "💰", "월급 예상"),
            MenuTile("tax", "🧾", "세무 리포트"),
            MenuTile("knowhow", "📝", "내 노하우"),
            MenuTile("namecard", "📇", "명함"),
            MenuTile("setup_help", "📖", "설치 도움말")
        )
    )

    fun handle(id: String) {
        when (id) {
            "namecard" -> try { com.callradar.app.NameCardActivity.start(context) } catch (e: Exception) {}
            "expense" -> try { com.callradar.app.ReceiptScanActivity.start(context, "지출") } catch (e: Exception) {}
            "knowhow" -> try { com.callradar.app.KnowhowActivity.start(context) } catch (e: Exception) {}
            "events" -> try { com.callradar.app.EventsActivity.start(context) } catch (e: Exception) {}
            "insights" -> try { com.callradar.app.InsightsActivity.start(context) } catch (e: Exception) {}
            "salary" -> try { com.callradar.app.CompanyProfileActivity.start(context) } catch (e: Exception) {}
            "tax" -> try { com.callradar.app.TaxReportActivity.start(context) } catch (e: Exception) {}
            "setup_help" -> { com.callradar.app.MainActivity.wizardReopen.value = true; onBack() }   // 홈으로 돌아가면 마법사가 위에 뜸
            else -> onOpen(id)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.bg)) {
        // B 스타일 뒤로가기 헤더
        Row(modifier = Modifier.fillMaxWidth().background(AppTheme.card).padding(top = 34.dp, start = 10.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("‹", fontSize = 24.sp, color = accent, fontWeight = FontWeight.Bold); Spacer(Modifier.width(4.dp)); Text("홈", fontSize = 14.sp, color = accent) }
            Spacer(Modifier.width(4.dp))
            Text("메뉴", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
            // [3그룹] 섹션 제목 + 3열 그리드
            groups.forEach { (title, tiles) ->
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp, top = 4.dp))
                tiles.chunked(3).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { t ->
                            Card(modifier = Modifier.weight(1f).height(84.dp).clickable { handle(t.id) }, colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp)) {
                                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(t.icon, fontSize = 22.sp); Spacer(Modifier.height(6.dp))
                                    Text(t.label, fontSize = 11.5.sp, color = AppTheme.text, fontWeight = FontWeight.Medium, maxLines = 1)
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            // [간편모드 피드백] "전체 메뉴로 3번 클릭" 제거 — 더보기의 주요 항목을 여기 직접 펼침(설정 · 도구)
            Text("설정 · 도구", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp, top = 4.dp))
            @Composable fun MenuRow(icon: String, label: String, desc: String, onClick: () -> Unit) {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(icon, fontSize = 17.sp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, fontSize = 13.5.sp, color = AppTheme.text, fontWeight = FontWeight.Bold)
                            Text(desc, fontSize = 10.5.sp, color = muted, maxLines = 1)
                        }
                        Text("›", fontSize = 16.sp, color = muted)
                    }
                }
            }
            MenuRow("⚙️", "기사 설정", "유형·사납금·가스·수수료·연차") { onOpen("driver_settings") }   // [#버그수정] settlement(일일마감)과 충돌하던 것 분리
            // [영업일 시각] 간편모드에서도 바로 설정 — 자정 넘긴 운행을 어느 날 매출로 묶을지 기준
            run {
                val prefs2 = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                var dsh by remember { mutableStateOf(prefs2.getInt("day_start_hour", 0)) }
                var showDsh by remember { mutableStateOf(false) }
                MenuRow("📅", "영업일 시작시각", if (dsh == 0) "자정 기준 · 야간·일차 기사님은 새벽으로" else "${dsh}시 기준 — 자정 넘긴 운행을 한 날로") { showDsh = true }
                if (showDsh) DayStartDialog(onDismiss = { showDsh = false }, onSaved = { dsh = it })   // [영업일 단일화] 공용 다이얼로그
            }
            MenuRow("📄", "급여명세서 스캔", "촬영 한 번으로 전 항목 인식·실수령 역산") { try { com.callradar.app.PayslipScanActivity.start(context) } catch (e: Exception) {} }
            MenuRow("🧾", "매출 영수증 정산", "미터기 상세내역 촬영 → 빠진 금액 자동 채움") { try { com.callradar.app.ReceiptReconcileActivity.start(context) } catch (e: Exception) {} }
            MenuRow("📷", "과거기록 가져오기", "다른 앱 장부를 사진으로 불러오기") { try { com.callradar.app.ImageImportActivity.start(context) } catch (e: Exception) {} }
            run {
                val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                MenuRow(if (AppTheme.isDark) "☀️" else "🌙", if (AppTheme.isDark) "라이트 모드로" else "다크 모드로", "화면 테마 전환") {
                    val nd = !AppTheme.isDark
                    prefs.edit().putBoolean("dark_mode", nd).apply(); AppTheme.isDark = nd
                }
            }
            MenuRow("📸", if (shotOn) "캡처 버튼 끄기" else "캡처 버튼 켜기",
                if (shotOn) "운행 버튼 아래 · 콜 화면 찍어 공유" else "지금은 숨겨져 있어요") {
                shotOn = !shotOn
                val p = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                p.edit().putBoolean("floating_shot", shotOn).apply()
                // 플로팅이 떠 있으면 즉시 반영 (앱 껐다 켜야 보이면 켠 건지 만 건지 헷갈린다)
                val act = context as? com.callradar.app.MainActivity
                if (p.getBoolean("floating_on", false)) { act?.stopFloatingButton(); act?.startFloatingButton() }
            }
            MenuRow("📤", "데이터 내보내기", "전체 운행기록 CSV 다운로드") {
                try {
                    val uid = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("user_id", "") ?: ""
                    if (uid.isNotEmpty()) context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://callradar-server.onrender.com/api/export/$uid")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {}
            }

            Spacer(Modifier.height(6.dp))
            // 나머지(계정 연결·이름·공유설정·로그아웃 등)만 전체 메뉴로
            Card(modifier = Modifier.fillMaxWidth().clickable { onFullMenu() }, colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⋯ 계정 · 연결 · 고급 설정", fontSize = 14.sp, color = AppTheme.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("›", fontSize = 18.sp, color = muted)
                }
            }

            Spacer(Modifier.height(10.dp))
            // 홈 모드 되돌리기 — [위치수정] 맨 밑(weight)에서 전체 메뉴 바로 아래로 올림(너무 밑에 있다는 피드백).
            Card(modifier = Modifier.fillMaxWidth().clickable { onSwitchClassic() }, colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🔄 기본 홈으로 전환", fontSize = 14.sp, color = AppTheme.text, fontWeight = FontWeight.Bold)
                        Text("심플 모드(베타)를 끄고 원래 화면으로", fontSize = 11.sp, color = muted)
                    }
                    Text("›", fontSize = 18.sp, color = muted)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
