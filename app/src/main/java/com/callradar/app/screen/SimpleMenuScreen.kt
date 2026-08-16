package com.callradar.app.screen

// [심플 모드 메뉴 · B안] 무탭 모드의 '메뉴'. B 스타일 아이콘 그리드 + 전체 메뉴(기존 더보기)로 폴백 + 홈 모드 전환.
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

    // 앱 내 화면으로 라우팅되는 주요 기능(B 그리드). 나머지 전체는 '전체 메뉴'.
    val tiles = listOf(
        MenuTile("records", "📋", "기록"),
        MenuTile("radar", "📡", "레이더"),
        MenuTile("airport", "✈️", "공항"),
        MenuTile("settlement", "🧮", "정산"),
        MenuTile("track", "🗺️", "궤적"),
        MenuTile("stats", "📊", "분석"),
        MenuTile("ranking", "🏆", "랭킹"),
        MenuTile("namecard", "📇", "명함"),
        MenuTile("expense", "🧾", "지출촬영")
    )

    fun handle(id: String) {
        when (id) {
            "namecard" -> try { com.callradar.app.NameCardActivity.start(context) } catch (e: Exception) {}
            "expense" -> try { com.callradar.app.ReceiptScanActivity.start(context, "지출") } catch (e: Exception) {}
            else -> onOpen(id)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.bg)) {
        // B 스타일 뒤로가기 헤더
        Row(modifier = Modifier.fillMaxWidth().background(AppTheme.card).padding(top = 40.dp, start = 10.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("‹", fontSize = 24.sp, color = accent, fontWeight = FontWeight.Bold); Spacer(Modifier.width(4.dp)); Text("홈", fontSize = 14.sp, color = accent) }
            Spacer(Modifier.width(4.dp))
            Text("메뉴", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
        }

        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            // 3열 그리드
            tiles.chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { t ->
                        Card(modifier = Modifier.weight(1f).height(84.dp).clickable { handle(t.id) }, colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp)) {
                            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(t.icon, fontSize = 22.sp); Spacer(Modifier.height(6.dp))
                                Text(t.label, fontSize = 12.sp, color = AppTheme.text, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            Spacer(Modifier.height(6.dp))
            // 전체 메뉴(기존 더보기 — 설정·로그아웃·기타 기능 전부)
            Card(modifier = Modifier.fillMaxWidth().clickable { onFullMenu() }, colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⚙️ 전체 메뉴 · 설정", fontSize = 14.sp, color = AppTheme.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
