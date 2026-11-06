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

    /** 인식 결과 + 게임 창의 화면상 위치 */
    private class Frame(val rec: Recognizer.Result, val x: Int, val y: Int)

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private var meldDone: Boolean
        get() = prefs.getBoolean("meldDone", false)
        set(v) = prefs.edit().putBoolean("meldDone", v).apply()
    private var showLabels: Boolean
        get() = prefs.getBoolean("showLabels", false)
        set(v) = prefs.edit().putBoolean("showLabels", v).apply()
    /** 손패 조커 사용 여부. 기본은 아껴 둠. */
    private var useJoker: Boolean
        get() = prefs.getBoolean("useJoker", false)
        set(v) = prefs.edit().putBoolean("useJoker", v).apply()

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
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
                    if (dragging) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); wm.updateViewLayout(v, lp) }
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
                if (f.rec.board.isEmpty()) meldDone = false // 판이 비었으면 새 게임
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

    /** 타일 종류·위치(8px 단위)·창 위치가 같으면 같은 화면 */
    private fun signature(f: Frame) = buildString {
        append(f.x).append(',').append(f.y).append('|')
        for (t in f.rec.tiles.sortedWith(compareBy({ it.glyph.cy / 8 }, { it.glyph.cx / 8 })))
            append(t.kind).append(if (t.inHand) 'h' else 'b').append(t.glyph.cx / 8).append(',').append(t.glyph.cy / 8).append(';')
    }

    // ------------------------------------------------------------ 결과 표시

    private fun render() {
        val f = shown ?: return
        val advice = try {
            Advisor.advise(f.rec, meldDone, useJoker)
        } catch (t: Throwable) {
            return showStatus("계산 오류: ${t.message}")
        }
        val hv = highlight ?: HighlightView(this).also {
            wm.addView(it, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, touchable = false))
            highlight = it
        }
        hv.setAdvice(advice, showLabels, f.x, f.y)
        showPanel(advice, null)
    }

    private fun showStatus(msg: String) {
        if (!live) return toast(msg)
        showPanel(null, msg)
    }

    private fun showPanel(advice: Advisor.Advice?, status: String?) {
        val p = panel ?: ResultPanel(this, wm).also { panel = it }
        p.show(
            advice, status, meldDone, showLabels, useJoker,
            onMeldToggle = { meldDone = it; render() },
            onLabelsToggle = { showLabels = it; render() },
            onJokerToggle = { useJoker = it; render() },
            onClose = { stopLive() },
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
