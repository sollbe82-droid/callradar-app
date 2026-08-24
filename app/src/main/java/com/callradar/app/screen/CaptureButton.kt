package com.callradar.app.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * [v92] 화면 캡처 버튼 — 각 탭 헤더의 우측에 놓는다.
 *
 *  왜 여기 있나:
 *   캡처가 홈 상단에만 있어서 '홈 화면밖에 못 찍는' 상태였다. 그런데 기사들이 실제로
 *   찍어서 톡방에 올리는 건 레이더 수급 화면이나 기록 탭의 하루 매출이다.
 *
 *  왜 오버레이가 아닌가:
 *   처음엔 탭 컨테이너 위에 오버레이 하나로 띄웠는데 두 번 어긋났다 —
 *   인셋이 없으면 상태바 밑에 깔리고, 인셋을 주면 레이더의 '⚪대기' 배지를 덮는다.
 *   화면마다 헤더 높이(44dp/48dp)도 우측에 두는 것도 달라서 바깥에서는 맞출 수가 없다.
 *   그래서 각 헤더가 자기 자리에 직접 배치하게 했다.
 *
 *  플랫폼 앱(카카오T·우버) 화면은 콜레이더 밖이라 이 버튼으로 못 찍는다 → 플로팅 캡처 버튼 담당.
 */
@Composable
fun CaptureButton(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    IconButton(
        onClick = { try { com.callradar.app.ScreenCaptureService.shareShot(ctx) } catch (e: Exception) {} },
        modifier = modifier.size(36.dp)
    ) { Text("📸", fontSize = 18.sp) }
}
