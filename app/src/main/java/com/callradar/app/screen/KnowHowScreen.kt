// ===== KnowHowScreen v1 (2026-07) — 내 노하우(씨앗) =====
// 기사 개인 사전지식을 저장 → AI 비서가 데이터 0이어도 개인 맞춤. 음성 한마디/원터치로 손 안 가게.
// 3층 구조: (1) 씨앗=이 화면 (2) 데이터로 검증(confirmed) (3) 익명 크라우드. v1은 씨앗 입력.
package com.callradar.app.screen

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private data class KnowHow(val id: Int, val area: String, val timeBand: String, val pattern: String, val note: String, val confirmed: Int)

@Composable
fun KnowHowScreen(userId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = Color(0xFFF5A623); val green = Color(0xFF10B981); val muted = Color(0xFF9CA3AF)
    val server = Config.SERVER_URL

    var area by remember { mutableStateOf("") }
    var timeBand by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf<List<KnowHow>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    val timeBands = listOf("아무때나", "새벽", "오전", "점심", "오후", "저녁", "심야")
    val patterns = listOf("장거리", "공항", "단거리", "특정목적지", "콜 없음")

    fun load() {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { (URL("$server/api/knowhow/$userId").openConnection() as HttpURLConnection).apply { connectTimeout = 8000 }.inputStream.bufferedReader().readText() }
                val arr = JSONArray(resp)
                notes = (0 until arr.length()).map { i -> val o = arr.getJSONObject(i); KnowHow(o.optInt("id"), o.optString("area"), o.optString("time_band"), o.optString("pattern"), o.optString("note"), o.optInt("confirmed")) }
            } catch (e: Exception) {}
        }
    }
    LaunchedEffect(Unit) { load() }

    // 음성 입력 — 시스템 받아쓰기(권한 불필요). 결과를 메모에 채움("운전 중 한마디").
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val said = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) note = if (note.isBlank()) said else "$note $said"
        }
    }
    fun startVoice() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "노하우를 말해보세요 (예: 뱅뱅사거리 저녁 수원 장거리 자주)")
            }
            voiceLauncher.launch(i)
        } catch (e: Exception) {}
    }

    fun save() {
        if (busy) return
        if (area.isBlank() && note.isBlank()) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = JSONObject().apply { put("user_id", userId.toIntOrNull() ?: userId); put("area", area); put("time_band", timeBand); put("pattern", pattern); put("note", note) }
                    val conn = (URL("$server/api/knowhow").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 8000 }
                    conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }; conn.responseCode
                }
                area = ""; timeBand = ""; pattern = ""; note = ""
                load()
            } catch (e: Exception) {}
            busy = false
        }
    }

    fun del(id: Int) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val conn = (URL("$server/api/knowhow/$id").openConnection() as HttpURLConnection).apply { requestMethod = "DELETE"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000 }
                    conn.outputStream.use { it.write(JSONObject().apply { put("user_id", userId.toIntOrNull() ?: userId) }.toString().toByteArray()) }; conn.responseCode
                }
                load()
            } catch (e: Exception) {}
        }
    }

    Column(Modifier.fillMaxSize().background(AppTheme.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("‹ 더보기", color = accent, fontSize = 15.sp) }
            Spacer(Modifier.width(6.dp))
            Text("📝 내 노하우", color = AppTheme.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("내가 아는 걸 적어두면, 데이터가 없어도 비서가 그대로 알려줘요.", fontSize = 12.sp, color = muted)
                        OutlinedTextField(value = area, onValueChange = { area = it }, label = { Text("지역 (예: 뱅뱅사거리, 잠실 3동)", color = muted) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                        Text("언제", fontSize = 12.sp, color = muted)
                        FlowChips(timeBands, timeBand, accent, muted) { timeBand = if (timeBand == it) "" else it }
                        Text("뭐가 뜨나", fontSize = 12.sp, color = muted)
                        FlowChips(patterns, pattern, accent, muted) { pattern = if (pattern == it) "" else it }
                        OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("메모 (예: 수원·성남 장거리, 인공도 종종)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { startVoice() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text("🎤 말로 입력") }
                            Button(onClick = { save() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text(if (busy) "저장 중" else "저장", color = Color.Black, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            item { Text("내가 적은 노하우 (${notes.size})", fontSize = 13.sp, color = muted, modifier = Modifier.padding(top = 4.dp)) }
            items(notes) { k ->
                Card(colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val head = listOfNotNull(k.area.ifBlank { null }, k.timeBand.ifBlank { null }, k.pattern.ifBlank { null }).joinToString(" · ")
                            if (head.isNotBlank()) Text(head, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                            if (k.note.isNotBlank()) Text(k.note, fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
                            if (k.confirmed > 0) Text("✓ 데이터로도 확인 ${k.confirmed}회", fontSize = 11.sp, color = green, modifier = Modifier.padding(top = 2.dp))
                        }
                        TextButton(onClick = { del(k.id) }) { Text("🗑", fontSize = 14.sp) }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun FlowChips(options: List<String>, selected: String, accent: Color, muted: Color, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { opt ->
                    FilterChip(selected = selected == opt, onClick = { onPick(opt) }, label = { Text(opt, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                }
            }
        }
    }
}
