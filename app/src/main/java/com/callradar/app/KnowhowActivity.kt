package com.callradar.app

// ═══════ [내 노하우 v2 — 로컬 온리] ═══════
// 원칙: 비공개 노트는 이 폰(SharedPreferences)에만 저장. 서버 미전송 → 운영자도 물리적으로 볼 수 없음.
//  공유 스위치를 켠 노트만, 익명 활용 고지에 동의한 뒤 서버(/api/knowhow)로 올라감.
//  (콜제보가 약했던 이유 = 쓰는 사람에게 남는 게 없음 → 이번엔 '내 영업수첩'이 본체, 공유는 옵션)

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callradar.app.ui.theme.CallRadarTheme
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class KnowhowActivity : ComponentActivity() {

    companion object {
        private const val PKEY = "local_knowhow_v1"
        fun start(context: Context) {
            context.startActivity(Intent(context, KnowhowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val SERVER_URL = "https://callradar-server.onrender.com"
    private val bg = Color(0xFF0A0E1A); private val card = Color(0xFF111827)
    private val accent = Color(0xFFF59E0B); private val green = Color(0xFF10B981); private val muted = Color(0xFF9CA3AF)

    data class Note(val id: String, val area: String, val time: String, val text: String, val created: Long, val shared: Boolean)

    private fun prefs() = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    private fun loadNotes(): List<Note> = try {
        val arr = JSONArray(prefs().getString(PKEY, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Note(o.optString("id"), o.optString("area"), o.optString("time"), o.optString("text"), o.optLong("created"), o.optBoolean("shared"))
        }.sortedByDescending { it.created }
    } catch (e: Exception) { emptyList() }

    private fun saveNotes(list: List<Note>) {
        val arr = JSONArray()
        list.forEach { n -> arr.put(JSONObject().apply { put("id", n.id); put("area", n.area); put("time", n.time); put("text", n.text); put("created", n.created); put("shared", n.shared) }) }
        prefs().edit().putString(PKEY, arr.toString()).apply()
    }

    /** 공유 = 익명 활용 동의 후에만 서버 전송. 성공 시 true */
    private fun shareToServer(n: Note, done: (Boolean) -> Unit) {
        val userId = prefs().getString("user_id", "") ?: ""
        if (userId.isBlank()) { done(false); return }
        Thread {
            var ok = false
            try {
                val json = JSONObject().apply { put("user_id", userId); put("area", n.area); put("time_band", n.time); put("pattern", ""); put("note", n.text) }
                val conn = (URL("$SERVER_URL/api/knowhow").openConnection().apply { com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") } } as HttpURLConnection).apply {
                    requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 15000; readTimeout = 20000
                }
                conn.outputStream.write(json.toString().toByteArray(Charsets.UTF_8))
                ok = conn.responseCode in 200..299; conn.disconnect()
            } catch (e: Exception) {}
            runOnUiThread { done(ok) }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CallRadarTheme { Screen() } }
    }

    data class Draft(val area: String, val time: String, val text: String)

    @Composable
    private fun Screen() {
        var notes by remember { mutableStateOf(loadNotes()) }
        var showAdd by remember { mutableStateOf(false) }
        var shareTarget by remember { mutableStateOf<Note?>(null) }
        var drafts by remember { mutableStateOf<List<Draft>>(emptyList()) }

        // [자동 초안] 빈 종이 제거 — 내 운행 패턴(서버 규칙 감지)을 확인만 하면 노트가 됨
        LaunchedEffect(Unit) {
            Thread {
                try {
                    val uid = prefs().getString("user_id", "") ?: return@Thread
                    if (uid.isBlank()) return@Thread
                    val conn = (URL("$SERVER_URL/api/knowhow-drafts/$uid").openConnection().apply { com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") } } as HttpURLConnection).apply { connectTimeout = 12000; readTimeout = 20000 }
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText(); conn.disconnect()
                    val arr = JSONObject(body).optJSONArray("drafts") ?: return@Thread
                    val existing = loadNotes().map { it.text }.toSet()
                    val list = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        Draft(o.optString("area"), o.optString("time"), o.optString("text"))
                    }.filter { it.text !in existing }
                    runOnUiThread { drafts = list }
                } catch (e: Exception) {}
            }.start()
        }

        Column(Modifier.fillMaxSize().background(bg)) {
            // 헤더
            Row(Modifier.fillMaxWidth().background(card).padding(top = 40.dp, start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", fontSize = 24.sp, color = accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { finish() }.padding(end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Text("📝 내 노하우", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("나만 아는 콜 패턴을 적어두는 영업 수첩", fontSize = 11.sp, color = muted)
                }
                Button(onClick = { showAdd = true }, colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                    Text("+ 쓰기", fontSize = 13.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }

            // 🔒 비공개 고지 — 이 한 줄이 콜제보와의 신뢰 차이
            Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF10B981).copy(alpha = 0.12f)), shape = RoundedCornerShape(12.dp)) {
                Text("🔒 비공개 노트는 이 폰에만 저장됩니다. 서버로 전송되지 않고, 운영자도 볼 수 없습니다. 공유 버튼을 누른 노트만 익명으로 올라갑니다.",
                    fontSize = 12.sp, color = Color(0xFF6EE7B7), lineHeight = 18.sp, modifier = Modifier.padding(12.dp))
            }

            // ✨ 자동 발견된 패턴 — 확인만 하면 노트가 됨 (쓰는 비용 0)
            if (drafts.isNotEmpty()) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFA78BFA).copy(alpha = 0.10f)), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("✨ 운행 기록에서 발견된 내 패턴", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC4B5FD))
                        drafts.forEach { d ->
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(d.text, fontSize = 12.sp, color = Color.White, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                                Button(onClick = {
                                    val n = Note(java.util.UUID.randomUUID().toString(), d.area, d.time, d.text, System.currentTimeMillis(), false)
                                    val nl = listOf(n) + notes; saveNotes(nl); notes = nl
                                    drafts = drafts.filter { it.text != d.text }
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA78BFA)), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                    Text("저장", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            if (notes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🗒️", fontSize = 44.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("첫 노하우를 적어보세요", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("예: \"금요일 밤 11시 홍대 → 강남 수요 많음\"\n\"잠실 야구 끝나면 2루 쪽 출구 대기\"", fontSize = 12.sp, color = muted, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(notes, key = { it.id }) { n ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (n.area.isNotBlank()) Tag("📍 ${n.area}")
                                    if (n.time.isNotBlank()) { Spacer(Modifier.width(6.dp)); Tag("🕐 ${n.time}") }
                                    Spacer(Modifier.weight(1f))
                                    Text(java.text.SimpleDateFormat("M/d", java.util.Locale.KOREA).format(java.util.Date(n.created)), fontSize = 10.sp, color = muted)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(n.text, fontSize = 14.sp, color = Color.White, lineHeight = 21.sp)
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (n.shared) Text("🌐 공유됨 · 익명", fontSize = 11.sp, color = green, fontWeight = FontWeight.Bold)
                                    else TextButton(onClick = { shareTarget = n }, contentPadding = PaddingValues(0.dp)) { Text("🌐 익명으로 공유하기", fontSize = 12.sp, color = accent) }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { val nl = notes.filter { it.id != n.id }; saveNotes(nl); notes = nl }, contentPadding = PaddingValues(0.dp)) { Text("🗑", fontSize = 13.sp) }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }

        if (showAdd) AddDialog(onSave = { area, time, text ->
            val n = Note(java.util.UUID.randomUUID().toString(), area, time, text, System.currentTimeMillis(), false)
            val nl = listOf(n) + notes; saveNotes(nl); notes = nl; showAdd = false
        }, onDismiss = { showAdd = false })

        shareTarget?.let { t ->
            AlertDialog(
                onDismissRequest = { shareTarget = null },
                title = { Text("익명으로 공유할까요?", color = Color.White, fontWeight = FontWeight.Bold) },
                text = { Text("공유하면 닉네임 없이 익명으로 전체 기사 팁·서비스 개선에 활용돼요. 이 노트만 서버에 올라가고, 나머지 비공개 노트는 계속 폰에만 있습니다.", fontSize = 13.sp, color = muted, lineHeight = 20.sp) },
                confirmButton = {
                    Button(onClick = {
                        shareToServer(t) { ok ->
                            if (ok) { val nl = notes.map { if (it.id == t.id) it.copy(shared = true) else it }; saveNotes(nl); notes = nl
                                android.widget.Toast.makeText(this@KnowhowActivity, "공유됨 — 고마워요! 🌱", android.widget.Toast.LENGTH_SHORT).show()
                            } else android.widget.Toast.makeText(this@KnowhowActivity, "전송 실패 — 잠시 후 다시 시도해 주세요", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        shareTarget = null
                    }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("익명 공유", color = Color.Black, fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { shareTarget = null }) { Text("취소", color = muted) } },
                containerColor = card
            )
        }
    }

    @Composable
    private fun Tag(t: String) {
        Text(t, fontSize = 10.5.sp, color = accent, modifier = Modifier.background(accent.copy(alpha = 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
    }

    @Composable
    private fun AddDialog(onSave: (String, String, String) -> Unit, onDismiss: () -> Unit) {
        var area by remember { mutableStateOf("") }
        var time by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("노하우 쓰기 🔒", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(value = text, onValueChange = { text = it.take(300) }, label = { Text("내용 (필수)", color = muted) },
                        placeholder = { Text("예: 금요일 밤 11시 홍대입구 3번 출구 앞 수요 많음", color = muted.copy(alpha = 0.6f), fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = area, onValueChange = { area = it.take(20) }, label = { Text("지역 (선택)", color = muted) },
                            singleLine = true, modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                        OutlinedTextField(value = time, onValueChange = { time = it.take(15) }, label = { Text("시간대 (선택)", color = muted) },
                            singleLine = true, modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("이 노트는 이 폰에만 저장돼요.", fontSize = 11.sp, color = green)
                }
            },
            confirmButton = {
                Button(onClick = { if (text.isNotBlank()) onSave(area.trim(), time.trim(), text.trim()) }, enabled = text.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, disabledContainerColor = Color(0xFF1F2937))) {
                    Text("저장", color = if (text.isNotBlank()) Color.Black else muted, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = muted) } },
            containerColor = card
        )
    }
}
