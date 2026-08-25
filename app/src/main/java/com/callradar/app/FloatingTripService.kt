package com.callradar.app

// ===== FloatingTripService v1 (2026-07-13) =====
// 플로팅 오버레이 버튼: 다른 앱 위에 떠서 기사가 직접 탑승/완료 누름
// 탑승 → GPS 출발위치 스냅샷 / 완료 → GPS 도착위치 + 운행 자동생성(서버 전송)
// 접근성 자동기록과 독립. 기사 능동 조작이라 심사 안전. 백그라운드 추적 안 함(누를 때만 위치 1회)

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class FloatingTripService : Service() {

    private val SERVER_URL = "https://callradar-server.onrender.com"
    private lateinit var windowManager: WindowManager
    private var floatingView: TextView? = null
    // [v91] 캡처 전용 버튼 — 카카오T·우버 화면 위에서 콜을 찍으려면 앱 밖에 떠 있어야 한다.
    private var shotView: TextView? = null
    private var shotParams: WindowManager.LayoutParams? = null   // 운행 버튼을 끌 때 같이 옮기려면 참조가 필요
    // [v53 #124] 자동 운행 수동취소 — 배지 길게누름으로 '취소?' 무장 → 탭하면 취소.
    private var autoCancelArmed = false
    private val autoCancelHandler = android.os.Handler(Looper.getMainLooper())
    private var autoCancelRun: Runnable? = null
    // [완료콜누락 방어] 자동배지가 안 꺼질 때(완료 감지 놓침) 탭 → '완료로 기록'(삭제 아님). 삭제는 길게누름.
    private var autoFinalizeArmed = false
    private val autoFinalizeHandler = android.os.Handler(Looper.getMainLooper())
    private var autoFinalizeRun: Runnable? = null
    private lateinit var fusedClient: FusedLocationProviderClient

    // 운행 상태
    private var isRiding = false
    private var startLat = 0.0
    private var startLng = 0.0
    private var startAddr = ""
    private var startTime = ""

    // [안전장치] 완료 후 3초 취소 대기 상태
    private var pendingConfirm = false
    private var pendingDestLat = 0.0
    private var pendingDestLng = 0.0
    private var pendingDestAddr = ""
    private val confirmHandler = android.os.Handler(Looper.getMainLooper())
    private var confirmRunnable: Runnable? = null
    private val MIN_DISTANCE_M = 100f  // 최소 이동거리(제자리 연타 방지)
    private val MIN_RIDE_MS = 2L * 60 * 1000  // [플로팅 개선] 100m 미만이어도 이 시간(2분) 이상 운행이면 기록 — 정체·단거리 실제 운행 보호
    private val MAX_RIDE_MS = 3L * 60 * 60 * 1000  // [v2] 3시간 넘게 열린 운행 = '깜빡 잊고 안 끈' 스톨 → 복원·기록 안 함
    private fun parseUtc(s: String): Long = try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.parse(s)?.time ?: 0L
    } catch (e: Exception) { 0L }
    // [v2] 이 폰의 고유 기기 ID — 운행 상태에 찍어 '다른 폰 운행'을 절대 이어받지 않게 (투폰 독립 보장)
    private fun deviceId(): String = try { android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "" } catch (e: Exception) { "" }

    // [v2] 운행중 은은한 펄스(밝기 호흡) — "지금 기록 중, 끝나면 눌러요" 신호. 더보기에서 끌 수 있음.
    private var pulseOn = false
    private var pulsePhase = 0.0
    private val pulseHandler = android.os.Handler(Looper.getMainLooper())
    private var pulseRunnable: Runnable? = null

    private fun userId(): String {
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        return prefs.getString("user_id", "") ?: ""
    }

    override fun onBind(intent: Intent?): IBinder? = null


    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        try { getSharedPreferences("callradar_prefs", MODE_PRIVATE).edit().putString("overlay_started", java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(java.util.Date())).apply() } catch (e: Exception) {}
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        // 플로팅 버튼(원형 텍스트뷰)
        val btn = TextView(this).apply {
            text = "🚕\n운행"   // [피드백] 레이더 아이콘처럼 보여 헷갈림 → 🚕택시로 '운행 기록 버튼'임을 명확히
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = ovalBg("#F59E0B")
            setPadding(0, 0, 0, 0)
        }
        floatingView = btn

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val sizePx = (64 * resources.displayMetrics.density).toInt()
        // 사각형 모양이면 가로를 넓게 준다 — 그래야 "회현동1가" 같은 긴 지명이 한 줄에 들어간다.
        //  (원은 가로·세로가 같아야 동그랗게 보이므로 정사각형 유지)
        val isRect = getSharedPreferences("callradar_prefs", MODE_PRIVATE)
            .getString("floating_shape", "circle") == "rect"
        val wPx = if (isRect) (96 * resources.displayMetrics.density).toInt() else sizePx
        val params = WindowManager.LayoutParams(
            wPx, sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // [겹침 수정] 저장된 위치가 있으면 복원(드래그한 자리 유지), 없으면 기본값을 화면 아래쪽(72%)·우측으로.
            //  이전 42%(중앙)는 시간당매출·레이더 버튼을 가려서 아래로 내림. 드래그하면 그 자리에 고정됨.
            val fpPos = getSharedPreferences("callradar_prefs", MODE_PRIVATE)
            val savedX = fpPos.getInt("float_x", -1); val savedY = fpPos.getInt("float_y", -1)
            x = if (savedX in 0..resources.displayMetrics.widthPixels) savedX else (resources.displayMetrics.widthPixels - wPx - (16 * resources.displayMetrics.density)).toInt().coerceAtLeast(0)
            y = if (savedY in 0..resources.displayMetrics.heightPixels) savedY else (resources.displayMetrics.heightPixels * 0.72f).toInt()
        }

        // 드래그 이동 + 탭 구분
        var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        val lpHandler = android.os.Handler(Looper.getMainLooper()); var longPressed = false; var lpRun: Runnable? = null
        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = event.rawX; touchY = event.rawY; moved = false
                    longPressed = false
                    // [v53 #124] 자동 운행 중 길게누름 → 수동취소 무장(가맹 자동취소 놓침·유령콜 안전망). 그 외엔 무동작.
                    //  [v91] 여기에 캡처를 얹어봤다가 뺐다. 운행 중에 콜 화면을 찍으려고 길게 누르면
                    //   취소 무장이 떠버린다 — 하필 찍고 싶은 때가 운행 중이라 부딪힌다. 캡처는 별도 버튼으로 뺐다.
                    lpRun = Runnable { if (!moved) { longPressed = true; armAutoCancel() } }
                    lpHandler.postDelayed(lpRun!!, 700)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt(); val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 28 || kotlin.math.abs(dy) > 28) { moved = true; lpRun?.let { lpHandler.removeCallbacks(it) } }   // [v19] 10→28px: 손가락 미세이동에도 탭 인식
                    params.x = initX + dx; params.y = initY + dy
                    windowManager.updateViewLayout(btn, params)
                    syncShotToMain(params, sizePx)   // [v91] 캡처 버튼도 같이 따라온다 (따로 놀지 않게)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    lpRun?.let { lpHandler.removeCallbacks(it) }; if (!moved && !longPressed) onButtonTap()
                    // [겹침 수정] 드래그로 옮긴 위치 저장 → 다음에 그 자리에 뜸(매번 중앙으로 안 돌아감).
                    if (moved) try { getSharedPreferences("callradar_prefs", MODE_PRIVATE).edit().putInt("float_x", params.x).putInt("float_y", params.y).apply() } catch (e: Exception) {}
                    true
                }
                else -> false
            }
        }

        try { windowManager.addView(btn, params) } catch (e: Exception) {}
        addShotButton(params, wPx)   // 사각형이면 가로가 넓으니 그 폭 기준으로 캡처 버튼을 가운데 맞춘다
        restoreRideState()
        startAutoBadgeTicker()
    }

    /**
     * [v91] 캡처 전용 버튼 — 운행 버튼과 따로 둔다.
     *
     *  처음엔 운행 버튼 길게누르기에 얹었는데, 그 동작은 이미 '자동운행 취소 무장'이 쓰고 있다.
     *  콜 화면을 찍고 싶은 때가 대개 운행 중이라 정확히 부딪힌다 — 찍으려다 취소를 누를 판.
     *  그래서 작은 버튼을 하나 더 띄우고 한 번 탭 = 캡처로 뒀다. 길게 누를 필요도 없다.
     *
     *  [v91] 기본은 꺼둔다. 화면에 버튼이 하나 더 뜨는 걸 부담스러워하는 쪽이 많고,
     *   운행 버튼 옆이라 급할 때 잘못 누를 여지도 있다. 콜 화면을 자랑하려는 기사만
     *   메뉴에서 켜면 된다. 콜레이더 화면 캡처는 홈 상단 📸로 충분하다.
     */
    private fun addShotButton(mainParams: WindowManager.LayoutParams, mainSize: Int) {
        val prefs = getSharedPreferences("callradar_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("floating_shot", false)) return

        val d = resources.displayMetrics.density
        val size = (44 * d).toInt()   // 운행 버튼(64dp)보다 작게 — 오조작 방지 겸 시각적 종속
        val v = TextView(this).apply {
            text = "📸"; textSize = 17f; gravity = Gravity.CENTER
            background = ovalBg("#1F2937")
            alpha = 0.92f
        }
        val p = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 운행 버튼 바로 '아래'에 붙인다. 위에 붙였더니 운행 버튼을 화면 상단에 둔 기기에서
            //  앱 상단바(☰메뉴)를 덮어 메뉴가 안 눌렸다(실기기 확인).
            //  자리를 따로 저장하지 않는다 — 항상 운행 버튼에 붙어 다닌다.
            val screenH = resources.displayMetrics.heightPixels
            x = mainParams.x + (mainSize - size) / 2
            y = (mainParams.y + mainSize + (8 * d).toInt()).coerceIn(0, (screenH - size).coerceAtLeast(0))
        }

        // 캡처 버튼은 끌지 않는다 — 탭 전용.
        //  [유저지적] 따로 끌리면 운행 버튼과 떨어져 제각각 놀고, 겹치면 운행 버튼이 같이 눌린다.
        //  운행 버튼을 끌면 이 버튼이 따라오는 방식(syncShotToMain)으로 바꿨다.
        var downX = 0f; var downY = 0f; var slid = false
        // 길게 누르면 숨기기 — 설정까지 들어가는 것보다 빠르다. 다시 켜려면 설정 > 캡처 버튼.
        val shotLp = android.os.Handler(Looper.getMainLooper()); var shotHeld = false; var shotLpRun: Runnable? = null
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; slid = false; shotHeld = false
                    shotLpRun = Runnable {
                        if (slid) return@Runnable
                        shotHeld = true
                        prefs.edit().putBoolean("floating_shot", false).apply()
                        try { windowManager.removeView(v) } catch (ex: Exception) {}
                        shotView = null; shotParams = null
                        toast("캡처 버튼 숨김 — 설정 > 캡처 버튼에서 다시 켤 수 있어요")
                    }
                    shotLp.postDelayed(shotLpRun!!, 700)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.rawX - downX) > 28 || kotlin.math.abs(e.rawY - downY) > 28) {
                        slid = true; shotLpRun?.let { shotLp.removeCallbacks(it) }
                    }
                    true   // 소비만 하고 안 움직임 → 아래(운행 버튼)로 터치가 새지 않는다
                }
                MotionEvent.ACTION_UP -> {
                    shotLpRun?.let { shotLp.removeCallbacks(it) }
                    if (!slid && !shotHeld) {
                        // 캡처 순간 이 버튼들이 사진에 찍히면 지저분하다 → 둘 다 잠깐 숨긴다
                        v.visibility = View.GONE
                        floatingView?.visibility = View.GONE
                        v.postDelayed({
                            com.callradar.app.ScreenCaptureService.shareShot(this@FloatingTripService)
                            v.postDelayed({
                                v.visibility = View.VISIBLE
                                floatingView?.visibility = View.VISIBLE
                            }, 900)
                        }, 150)
                    }
                    true
                }
                else -> false
            }
        }
        shotView = v; shotParams = p
        try { windowManager.addView(v, p) } catch (e: Exception) {}
    }

    /** 운행 버튼을 끌면 캡처 버튼도 같은 간격으로 따라오게 한다 */
    private fun syncShotToMain(mainParams: WindowManager.LayoutParams, mainSize: Int) {
        val v = shotView ?: return
        val p = shotParams ?: return
        val d = resources.displayMetrics.density
        val size = (44 * d).toInt()
        val screenH = resources.displayMetrics.heightPixels
        p.x = mainParams.x + (mainSize - size) / 2
        p.y = (mainParams.y + mainSize + (8 * d).toInt())
            .coerceIn(0, (screenH - size).coerceAtLeast(0))
        try { windowManager.updateViewLayout(v, p) } catch (e: Exception) {}
    }

    // [자동기록 배지] 자동기록이 플랫폼 콜을 기록 중이면(activeTripId>0) 플로팅을 '🔴자동 · 출발동→현재동'으로 변신.
    //   NaviIntentReceiver가 prefs(auto_origin_dong/auto_cur_dong)에 채워둔 값을 5초마다 읽어 실시간 갱신(추가 네트워크·GPS 0).
    private val autoBadgeHandler = android.os.Handler(Looper.getMainLooper())
    private var autoBadgeRunnable: Runnable? = null
    private var autoBadgeShown = false
    private fun startAutoBadgeTicker() {
        autoBadgeRunnable = object : Runnable {
            override fun run() {
                try { updateAutoBadge() } catch (e: Exception) {}
                autoBadgeHandler.postDelayed(this, 5000)
            }
        }
        autoBadgeHandler.post(autoBadgeRunnable!!)
    }
    private fun dongOnly(s: String): String {
        val toks = s.trim().split(" ").filter { it.isNotBlank() }
        val d = toks.lastOrNull { it.endsWith("동") || it.endsWith("읍") || it.endsWith("면") || it.endsWith("가") || it.endsWith("리") }
        return d ?: toks.firstOrNull() ?: s.trim()
    }
    private fun setFloatVisible(v: Boolean) { floatingView?.post { floatingView?.visibility = if (v) View.VISIBLE else View.GONE } }
    // [v53 #124] 자동 운행 수동취소 무장: 배지를 빨간 '취소?'로 바꾸고 4초 안에 탭하면 취소.
    private fun armAutoCancel() {
        if (com.callradar.app.NaviIntentReceiver.activeTripId <= 0) { toast("취소할 자동 운행이 없어요"); return }
        autoCancelArmed = true
        floatingView?.post {
            floatingView?.textSize = 12f
            floatingView?.setTextColor(Color.WHITE)
            floatingView?.typeface = android.graphics.Typeface.DEFAULT_BOLD
            floatingView?.text = "취소?\n탭"
            floatingView?.background = ovalBg("#DC2626")
        }
        toast("이 운행을 취소하려면 배지를 한 번 더 탭하세요")
        autoCancelRun?.let { autoCancelHandler.removeCallbacks(it) }
        autoCancelRun = Runnable { autoCancelArmed = false; try { updateAutoBadge() } catch (e: Exception) {} }
        autoCancelHandler.postDelayed(autoCancelRun!!, 4000)
    }

    // [완료콜누락 방어] 자동배지가 안 꺼질 때(완료 감지 놓침) 탭 → 초록 '완료?' 무장 → 다시 탭하면 완료로 기록(삭제 아님).
    //  다른 앱 쓰다 '운행 완료' 탭을 못 잡아 activeTripId가 안 풀린 케이스를, 기록 유지한 채 원터치로 마감.
    private fun armAutoFinalize() {
        if (com.callradar.app.NaviIntentReceiver.activeTripId <= 0) { toast("완료 처리할 자동 운행이 없어요"); return }
        autoFinalizeArmed = true
        floatingView?.post {
            floatingView?.textSize = 12f
            floatingView?.setTextColor(Color.WHITE)
            floatingView?.typeface = android.graphics.Typeface.DEFAULT_BOLD
            floatingView?.text = "완료?\n탭"
            floatingView?.background = ovalBg("#10B981")
        }
        toast("이 운행을 완료로 기록하려면 배지를 한 번 더 탭 (기록은 유지돼요)")
        autoFinalizeRun?.let { autoFinalizeHandler.removeCallbacks(it) }
        autoFinalizeRun = Runnable { autoFinalizeArmed = false; try { updateAutoBadge() } catch (e: Exception) {} }
        autoFinalizeHandler.postDelayed(autoFinalizeRun!!, 4000)
    }

    private fun updateAutoBadge() {
        if (autoCancelArmed || autoFinalizeArmed) return   // 무장중엔 배지 갱신 보류(취소?/완료? 유지)
        // 수동 길빵 표시가 우선 — 자동 배지가 덮지 않게.
        if (isRiding) { setFloatVisible(true); return }
        // [업데이트 유령 플로팅 방지] 업데이트 직후엔 앱을 직접 열기 전까지 배지 숨김(MainActivity가 해제).
        if (getSharedPreferences("callradar_prefs", MODE_PRIVATE).getBoolean("float_suppressed", false)) { setFloatVisible(false); stopLocationForeground(); return }
        val active = com.callradar.app.NaviIntentReceiver.activeTripId > 0
        val p = getSharedPreferences("callradar_prefs", MODE_PRIVATE)
        val armed = p.getBoolean("auto_record_on", false)
        val floatingOn = p.getBoolean("floating_on", false)   // [#5] 수동 '운행 기록 버튼' 토글
        when {
            active -> {   // 자동기록 운행 중 — 켜든 끄든 항상 표시(기록되는 게 보이게)
                setFloatVisible(true)
                val o = dongOnly(p.getString("auto_origin_dong", "") ?: "")
                val c = dongOnly(p.getString("auto_cur_dong", "") ?: "")
                val body = when {
                    o.isNotBlank() && c.isNotBlank() && o != c -> "🔴자동\n$o→\n$c"
                    c.isNotBlank() -> "🔴자동\n$c"
                    o.isNotBlank() -> "🔴자동\n$o"
                    else -> "🔴자동\n기록중"
                }
                updateButtonSmall(body, "#EF4444"); startPulse()
                startLocationForeground()   // [플로팅 사라짐 수정] 자동기록 운행 중엔 포그라운드 유지 → 다른 앱 봐도 배지 안 죽음
            }
            // [v91] 수동 운행 중에도 어디서 어디로 가는지 보여준다.
            //  전엔 자동기록만 지역을 띄우고 수동은 "🚕 운행"만 나왔다. 손님 태우고 달리는 동안
            //  출발동이 어디였는지 확인할 데가 없어서, 나중에 기록 고칠 때 기억에 의존해야 했다.
            //  출발지는 운행 시작 때 잡은 주소, 현재지는 자동기록이 갱신해 둔 값을 그대로 쓴다(추가 GPS 0).
            isRiding -> {
                setFloatVisible(true)
                val o = dongOnly(startAddr)
                val c = dongOnly(p.getString("auto_cur_dong", "") ?: "")
                val body = when {
                    o.isNotBlank() && c.isNotBlank() && o != c -> "🚕운행\n$o→\n$c"
                    o.isNotBlank() -> "🚕운행\n$o"
                    c.isNotBlank() -> "🚕운행\n$c"
                    else -> "🚕\n운행중"
                }
                updateButtonSmall(body, "#EF4444"); startPulse()
                startLocationForeground()
            }
            floatingOn && armed -> {    // 운행 기록 버튼 ON + 자동 대기
                setFloatVisible(true); stopPulse(); updateButtonSmall("🟢자동\n대기", "#10B981"); stopLocationForeground()
            }
            floatingOn -> {             // 운행 기록 버튼만 ON = 수동 시작 버튼
                setFloatVisible(true); stopPulse(); updateButtonSmall("🚕\n운행", "#F59E0B"); stopLocationForeground()
            }
            else -> {                   // [#5] 운행 기록 버튼 OFF + 운행 아님 → 플로팅 숨김
                stopPulse(); setFloatVisible(false); stopLocationForeground()
            }
        }
    }

    // 서비스가 죽어도 시스템이 되살리도록 (운행중 상태는 prefs에서 복원)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // [v18] 화면잠금·백그라운드 중 서비스가 죽어도 운행버튼/상태가 사라지지 않게: 운행중 포그라운드 유지 + 상태 저장
    private fun saveRideState() {
        try { getSharedPreferences("callradar_prefs", MODE_PRIVATE).edit()
            .putBoolean("ride_active", isRiding)
            .putString("ride_startTime", startTime)
            .putString("ride_startAddr", startAddr)
            .putString("ride_startLat", startLat.toString())
            .putString("ride_startLng", startLng.toString())
            .putString("ride_device", deviceId())   // [v2] 이 폰 기기 ID 각인 → 다른 폰이 이어받지 못하게
            .apply() } catch (e: Exception) {}
    }
    private fun clearRideState() {
        try { getSharedPreferences("callradar_prefs", MODE_PRIVATE).edit().putBoolean("ride_active", false).apply() } catch (e: Exception) {}
    }
    private fun restoreRideState() {
        try {
            val p = getSharedPreferences("callradar_prefs", MODE_PRIVATE)
            if (p.getBoolean("ride_active", false)) {
                val savedDev = p.getString("ride_device", "") ?: ""
                val savedStart = p.getString("ride_startTime", "") ?: ""
                val startMs = parseUtc(savedStart)
                // [v2] 복원 거부 조건: (1) 다른 폰에서 시작된 운행(기기 ID 불일치) → 투폰 독립 보장,
                //      (2) 시각 파싱 불가/3시간+ 열린 스톨 → 유령 운행·금액없는 기록 방지
                val rejectRestore = (savedDev.isNotEmpty() && savedDev != deviceId()) ||
                    startMs == 0L || System.currentTimeMillis() - startMs > MAX_RIDE_MS
                if (rejectRestore) {
                    clearRideState()
                } else {
                    isRiding = true
                    startTime = savedStart
                    startAddr = p.getString("ride_startAddr", "") ?: ""
                    startLat = p.getString("ride_startLat", "0.0")?.toDoubleOrNull() ?: 0.0
                    startLng = p.getString("ride_startLng", "0.0")?.toDoubleOrNull() ?: 0.0
                    if (startAddr.isNotBlank() && startAddr != "미상") updateButtonSmall("운행중\n$startAddr", "#10B981") else updateButton("운행중", "#10B981")
                    startPulse()               // [v2] 복원 시에도 운행중 펄스
                    startLocationForeground()  // 운행중 유지 → 잠금 중에도 서비스가 안 죽음
                }
            }
        } catch (e: Exception) {}
    }

    // [v44] 운행 시작 = 근무 자동 시작. 출근 깜빡했어도 근무시간·궤적이 끊기지 않게.
    //  · 미출근(work_start=0) → 자동 출근    · 그 외 → 서비스만 확실히 기동
    //  WorkSessionService가 궤적 점(addPoint)을 4초마다 기록하므로, 이걸 켜야 운행 궤적이 남는다(거리미터 off여도 켬).
    //
    // [v93] 여기서 '일시정지 자동 재개'를 하지 않는다.
    //  예전엔 운행 시작 시도만으로 일시정지가 풀렸다. 플로팅 탑승→취소, 자동기록 오탐도 전부 통과해서
    //  운행이 없는데 근무가 켜졌고(2026-08-25 실측 11:52, 그날 운행은 07:46·10:20 두 건뿐), 3시간 50분이 오계상됐다.
    //  일시정지는 기사가 명시적으로 누른 것이다. 운행이 확정 저장됐을 때만 WorkResume.resumeIfPaused()로 푼다.
    //  → createTrip()의 로컬 저장 성공 지점을 볼 것.
    private fun ensureWorkSessionActive() {
        try {
            val p = getSharedPreferences("callradar_prefs", MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val ws = p.getLong("work_start", 0L)
            val ps = p.getLong("work_pause_start", 0L)
            var pushWs = ws; var pushPt = p.getLong("work_paused_total", 0L); var pushPs = ps
            if (ws == 0L) {
                p.edit().putLong("work_start", now).putLong("work_paused_total", 0L).putLong("work_pause_start", 0L)
                    .putInt("work_start_fare", p.getInt("work_day_start_fare", 0)).apply()
                pushWs = now; pushPt = 0L; pushPs = 0L
                try { com.callradar.app.WorkSegments.open(this, now) } catch (e: Exception) {}   // [v93] 자동출근도 구간을 남긴다
                toast("자동 출근 — 근무 시작")
            }
            // [v93] ps > 0L(일시정지) 분기 삭제 — 운행 확정 저장 시점(createTrip)에서만 재개한다.
            try { androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, com.callradar.app.WorkSessionService::class.java)) } catch (e: Exception) {}
            val uid = userId()
            if (uid.isNotEmpty()) {
                val sf = p.getInt("work_start_fare", 0)
                Thread {
                    try {
                        val json = org.json.JSONObject().apply { put("user_id", uid); put("work_start", pushWs); put("paused_total", pushPt); put("pause_start", pushPs); put("start_fare", sf) }
                        val conn = (java.net.URL("$SERVER_URL/api/work-session").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as java.net.HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 15000; readTimeout = 20000 }
                        conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                        conn.responseCode; conn.disconnect()
                    } catch (e: Exception) {}
                }.start()
            }
        } catch (e: Exception) {}
    }

    // 버튼 탭: 탑승 → 완료(취소대기 3초) → 확정
    private fun onButtonTap() {
        // [v53 #124] 자동 운행 수동취소 무장 상태에서 탭 → 취소 실행.
        // [완료콜누락 방어] '완료?' 무장 상태에서 탭 → 완료로 기록(삭제 아님) + 배지 꺼짐.
        if (autoFinalizeArmed) {
            autoFinalizeArmed = false
            autoFinalizeRun?.let { autoFinalizeHandler.removeCallbacks(it) }
            com.callradar.app.NaviIntentReceiver.instance?.finalizeActiveTripManually()
            toast("운행을 완료로 기록했어요 · 금액은 기록 탭에서 확인/수정")
            try { updateAutoBadge() } catch (e: Exception) {}
            return
        }
        if (autoCancelArmed) {
            autoCancelArmed = false
            autoCancelRun?.let { autoCancelHandler.removeCallbacks(it) }
            com.callradar.app.NaviIntentReceiver.instance?.cancelActiveTripManually()
            toast("자동 운행 기록을 취소했어요")
            try { updateAutoBadge() } catch (e: Exception) {}
            return
        }
        // [안전장치 3] 취소 대기중 다시 누르면 → 취소
        if (pendingConfirm) {
            pendingConfirm = false
            confirmRunnable?.let { confirmHandler.removeCallbacks(it) }
            isRiding = false
            stopPulse()
            stopLocationForeground(); clearRideState()
            updateButtonSmall("🚕\n운행", "#F59E0B")
            toast("운행 취소됨")
            return
        }

        if (!isRiding) {
            val p0 = getSharedPreferences("callradar_prefs", MODE_PRIVATE)
            // [완료콜누락 방어 v66] 자동기록이 플랫폼 콜을 기록 중일 때 탭 → '완료?' 무장(완료로 기록, 삭제 아님).
            //  다른 앱 쓰다 '운행 완료' 탭을 못 잡아 배지가 안 꺼진 케이스를, 기록 유지한 채 원터치로 마감할 수 있게.
            //  (삭제가 필요하면 배지를 길게 눌러 '취소?' 무장 → 탭.) 한 번 더 탭해야 실제 실행 + 4초 뒤 자동 원복(실수 방지).
            if (p0.getBoolean("auto_record_on", false) && com.callradar.app.NaviIntentReceiver.activeTripId > 0) {
                armAutoFinalize()   // [완료콜누락 방어] 탭=완료로 기록(주 동작). 삭제는 길게누름(취소?)로.
                return
            }
            // [v44 Fix B] 이전 트립의 카드금액(pending_fare)이 이 운행에 새는 것 방지 → 탑승 순간 초기화.
            p0.edit().remove("pending_fare").remove("pending_fare_ts").remove("pending_fare_raw").apply()
            // ★탑승: 버튼 즉시 "완료"로 전환 (GPS 안 기다림 = 백그라운드서도 즉각 반응)
            isRiding = true
            startTime = utcNow()
            startLat = 0.0; startLng = 0.0; startAddr = ""
            updateButton("운행중", "#10B981")
            startPulse()                // [v2] 운행중 은은한 펄스
            startLocationForeground()   // [v18] 운행 내내 포그라운드 유지 → 화면잠금에도 버튼/서비스 유지
            saveRideState()
            ensureWorkSessionActive()   // [v44] 운행 시작 → 근무 자동 출근/재개 + 궤적 기록 서비스 기동
            com.callradar.app.TimingLog.send(this, "trip_start")   // [v24 진화⑥] 시작 누른 순간 기록
            // [v30] 시작 화면 캡처(플랫폼 자동판별) 제거 — 화면캡처 미사용으로 동의창 원천 차단.
            toast("시작 — 출발 확인 중")
            // GPS는 백그라운드에서 따로 잡아 채움 (늦게 와도 됨). 잡히면 출발지 동을 버튼에 표시
            captureLocation { lat, lng, addr ->
                startLat = lat; startLng = lng; startAddr = addr
                saveRideState()
                if (lat != 0.0 && isRiding && !pendingConfirm) {
                    // 출발지 동 있으면 "완료\n양재2동" 두 줄(글자 작게), 없으면 "완료✓"
                    if (addr.isNotBlank() && addr != "미상") updateButtonSmall("운행중\n$addr", "#10B981")
                    else updateButton("운행중", "#10B981")
                }
            }
        } else {
            // [v30] 종료 시 화면캡처(자동 금액인식) 제거 — 안드로이드 화면공유 동의창을 원천 차단.
            //  금액은 기록 후 QuickEntry 팝업에서 수동 입력(원래 방식). 화면캡처 안 씀 = 동의창 안 뜸.
            getSharedPreferences("callradar_prefs", MODE_PRIVATE).edit().remove("pending_fare").apply()
            // ★완료: 버튼 즉시 "취소?"로 전환 (GPS 안 기다림)
            com.callradar.app.TimingLog.send(this, "trip_end")   // [v24 진화⑥] 종료 누른 순간 기록
            stopPulse()                 // [v2] 운행 종료 → 펄스 멈춤
            pendingConfirm = true
            updateButton("취소?", "#EF4444")
            toast("운행 종료 — 3초 뒤 자동 기록 · 잘못 눌렀으면 지금 탭하면 취소")
            pendingDestLat = 0.0; pendingDestLng = 0.0; pendingDestAddr = ""
            // [플로팅 개선] 취소창 3초→5초 — 잘못 눌렀을 때 되돌릴 여유. 그 안에 다시 누르면 취소, 아니면 확정.
            confirmRunnable = Runnable {
                if (pendingConfirm) {
                    pendingConfirm = false
                    // ★확정 시점에 도착 GPS를 새로 잡음 (여유있게, 최대 10초). 잡히면 기록
                    updateButton("기록중", "#3B82F6")
                    toast("도착 위치 확인 중...")
                    captureLocation { lat, lng, addr ->
                        pendingDestLat = lat; pendingDestLng = lng; pendingDestAddr = addr
                        // [안전장치 1] 이동거리 체크 — 출발·도착 GPS 둘 다 확실할 때만
                        if (startLat != 0.0 && lat != 0.0) {
                            val dist = FloatArray(1)
                            Location.distanceBetween(startLat, startLng, lat, lng, dist)
                            // [플로팅 개선] 예전엔 거리 100m 미만이면 무조건 폐기 → 정체·단거리 실제 운행이 사라짐.
                            //  이제 100m 미만이라도 운행 시간이 2분 이상이면 정상 운행으로 기록. 거리·시간 둘 다 짧을 때만 폐기(제자리 연타).
                            val rideMsShort = parseUtc(startTime).let { if (it > 0L) System.currentTimeMillis() - it else 0L }
                            if (dist[0] < MIN_DISTANCE_M && rideMsShort < MIN_RIDE_MS) {
                                isRiding = false
                                stopLocationForeground(); clearRideState()
                                updateButtonSmall("🚕\n운행", "#F59E0B")
                                toast("이동거리·시간이 짧아 기록 안 함")
                                return@captureLocation
                            }
                        }
                        // [v2] 비정상 장시간 운행(3h+) = 깜빡 잊고 안 끈 것 → 기록 안 함(금액없는 유령 운행 방지)
                        val sMs = parseUtc(startTime)
                        if (sMs > 0L && System.currentTimeMillis() - sMs > MAX_RIDE_MS) {
                            isRiding = false
                            stopLocationForeground(); clearRideState()
                            updateButtonSmall("🚕\n운행", "#F59E0B")
                            toast("운행이 너무 길어(3시간+) 기록 안 함 — 필요하면 수동으로 추가하세요")
                            return@captureLocation
                        }
                        // GPS 하나라도 없으면 체크 건너뛰고 기록(아예 안하는것보단), 있으면 거리 통과분만
                        createTrip(startLat, startLng, startAddr, startTime, lat, lng, addr)
                        isRiding = false
                        stopLocationForeground(); clearRideState()
                        updateButtonSmall("🚕\n운행", "#F59E0B")
                        toast("운행 기록됨 · 잘못됐으면 기록 탭에서 삭제")
                    }
                }
            }
            confirmHandler.postDelayed(confirmRunnable!!, 3000)
        }
    }

    // [v27] 플로팅 버튼 공유 기능 제거 — 버튼은 시작/종료 전용.
    //  종료 탭 시 화면 캡처 → OCR로 요금만 읽어 자동 기입(endfare). 공유(shareCall)는 삭제됨.

    private fun toast(msg: String) {
        floatingView?.post { android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }

    // [v2] 운행중 펄스 시작/정지 (밝기 0.55~1.0 사인 호흡, ~3초 주기)
    // [v41 수정] 매 틱마다 floating_pulse 설정 재확인 → 운행 중에 껐을 때 즉시 멈춤(꺼도 깜빡이던 버그).
    private fun startPulse() {
        val enabled = getSharedPreferences("callradar_prefs", MODE_PRIVATE).getBoolean("floating_pulse", true)
        if (!enabled || pulseOn) return
        pulseOn = true; pulsePhase = 0.0
        pulseRunnable = object : Runnable {
            override fun run() {
                // 설정을 끄면(운행 중이라도) 다음 틱에서 즉시 정지 + 알파 원복
                if (!getSharedPreferences("callradar_prefs", MODE_PRIVATE).getBoolean("floating_pulse", true)) {
                    stopPulse(); return
                }
                pulsePhase += 0.115   // ~3초 주기 (55ms 틱 * 2π/0.115 ≈ 3000ms)
                val a = (0.55 + 0.45 * ((Math.sin(pulsePhase) + 1) / 2)).toFloat()
                floatingView?.alpha = a
                if (pulseOn) pulseHandler.postDelayed(this, 55)
            }
        }
        pulseHandler.post(pulseRunnable!!)
    }
    private fun stopPulse() {
        pulseOn = false
        pulseRunnable?.let { pulseHandler.removeCallbacks(it) }
        floatingView?.post { floatingView?.alpha = 1f }
    }

    /**
     * 버튼 배경 — 모양·투명도를 설정에서 고를 수 있다.
     *
     *  floating_shape : "circle"(기본) | "rect"
     *   원은 예쁘지만 가로 폭이 좁아 "회현동1가" 같은 긴 지명이 글자가 밖으로 나간다.
     *   사각형(둥근 모서리)은 같은 크기에서 가로를 더 쓸 수 있어 긴 이름이 들어간다.
     *
     *  floating_alpha : 100(불투명) ~ 40(많이 투명). 아래 앱 화면을 가린다는 의견이 있어 넣었다.
     */
    private fun ovalBg(colorHex: String): android.graphics.drawable.GradientDrawable {
        val p = getSharedPreferences("callradar_prefs", MODE_PRIVATE)
        val rect = p.getString("floating_shape", "circle") == "rect"
        val d = resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            if (rect) {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 14 * d
            } else {
                shape = android.graphics.drawable.GradientDrawable.OVAL
            }
            setColor(Color.parseColor(colorHex))
            setStroke((3 * d).toInt(), Color.WHITE)
        }
    }

    /** 설정한 투명도를 뷰에 적용 (운행중 펄스가 돌 때는 펄스가 알파를 쥐므로 건드리지 않는다) */
    private fun applyAlpha() {
        if (pulseOn) return
        val a = getSharedPreferences("callradar_prefs", MODE_PRIVATE).getInt("floating_alpha", 100)
        floatingView?.alpha = (a.coerceIn(30, 100)) / 100f
    }

    /**
     * 글자가 버튼 밖으로 나가지 않게 자동으로 줄인다.
     *  [유저지적] "회현동1가"처럼 긴 지명에서 폰트가 원을 벗어났다.
     *  가장 긴 줄의 글자 수로 크기를 정하고, 사각형이면 가로 여유가 있어 조금 더 키운다.
     */
    private fun fitTextSize(txt: String, base: Float): Float {
        val rect = getSharedPreferences("callradar_prefs", MODE_PRIVATE)
            .getString("floating_shape", "circle") == "rect"
        val longest = txt.split("\n").maxOfOrNull { it.length } ?: 0
        val lines = txt.count { it == '\n' } + 1
        var s = base
        if (longest >= 5) s -= 1.5f
        if (longest >= 6) s -= 1.5f
        if (longest >= 8) s -= 1.5f
        if (lines >= 3) s -= 1f
        if (rect) s += 1.5f          // 사각형은 가로가 넓어 더 크게 보여도 안 넘친다
        return s.coerceAtLeast(8f)
    }

    private fun updateButton(txt: String, colorHex: String) {
        floatingView?.post {
            floatingView?.textSize = fitTextSize(txt, 18f)
            floatingView?.setTextColor(Color.WHITE)
            floatingView?.typeface = android.graphics.Typeface.DEFAULT_BOLD
            floatingView?.text = txt
            floatingView?.background = ovalBg(colorHex)
            applyAlpha()
        }
    }

    // 출발지 동까지 두 줄로 표시할 때 (글자 작게 해서 64dp 안에 들어가게)
    private fun updateButtonSmall(txt: String, colorHex: String) {
        floatingView?.post {
            floatingView?.textSize = fitTextSize(txt, 12f)
            floatingView?.setTextColor(Color.WHITE)
            floatingView?.typeface = android.graphics.Typeface.DEFAULT_BOLD
            floatingView?.text = txt
            floatingView?.background = ovalBg(colorHex)
            applyAlpha()
        }
    }

    // GPS 현재 위치 1회 스냅샷 + 주소 변환
    @SuppressLint("MissingPermission")
    private fun captureLocation(cb: (Double, Double, String) -> Unit) {
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            cb(0.0, 0.0, "위치권한없음"); return
        }
        // [location FGS] 위치 캡처 동안만 잠깐 location 포그라운드 승격 → 백그라운드(카카오T·티맵 위)서도 GPS 잡힘. 캡처 끝나면 즉시 해제
        startLocationForeground()
        var done = false
        val emit = { lat: Double, lng: Double ->
            if (!done) {
                done = true
                if (!isRiding) stopLocationForeground()  // [v18] 운행중엔 유지(잠금 대비), 운행 아닐 때만 해제
                thread { val addr = if (lat != 0.0) reverseGeocode(lat, lng) else ""; cb(lat, lng, addr) }
            }
        }
        // ① 먼저 마지막 알려진 위치로 빠르게 채움 (백그라운드서도 즉시 확보되는 경우 많음)
        try {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && !done) emit(loc.latitude, loc.longitude)
            }
        } catch (e: Exception) {}
        // ② 정확한 최신 위치 요청 (오면 위에서 done이라 무시됨, 없었으면 이게 채움)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMaxUpdates(1).build()
        fusedClient.requestLocationUpdates(req, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                fusedClient.removeLocationUpdates(this)
                val loc = result.lastLocation
                if (loc != null) emit(loc.latitude, loc.longitude)
            }
        }, Looper.getMainLooper())
        // ③ 10초 타임아웃 — 그래도 못 잡으면 빈 위치로 (버튼 흐름은 안 막힘)
        confirmHandler.postDelayed({ if (!done) { done = true; if (!isRiding) stopLocationForeground(); cb(0.0, 0.0, "") } }, 10000)
    }

    // ===== location 포그라운드 (위치 캡처 중에만 잠깐) =====
    private val LOC_CHANNEL_ID = "callradar_location"
    private val LOC_NOTI_ID = 3001
    private var locForegroundOn = false
    private fun startLocationForeground() {
        if (locForegroundOn) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(LOC_CHANNEL_ID, "위치 기록", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
                nm.createNotificationChannel(ch)
            }
            val pi = try {
                val i = packageManager.getLaunchIntentForPackage(packageName)
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            } catch (e: Exception) { null }
            val noti = Notification.Builder(this, LOC_CHANNEL_ID)
                .setContentTitle("콜레이더 · 운행 기록 중")
                .setContentText("출발·도착 위치를 확인하고 있어요")
                .setOnlyAlertOnce(true)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .apply { if (pi != null) setContentIntent(pi) }
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(LOC_NOTI_ID, noti, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(LOC_NOTI_ID, noti)
            }
            locForegroundOn = true
        } catch (e: Exception) {}
    }
    private fun stopLocationForeground() {
        if (!locForegroundOn) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (e: Exception) {}
        locForegroundOn = false
    }

    // 좌표 → 동 이름
    private fun reverseGeocode(lat: Double, lng: Double): String {
        return try {
            val geo = Geocoder(this, Locale.KOREA)
            @Suppress("DEPRECATION")
            val list = geo.getFromLocation(lat, lng, 3)  // 여러 후보를 받아 그중 동 정보가 있는 걸 찾음(Geocoder가 호출마다 다른 레벨을 줘서 1개만 받으면 구로 떨어지는 문제 완화)
            if (!list.isNullOrEmpty()) {
                fun isDong(s: String?) = !s.isNullOrBlank() && (s.endsWith("동") || s.endsWith("읍") || s.endsWith("면"))
                // 모든 결과의 모든 주소 필드를 뒤져 "동/읍/면"으로 끝나는 첫 값을 찾음
                val dong = list.asSequence()
                    .flatMap { sequenceOf(it.subLocality, it.thoroughfare, it.subThoroughfare, it.locality, it.subAdminArea) }
                    .firstOrNull { isDong(it) }
                if (dong != null) return dong
                // 동이 하나도 없으면 getFromLocation 이 준 전체 주소(getAddressLine)에서 동 토큰을 탐색
                val fromLine = list.asSequence()
                    .mapNotNull { it.getAddressLine(0) }
                    .flatMap { it.split(" ").asSequence() }
                    .firstOrNull { isDong(it) }
                if (fromLine != null) return fromLine
                // 그래도 없으면 구/시라도, 그것도 없으면 미상
                val a = list[0]
                a.subLocality ?: a.locality ?: a.subAdminArea ?: "미상"
            } else "미상"
        } catch (e: Exception) { "미상" }
    }

    private fun utcNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    // 운행 생성 → 서버 전송 (source=gps, editable=true → 기사가 나중에 수정)
    private fun createTrip(oLat: Double, oLng: Double, oAddr: String, sTime: String, dLat: Double, dLng: Double, dAddr: String) {
        val uid = userId()
        if (uid.isEmpty()) return
        // 서버는 destName 필수 — GPS/주소 못잡아 비면 기본값으로 채움(저장 실패 방지)
        val originName = if (oAddr.isBlank()) "출발(미상)" else oAddr
        val destName = if (dAddr.isBlank()) "도착(미상)" else dAddr
        // [v24] 종료 캡처로 OCR된 금액(있으면) 사용 — 90초 내 값만
        val fp = getSharedPreferences("callradar_prefs", MODE_PRIVATE)
        val pFare = fp.getInt("pending_fare", 0)
        val pTs = fp.getLong("pending_fare_ts", 0L)
        val pRaw = fp.getString("pending_fare_raw", "") ?: ""   // [v24] 학습용 원문(ai 인식 근거)
        val useFare = if (pFare > 0 && System.currentTimeMillis() - pTs < 300000) pFare else 0  // [v43] 카드승인 알림 금액(직접결제)도 반영 — 90s→5분
        // [v25] 시작 때 판별한 플랫폼 자동 반영(3시간 내)
        val pPlat = fp.getString("pending_platform", "") ?: ""
        val pPlatTs = fp.getLong("pending_platform_ts", 0L)
        val usePlat = if (pPlat.isNotBlank() && System.currentTimeMillis() - pPlatTs < 3L * 60 * 60 * 1000) pPlat else "길빵/예약"
        fp.edit().remove("pending_fare").remove("pending_fare_ts").remove("pending_platform").remove("pending_platform_ts").apply()
        thread {
            // [v31] 로컬 우선: 먼저 로컬 DB에 저장 → 서버가 느리거나 죽어도 트립 유실 0. 서버 성공 시 synced 표시.
            val db = com.callradar.app.LocalTripDatabase.getInstance(this)
            val cuid = java.util.UUID.randomUUID().toString()   // [fix-B] 멱등키 — 재전송·타임아웃 중복 방지
            val localId = try { db.savePending(uid, usePlat, originName, destName, oLat, oLng, dLat, dLng, sTime, cuid) } catch (e: Exception) { -1L }
            if (useFare > 0 && localId > 0) try { db.updateFare(localId, useFare, usePlat) } catch (e: Exception) {}
            // [v93 일시정지 자동해제] 운행이 '확정 저장'된 지금에서야 일시정지를 푼다.
            //  로컬 DB 저장 성공을 기준으로 잡는다 — 서버 응답을 기다리면 오프라인·콜드스타트 기사는 영영 재개가 안 된다.
            //  여기까지 왔다는 건 3초 취소창을 넘기고 이동거리 검사도 통과했다는 뜻이다(탑승→취소는 여기 못 온다).
            if (localId > 0) {
                if (com.callradar.app.WorkResume.resumeIfPaused(this@FloatingTripService, "운행 저장")) {
                    confirmHandler.post { try { toast("일시정지 해제 — 근무 재개 (홈에서 되돌릴 수 있어요)") } catch (e: Exception) {} }
                }
            }
            if (localId > 0) com.callradar.app.LocalTripDatabase.handlingLocalIds.add(localId)   // [fix-B] 전송 중 중복 재전송 방지
            var tripId = 0
            try {
                val json = JSONObject().apply {
                    put("user_id", uid)
                    put("originName", originName)
                    put("destName", destName)
                    put("platform", usePlat)   // [v25] 시작 화면서 판별한 플랫폼 자동기록
                    put("payment_type", "cash")   // GPS 운행은 기본 현금(기사가 수정)
                    if (useFare > 0) put("fare", useFare)
                    put("source", "gps")
                    put("origin_lat", oLat); put("origin_lng", oLng)
                    put("dest_lat", dLat); put("dest_lng", dLng)
                    put("started_at", sTime)
                    put("ended_at", utcNow())
                    put("client_uuid", cuid)   // [fix-B] 멱등키
                }
                val conn = (URL("$SERVER_URL/api/trips/manual").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true; connectTimeout = 30000; readTimeout = 30000   // [v31] Render 콜드스타트(30~50초) 대응
                }
                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.flush() }
                val code = conn.responseCode
                val body = if (code in 200..299) try { conn.inputStream.bufferedReader().readText() } catch (e: Exception) { "" } else ""
                tripId = try { JSONObject(body).optInt("id", 0) } catch (e: Exception) { 0 }
                if (tripId > 0 && localId > 0) try { db.markSynced(localId, tripId) } catch (e: Exception) {}
            } catch (e: Exception) { /* 서버 실패 → 로컬 pending 유지, 앱 열 때 syncPendingTrips가 재전송 */ }
            finally { if (localId > 0) com.callradar.app.LocalTripDatabase.handlingLocalIds.remove(localId) }
            // [v18] 완료 후 금액 팝업 — 서버 성공이면 서버ID, 실패/오프라인이면 로컬ID로도 금액 입력 가능
            if (getSharedPreferences("callradar_prefs", MODE_PRIVATE).getBoolean("quick_entry_enabled", true)) {
                confirmHandler.post {
                    try {
                        startActivity(Intent(this, QuickEntryActivity::class.java).apply { putExtra("trip_id", tripId); putExtra("local_id", localId); putExtra("dest", destName); putExtra("ocr_fare", useFare); putExtra("ocr_raw", pRaw); putExtra("start_platform", usePlat); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    } catch (e: Exception) {}
                }
            } else if (useFare > 0) {
                com.callradar.app.Feedback.send(this@FloatingTripService, "amount", null, pRaw, useFare.toString(), useFare.toString())
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { getSharedPreferences("callradar_prefs", MODE_PRIVATE).edit().putString("overlay_died", java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(java.util.Date()) + " (onDestroy)").apply() } catch (e: Exception) {}
        confirmRunnable?.let { confirmHandler.removeCallbacks(it) }
        autoBadgeRunnable?.let { autoBadgeHandler.removeCallbacks(it) }
        stopPulse()
        try { floatingView?.let { windowManager.removeView(it) } } catch (e: Exception) {}
        try { shotView?.let { windowManager.removeView(it) } } catch (e: Exception) {}   // [v91] 캡처 버튼도 같이 정리
    }

    // [퇴근/앱종료] 최근앱에서 콜레이더를 스와이프로 지우면 버튼은 사라지되,
    // floating_on 설정은 유지 → 앱 다시 켜면 MainActivity onCreate에서 자동 복귀
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try { floatingView?.let { windowManager.removeView(it) } } catch (e: Exception) {}
        try { shotView?.let { windowManager.removeView(it) } } catch (e: Exception) {}   // [v91] 캡처 버튼도 같이 정리
        stopSelf()
    }
}
