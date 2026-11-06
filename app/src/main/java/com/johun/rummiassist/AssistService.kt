package com.johun.rummiassist

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.widget.Toast
import com.johun.rummiassist.core.Advisor
import com.johun.rummiassist.core.Recognizer
import com.johun.rummiassist.core.Tracker
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 접근성 서비스 하나로 캡처와 화면 위 표시를 모두 한다.
 *
 * 떠 있는 버튼을 누르면 실시간 분석이 켜진다(다시 누르면 끔). 약 0.4초마다 게임 창을 캡처해 인식하고,
 * 화면 내용이 바뀌면 이전 제안은 바로 지운 뒤, 같은 화면이 2번 연속 확인되면 새 제안을 그린다
 * → 지금 판과 손패로 만들 수 없는 옛 제안은 남지 않는다.
 *
 * Android 14+ 는 게임 창만 캡처(takeScreenshotOfWindow)하므로 우리 표시가 캡처에 섞이지 않아 깜빡임이 없다.
 * 그보다 낮거나 창 캡처가 실패하면 전체 화면 캡처 순간에만 표시를 숨긴다.
 */
class AssistService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val recognizer = Recognizer()

    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var highlight: HighlightView? = null
    private var panel: ResultPanel? = null

    private var live = false
    private var windowCapture = Build.VERSION.SDK_INT >= 34
    private var lastHash = 0L                 // 같은 화면이면 인식 생략 (worker 전용)
    private var lastHashRec: Frame? = null    // (worker 전용)
    private var shownSig: String? = null
    private var pendingSig: String? = null
    private var pendingCount = 0
    private var emptyStreak = 0
    private var shown: Frame? = null
    /** 첫 등록 전으로 바뀐 시점의 손패/판 장수: 여기서 손패가 3장 이상 판으로 나가면 등록한 것으로 본다. */
    private var meldBase: Pair<Int, Int>? = null
    /** 화살표를 그릴 단계(null = 전부) */
    private var focus: Int? = 1
    /** 사용자가 ◀▶ 로 고른 단계면 true (진행이 생기면 다시 자동) */
    private var manualFocus = false

    /**
     * 이번 턴의 고정 계획. 판+손패 구성이 같은 동안(타일을 옮기는 중)은 다시 계산하지 않고 진행만 따라간다
     * → 옮기는 도중의 어중간한 판으로 새 계획을 세워 계획이 계속 바뀌는 일이 없다.
     */
    private var plan: Advisor.Advice? = null
    private var lastDone = -1

    /** 인식 결과 + 게임 창의 화면상 위치 */
    private class Frame(val rec: Recognizer.Result, val x: Int, val y: Int)

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    /** 첫 등록(30점) 완료 여부. 기본은 완료. 직접 끄면, 손패가 3장 이상 판으로 나간 뒤 다시 완료로. */
    private var meldDone: Boolean
        get() = prefs.getBoolean("meldDone", true)
        set(v) = prefs.edit().putBoolean("meldDone", v).apply()
    private var showLabels: Boolean
        get() = prefs.getBoolean("showLabels", false)
        set(v) = prefs.edit().putBoolean("showLabels", v).apply()
    /** 손패 조커 사용 여부. 기본은 아껴 둠. */
    private var useJoker: Boolean
        get() = prefs.getBoolean("useJoker", false)
        set(v) = prefs.edit().putBoolean("useJoker", v).apply()

    /** 앱 화면의 「루미큐브 분석」 스위치. 꺼지면 버튼과 캡처 루프를 모두 없애 아무 일도 하지 않는다. */
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_ENABLED) applyEnabled()
    }

    // ------------------------------------------------------------ 루미큐브 앱 감지

    /** 루미큐브 앱이 지금 앞에 떠 있는가 */
    private var gameFg = false
    /** 이번 게임에서 사용자가 「켜기」를 눌렀는가(게임을 잠깐 나갔다 10분 안에 오면 다시 묻지 않음) */
    private var activated = false
    private var leftAt = 0L
    private val gamePkg get() = prefs.getString(KEY_GAME_PKG, GAME_PKG) ?: GAME_PKG

    private val actionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            when (i.action) {
                ACTION_ACTIVATE -> showAssistant()
                ACTION_DISMISS -> { activated = false; cancelAsk() }
            }
        }
    }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // 기본값: 첫 등록 완료 + 패널 최소화 (업데이트한 기기도 한 번 맞춤)
        if (!prefs.contains("defaults_v8")) prefs.edit().putBoolean("meldDone", true).putBoolean("panelMin", true).putBoolean("defaults_v8", true).apply()
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "루미큐브 실행 알림", android.app.NotificationManager.IMPORTANCE_HIGH))
        androidx.core.content.ContextCompat.registerReceiver(
            this, actionReceiver,
            android.content.IntentFilter().apply { addAction(ACTION_ACTIVATE); addAction(ACTION_DISMISS) },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        applyEnabled()
    }

    /** 스위치가 꺼지면 앱 전환 이벤트도, 주기 확인도 하지 않는다(배터리 0). */
    private fun applyEnabled() {
        val on = prefs.getBoolean(KEY_ENABLED, true)
        serviceInfo = serviceInfo.apply { eventTypes = if (on) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED else 0 }
        main.removeCallbacks(fgPoll)
        if (on) main.post(fgPoll) else { activated = false; gameFg = false; hideAssistant(); cancelAsk() }
    }

    /**
     * 앞 화면 앱 판단: 창 전환 이벤트 직후 두 번(0.3초, 0.8초) + 스위치가 켜져 있는 동안 1.5초마다 가볍게 확인
     * (이미 열려 있던 앱으로 돌아갈 땐 전환 이벤트가 오지 않기도 한다). 같은 앱이 두 번 연속 보일 때만 반영
     * → 전환 애니메이션 중 잠깐 보이는 이전 앱 때문에 켜졌다 꺼졌다 하지 않는다. 창 목록만 보며 화면 캡처는 하지 않는다.
     */
    private val fgCheck = Runnable { checkForeground() }
    private val fgPoll = object : Runnable {
        override fun run() {
            if (!prefs.getBoolean(KEY_ENABLED, true)) return
            checkForeground()
            main.postDelayed(this, 1500)
        }
    }
    private var candidate: String? = null
    private var candidateCount = 0

    /** 창 번호 → 앱 패키지(전환 이벤트에서 기억). 창 내용을 못 읽는 앱(게임 등)도 이걸로 알아본다. */
    private val windowPkg = HashMap<Int, String>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = event.packageName?.toString()
        if (p != null && event.windowId >= 0) { windowPkg[event.windowId] = p; if (windowPkg.size > 64) windowPkg.remove(windowPkg.keys.first()) }
        main.removeCallbacks(fgCheck)
        main.postDelayed(fgCheck, 300)
        main.postDelayed(fgCheck, 800)
    }

    /** 포커스된 일반 앱 창의 패키지. 알림창·키보드·우리 표시는 앱 창이 아니라서 바뀌지 않는다. */
    private fun foregroundPackage(): String? {
        val w = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .let { l -> l.firstOrNull { it.isFocused } ?: l.firstOrNull { it.isActive } } ?: return null
        val pkg = w.root?.packageName?.toString()?.also { windowPkg[w.id] = it } ?: windowPkg[w.id]
        val title = w.title?.toString()
        // 게임 창 제목을 기억해 두면, 서비스가 게임보다 늦게 켜져 창을 못 알아볼 때도 제목으로 찾는다
        if (pkg == gamePkg && !title.isNullOrEmpty() && prefs.getString("gameTitle", null) != title) prefs.edit().putString("gameTitle", title).apply()
        if (pkg == null && title != null && title == prefs.getString("gameTitle", null)) return gamePkg
        return pkg
    }

    private fun checkForeground() {
        if (!prefs.getBoolean(KEY_ENABLED, true)) return
        val pkg = foregroundPackage() ?: return
        if (pkg == candidate) candidateCount++ else { candidate = pkg; candidateCount = 1 }
        if (candidateCount < 2) return
        if (pkg == gamePkg) {
            if (gameFg) return
            gameFg = true
            if (activated && System.currentTimeMillis() - leftAt < 10 * 60_000) showAssistant() else askToActivate()
        } else if (pkg != packageName && gameFg) {
            gameFg = false
            leftAt = System.currentTimeMillis()
            hideAssistant()
            cancelAsk()
        }
    }

    /** 「루미큐브가 실행됐어요 — 어시스턴트를 켤까요?」 알림. 알림이 꺼져 있으면 버튼만 띄운다. */
    private fun askToActivate() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) { if (bubble == null) addBubble(); return }
        fun pi(action: String, code: Int) = android.app.PendingIntent.getBroadcast(
            this, code, android.content.Intent(action).setPackage(packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = android.app.Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("루미큐브가 실행됐어요")
            .setContentText("어시스턴트를 켤까요? 누르면 바로 실시간 분석을 시작합니다.")
            .setContentIntent(pi(ACTION_ACTIVATE, 1))
            .addAction(android.app.Notification.Action.Builder(null, "켜기", pi(ACTION_ACTIVATE, 1)).build())
            .addAction(android.app.Notification.Action.Builder(null, "이번엔 안 함", pi(ACTION_DISMISS, 2)).build())
            .setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_RECOMMENDATION)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    private fun cancelAsk() = getSystemService(android.app.NotificationManager::class.java).cancel(NOTIF_ID)

    /** 버튼을 띄우고 바로 실시간 분석 시작 */
    private fun showAssistant() {
        activated = true
        cancelAsk()
        if (bubble == null) addBubble()
        if (!live) startLive()
    }

    private fun hideAssistant() {
        stopLive()
        bubble?.let { wm.removeView(it) }
        bubble = null
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 회전·펼침으로 화면 크기가 바뀌면 버튼과 패널을 화면 안으로
        main.postDelayed({ clampBubble(); panel?.clamp() }, 300)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        main.removeCallbacks(fgPoll)
        runCatching { unregisterReceiver(actionReceiver) }
        cancelAsk()
        stopLive()
        bubble?.let { wm.removeView(it) }
        bubble = null
        worker.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 떠 있는 버튼

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val d = resources.displayMetrics.density
        val size = (52 * d).toInt()
        val v = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            elevation = 6 * d
        }
        val lp = overlayParams(size, size, touchable = true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bx", (8 * d).toInt())
            y = prefs.getInt("by", (200 * d).toInt())
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        val longPress = Runnable { dragging = true; stopLive(); toast("실시간 분석을 껐어요") }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                    main.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; main.removeCallbacks(longPress) }
                    if (dragging) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); clampBubble() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (dragging) prefs.edit().putInt("bx", lp.x).putInt("by", lp.y).apply()
                    else if (e.actionMasked == MotionEvent.ACTION_UP) if (live) stopLive() else startLive()
                }
            }
            true
        }
        wm.addView(v, lp)
        bubble = v; bubbleLp = lp
        updateBubble()
        v.post { clampBubble() }
    }

    /** 버튼이 화면 밖으로 나가지 않게 */
    private fun clampBubble() {
        val v = bubble ?: return
        val lp = bubbleLp ?: return
        val b = wm.currentWindowMetrics.bounds
        val w = if (v.width > 0) v.width else lp.width
        val h = if (v.height > 0) v.height else lp.height
        lp.x = lp.x.coerceIn(0, maxOf(0, b.width() - w))
        lp.y = lp.y.coerceIn(0, maxOf(0, b.height() - h))
        wm.updateViewLayout(v, lp)
    }

    private fun updateBubble() {
        val v = bubble ?: return
        val d = resources.displayMetrics.density
        v.text = if (live) "LIVE" else "루미"
        v.background = roundRect(if (live) 0xE02E7D32.toInt() else 0xE01F4FA8.toInt(), 26 * d, 0xFFF2EFE6.toInt(), 2 * d)
    }

    // ------------------------------------------------------------ 실시간 루프

    private val tick = Runnable { if (live) captureOnce() }

    private fun schedule(ms: Long) {
        main.removeCallbacks(tick)
        if (live) main.postDelayed(tick, ms)
    }

    private fun startLive() {
        live = true
        shownSig = null; pendingSig = null; pendingCount = 0; emptyStreak = 0
        updateBubble()
        showStatus("분석 중…")
        schedule(0)
    }

    private fun stopLive() {
        live = false
        plan = null
        main.removeCallbacks(tick)
        updateBubble()
        clearResults()
    }

    private fun captureOnce() {
        if (!(getSystemService(POWER_SERVICE) as PowerManager).isInteractive) return schedule(3000)
        if (windowCapture && Build.VERSION.SDK_INT >= 34) {
            val w = gameWindow()
            if (w != null) {
                val b = Rect().also { w.getBoundsInScreen(it) }
                takeScreenshotOfWindow(w.id, worker, callback(b.left, b.top, fullScreen = false))
                return
            }
        }
        // 전체 화면 캡처: 우리 표시가 섞이지 않게 잠깐 숨긴다.
        setOverlaysVisible(false)
        main.postDelayed({ takeScreenshot(Display.DEFAULT_DISPLAY, worker, callback(0, 0, fullScreen = true)) }, 120)
    }

    /** 포커스된(없으면 활성, 그것도 없으면 가장 큰) 일반 앱 창. 우리 표시는 접근성 오버레이 창이라 제외된다. */
    private fun gameWindow(): AccessibilityWindowInfo? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive }
            ?: apps.maxByOrNull { w -> Rect().also { w.getBoundsInScreen(it) }.let { it.width() * it.height() } }
    }

    private fun callback(ox: Int, oy: Int, fullScreen: Boolean) = object : TakeScreenshotCallback {
        override fun onSuccess(result: ScreenshotResult) {
            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
            val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
            hw?.recycle()
            result.hardwareBuffer.close()
            if (fullScreen) main.post { setOverlaysVisible(true) }
            if (copy == null) { main.post { schedule(LIVE_MS) }; return }
            val bmp = if (fullScreen) uprightToScreen(copy) else copy
            val frame = try { recognize(bmp, ox, oy) } catch (t: Throwable) { null }
            main.post { if (frame != null) onFrame(frame) else schedule(LIVE_MS) }
        }

        override fun onFailure(errorCode: Int) {
            main.post {
                if (fullScreen) setOverlaysVisible(true)
                when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> schedule(400)
                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> { showStatus("게임이 화면 캡처를 막고 있어요"); schedule(2000) }
                    else -> {
                        // 창 캡처가 안 되는 기기/게임이면 전체 화면 캡처로 바꾼다.
                        if (!fullScreen) windowCapture = false
                        schedule(LIVE_MS)
                    }
                }
            }
        }
    }

    /** worker 스레드: 이전과 픽셀이 같으면 인식을 건너뛴다. */
    private fun recognize(bmp: Bitmap, ox: Int, oy: Int): Frame {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var hash = (w.toLong() shl 32) xor h.toLong() xor (ox.toLong() shl 16) xor (oy.toLong() shl 48)
        var i = 0
        while (i < px.size) { hash = hash * 31 + px[i]; i += 97 }
        lastHashRec?.let { if (hash == lastHash) return it }
        val f = Frame(recognizer.recognize(px, w, h), ox, oy)
        lastHash = hash; lastHashRec = f
        return f
    }

    /** 캡처가 현재 화면 방향과 가로/세로가 다르면 화면 방향으로 돌린다. */
    private fun uprightToScreen(bmp: Bitmap): Bitmap {
        val b = wm.currentWindowMetrics.bounds
        val screenLandscape = b.width() > b.height()
        if ((bmp.width > bmp.height) == screenLandscape) return bmp
        @Suppress("DEPRECATION")
        val rot = wm.defaultDisplay.rotation
        val m = Matrix().apply { postRotate(360f - rot * 90f) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, false)
    }

    private fun onFrame(f: Frame) {
        if (!live) return
        if (f.rec.tiles.isEmpty()) {
            // 게임 화면이 아님: 표시를 치우고 천천히 확인
            if (++emptyStreak >= 2) { shownSig = null; pendingSig = null; clearResults() }
            return schedule(1500)
        }
        emptyStreak = 0
        val sig = signature(f)
        if (sig != shownSig) {
            if (sig == pendingSig) pendingCount++ else { pendingSig = sig; pendingCount = 1 }
            if (pendingCount >= 2) {
                shownSig = sig
                updateMeld(f)
                shown = f
                render()
            } else if (shownSig != null) {
                // 화면이 바뀜: 옛 제안은 바로 지운다.
                shownSig = null; shown = null
                highlight?.setAdvice(null, false)
                showStatus("화면 변화 감지 — 분석 중…")
            }
        }
        // 전체 화면 캡처 방식은 캡처마다 표시를 숨기므로 덜 자주.
        schedule(if (windowCapture) LIVE_MS else 1000)
    }

    private fun updateMeld(f: Frame) {
        val hand = f.rec.hand.size; val board = f.rec.board.size
        if (meldDone) return
        val base = meldBase ?: (hand to board).also { meldBase = it }
        val played = base.first - hand
        if (played >= 3 && board - base.second >= played) { meldDone = true; meldBase = null }
    }

    /** 타일 종류·위치(8px 단위)·창 위치가 같으면 같은 화면 */
    private fun signature(f: Frame) = buildString {
        append(f.x).append(',').append(f.y).append('|')
        for (t in f.rec.tiles.sortedWith(compareBy({ it.glyph.cy / 8 }, { it.glyph.cx / 8 })))
            append(t.kind).append(if (t.inHand) 'h' else 'b').append(t.glyph.cx / 8).append(',').append(t.glyph.cy / 8).append(';')
    }

    // ------------------------------------------------------------ 결과 표시

    private fun render() {
        val f = shown ?: return
        var advice = plan
        var prog: Tracker.Progress? = null
        if (advice != null && Tracker.sameTotals(advice.recognized, f.rec) && sameTileSize(advice.recognized, f.rec)) {
            prog = Tracker.track(advice, f.rec)
            if (prog.deviated) { advice = null; prog = null } // 계획과 다르게 옮김 → 지금 판에서 다시
        } else advice = null
        if (advice == null) {
            advice = try {
                Advisor.advise(f.rec, meldDone, useJoker)
            } catch (t: Throwable) {
                return showStatus("계산 오류: ${t.message}")
            }
            plan = advice
            prog = Tracker.track(advice, f.rec)
            focus = 1; manualFocus = false; lastDone = -1
        }
        // 진행이 생기면 아직 안 끝난 첫 단계로 자동 이동
        val doneNow = prog!!.done.size
        if (doneNow != lastDone) { manualFocus = false; lastDone = doneNow }
        if (!manualFocus) focus = advice.steps.firstOrNull { !it.blocked && !prog.stepDone(it) }?.number
            ?: advice.steps.firstOrNull()?.number
        val hv = highlight ?: HighlightView(this).also {
            wm.addView(it, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, touchable = false))
            highlight = it
            panel?.bringToFront()
        }
        val fNow = focus?.takeIf { n -> advice.steps.any { it.number == n } }
        hv.setAdvice(advice, showLabels, f.x, f.y, fNow, prog)
        showPanel(advice, null, fNow, prog)
    }

    private fun showStatus(msg: String) {
        if (!live) return toast(msg)
        showPanel(null, msg)
    }

    private fun sameTileSize(a: Recognizer.Result, b: Recognizer.Result): Boolean {
        val wa = a.board.firstOrNull()?.rect?.width ?: return true
        val wb = b.board.firstOrNull()?.rect?.width ?: return true
        return kotlin.math.abs(wa - wb) <= wa * 0.1 // 판 타일이 줄어들면(게임이 재배치) 자리 계산을 다시
    }

    private fun showPanel(advice: Advisor.Advice?, status: String?, shownFocus: Int? = null, prog: Tracker.Progress? = null) {
        val p = panel ?: ResultPanel(this, wm).also { panel = it }
        p.show(
            advice, status, meldDone, showLabels, useJoker,
            onMeldToggle = { meldDone = it; meldBase = null; plan = null; render() },
            onLabelsToggle = { showLabels = it; render() },
            onJokerToggle = { useJoker = it; plan = null; render() },
            onClose = { stopLive() },
            focus = shownFocus,
            // n = 단계 번호, 0 = 전체
            onFocus = { n -> focus = if (n == 0) null else n; manualFocus = true; render() },
            progress = prog,
        )
        bringBubbleToFront()
    }

    private fun bringBubbleToFront() {
        val v = bubble ?: return
        val lp = bubbleLp ?: return
        wm.removeView(v)
        wm.addView(v, lp)
    }

    private fun setOverlaysVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.INVISIBLE
        highlight?.visibility = vis
        panel?.setVisible(visible)
        bubble?.visibility = vis
    }

    private fun clearResults() {
        highlight?.let { wm.removeView(it) }
        highlight = null
        panel?.remove()
        panel = null
        shown = null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val KEY_ENABLED = "enabled"
        /** 감지할 게임 패키지(디버그 빌드에서만 바꿀 수 있음) */
        const val KEY_GAME_PKG = "gamePackage"
        const val GAME_PKG = "com.rummikubfree"
        const val CHANNEL = "game_detected"
        const val NOTIF_ID = 1
        const val ACTION_ACTIVATE = "com.johun.rummiassist.ACTIVATE"
        const val ACTION_DISMISS = "com.johun.rummiassist.DISMISS"

        /** 실시간 캡처 간격(시스템 제한 약 0.33초) */
        const val LIVE_MS = 400L

        fun overlayParams(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            gravity = Gravity.TOP or Gravity.START
        }
    }
}
