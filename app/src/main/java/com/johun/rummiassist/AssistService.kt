package com.johun.rummiassist

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import com.johun.rummiassist.core.Advisor
import com.johun.rummiassist.core.Recognizer
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 접근성 서비스 하나로 캡처(takeScreenshot)와 화면 위 표시(TYPE_ACCESSIBILITY_OVERLAY)를 모두 한다.
 * 떠 있는 버튼을 누르면: 표시를 잠깐 숨김 → 캡처 → 인식/계산(백그라운드) → 결과 표시.
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
    private var busy = false
    private var lastRec: Recognizer.Result? = null

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private var meldDone: Boolean
        get() = prefs.getBoolean("meldDone", false)
        set(v) = prefs.edit().putBoolean("meldDone", v).apply()
    private var showLabels: Boolean
        get() = prefs.getBoolean("showLabels", false)
        set(v) = prefs.edit().putBoolean("showLabels", v).apply()

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        clearResults()
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
            text = "루미"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = roundRect(0xE01F4FA8.toInt(), size / 2f, 0xFFF2EFE6.toInt(), 2 * d)
            elevation = 6 * d
        }
        val lp = overlayParams(size, size, touchable = true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bx", (8 * d).toInt())
            y = prefs.getInt("by", (200 * d).toInt())
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        val longPress = Runnable { dragging = true; clearResults(); toast("표시를 지웠어요") }
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
                    else if (e.actionMasked == MotionEvent.ACTION_UP) analyze()
                }
            }
            true
        }
        wm.addView(v, lp)
        bubble = v; bubbleLp = lp
    }

    // ------------------------------------------------------------ 캡처 → 분석

    private fun analyze() {
        if (busy) return
        busy = true
        bubble?.text = "…"
        // 우리 표시가 캡처에 섞이지 않도록 숨긴 뒤 한 프레임 이상 기다린다.
        setOverlaysVisible(false)
        main.postDelayed({ capture(retry = true) }, 180)
    }

    private fun capture(retry: Boolean) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                hw?.recycle()
                result.hardwareBuffer.close()
                setOverlaysVisible(true)
                if (bmp == null) return fail("캡처 이미지를 읽지 못했어요")
                val upright = uprightToScreen(bmp)
                worker.execute { process(upright) }
            }

            override fun onFailure(errorCode: Int) {
                if (retry && errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    main.postDelayed({ capture(retry = false) }, 400)
                    return
                }
                setOverlaysVisible(true)
                fail(
                    if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) "게임이 화면 캡처를 막고 있어요"
                    else "화면 캡처 실패 (코드 $errorCode)"
                )
            }
        })
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

    private fun process(bmp: Bitmap) {
        try {
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val rec = recognizer.recognize(px, w, h)
            main.post {
                // 판이 비어 있으면 아직 아무도 등록 전 = 새 게임으로 본다.
                if (rec.board.isEmpty()) meldDone = false
                lastRec = rec
                show(rec)
                busy = false
                bubble?.text = "루미"
            }
        } catch (t: Throwable) {
            main.post { fail("분석 오류: ${t.message}") }
        }
    }

    private fun fail(msg: String) {
        busy = false
        bubble?.text = "루미"
        toast(msg)
    }

    // ------------------------------------------------------------ 결과 표시

    private fun show(rec: Recognizer.Result) {
        val advice = try {
            Advisor.advise(rec, meldDone)
        } catch (t: Throwable) {
            return fail("계산 오류: ${t.message}")
        }
        if (rec.tiles.isEmpty()) {
            clearResults()
            toast("타일을 찾지 못했어요. 게임 화면에서 눌러 주세요")
            return
        }
        val hv = highlight ?: HighlightView(this).also {
            wm.addView(it, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, touchable = false))
            highlight = it
        }
        hv.setAdvice(advice, showLabels)

        val p = panel ?: ResultPanel(this, wm).also { panel = it }
        p.show(
            advice, meldDone, showLabels,
            onMeldToggle = { meldDone = it; lastRec?.let(::show) },
            onLabelsToggle = { showLabels = it; lastRec?.let(::show) },
            onRefresh = { analyze() },
            onClose = { clearResults() },
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
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
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
