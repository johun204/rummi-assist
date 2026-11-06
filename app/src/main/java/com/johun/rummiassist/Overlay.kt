package com.johun.rummiassist

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.johun.rummiassist.core.Advisor
import com.johun.rummiassist.core.SetTile

/** 세트 번호별 표시 색 */
val SET_COLORS = intArrayOf(
    0xFFFFEB3B.toInt(), 0xFF00E5FF.toInt(), 0xFFFF4081.toInt(), 0xFF76FF03.toInt(),
    0xFFFF9100.toInt(), 0xFFE040FB.toInt(), 0xFFFFFFFF.toInt(), 0xFF64FFDA.toInt(),
)

fun setColor(n: Int) = SET_COLORS[(n - 1) % SET_COLORS.size]

fun roundRect(fill: Int, radius: Float, stroke: Int = 0, strokeW: Float = 0f, dashed: Boolean = false) =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
        if (strokeW > 0) {
            if (dashed) setStroke(strokeW.toInt(), stroke, strokeW * 2, strokeW * 1.5f) else setStroke(strokeW.toInt(), stroke)
        }
    }

/** 게임 화면 위에 낼 타일(실선)과 옮길 판 타일(점선)을 세트 번호와 함께 그린다. 터치는 통과. */
class HighlightView(context: Context) : View(context) {
    private var advice: Advisor.Advice? = null
    private var labels = false
    /** 인식 좌표(게임 창 기준) → 화면 좌표 */
    private var winX = 0
    private var winY = 0
    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = 0xFF000000.toInt()
    }
    private val dash = DashPathEffect(floatArrayOf(10 * d, 6 * d), 0f)
    private val loc = IntArray(2)

    fun setAdvice(a: Advisor.Advice?, showLabels: Boolean, windowX: Int = 0, windowY: Int = 0) {
        advice = a; labels = showLabels; winX = windowX; winY = windowY; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val a = advice ?: return
        getLocationOnScreen(loc)
        val ox = (loc[0] - winX).toFloat(); val oy = (loc[1] - winY).toFloat()
        if (labels) {
            text.textSize = 12 * d
            for (t in a.recognized.tiles) {
                val r = t.rect
                val bx = (r.left + r.right) / 2f - ox
                val by = r.top - oy + 2 * d
                fill.color = if (t.inHand) 0xCC2E7D32.toInt() else 0xCC1565C0.toInt()
                c.drawRoundRect(RectF(bx - 16 * d, by, bx + 16 * d, by + 16 * d), 4 * d, 4 * d, fill)
                text.color = 0xFFFFFFFF.toInt()
                c.drawText(t.kind.toString(), bx, by + 12 * d, text)
            }
        }
        for (set in a.sets) {
            val col = setColor(set.number)
            for (pt in set.tiles) {
                val s = pt.screen ?: continue
                if (!pt.tile.fromHand && !pt.moved) continue
                val r = RectF(s.rect.left - ox, s.rect.top - oy, s.rect.right - ox, s.rect.bottom - oy)
                r.inset(2 * d, 2 * d)
                stroke.color = col
                stroke.pathEffect = if (pt.tile.fromHand) null else dash
                c.drawRoundRect(r, 6 * d, 6 * d, stroke)
                // 세트 번호 배지
                val rad = 11 * d
                fill.color = col
                c.drawCircle(r.left + rad * 0.6f, r.top + rad * 0.6f, rad, fill)
                text.color = 0xFF000000.toInt()
                text.textSize = 13 * d
                c.drawText(set.number.toString(), r.left + rad * 0.6f, r.top + rad * 0.6f + 4.5f * d, text)
            }
        }
    }
}

/** 추천 세트 목록 패널. 머리 부분을 끌어 옮길 수 있다. */
class ResultPanel(private val ctx: Context, private val wm: WindowManager) {
    private val d = ctx.resources.displayMetrics.density
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var root: LinearLayout? = null
    private val lp = AssistService.overlayParams(
        (300 * d).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, touchable = true,
    ).apply {
        x = prefs.getInt("px", (70 * d).toInt())
        y = prefs.getInt("py", (8 * d).toInt())
    }

    fun setVisible(v: Boolean) { root?.visibility = if (v) View.VISIBLE else View.INVISIBLE }

    fun remove() {
        root?.let { wm.removeView(it) }
        root = null
    }

    /** a 가 null 이면 제안 없이 status 문구만(화면이 바뀌는 중 등). */
    fun show(
        a: Advisor.Advice?, status: String?, meldDone: Boolean, labels: Boolean, useJoker: Boolean,
        onMeldToggle: (Boolean) -> Unit, onLabelsToggle: (Boolean) -> Unit, onJokerToggle: (Boolean) -> Unit,
        onClose: () -> Unit,
    ) {
        val r = root ?: LinearLayout(ctx).also {
            it.orientation = LinearLayout.VERTICAL
            it.background = roundRect(0xEE102040.toInt(), 12 * d, 0xFF4A6FB5.toInt(), 1 * d)
            it.setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            wm.addView(it, lp)
            root = it
        }
        r.removeAllViews()

        // 머리: 제목(끌기) + 닫기
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("루미 도우미 · 실시간  ⠿", 13f, 0xFF9FB8E8.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("✕", 18f, 0xFFFFFFFF.toInt()).apply {
            setPadding((10 * d).toInt(), 0, (4 * d).toInt(), 0)
            setOnClickListener { onClose() }
        })
        makeDraggable(header, r)
        r.addView(header)

        if (a == null) {
            r.addView(label(status ?: "", 15f, 0xFFFFFFFF.toInt(), bold = true))
        } else {
            r.addView(label(a.message, 16f, 0xFFFFFFFF.toInt(), bold = true))
            a.jokerHint?.let { r.addView(label("☺ $it", 12f, 0xFFFFEB3B.toInt())) }
            r.addView(label(
                "인식: 판 ${a.recognized.board.size}장 · 손패 ${a.recognized.hand.size}장" +
                    (if (a.keptSets > 0 && a.sets.isNotEmpty()) " · 그대로 둘 세트 ${a.keptSets}개" else ""),
                12f, 0xFFB0C4E8.toInt(),
            ))
            for (w in a.warnings) r.addView(label("⚠ $w", 12f, 0xFFFF8A80.toInt()))
            if (a.sets.isNotEmpty()) {
                val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                for (s in a.sets) list.addView(setRow(s))
                val scroll = MaxHeightScroll(ctx, (ctx.resources.displayMetrics.heightPixels * 0.55f).toInt())
                scroll.addView(list)
                r.addView(scroll)
                r.addView(label("실선 = 손패에서 낼 타일 · 점선 = 판에서 옮길 타일", 11f, 0xFF9FB8E8.toInt()))
            }
        }

        val opts = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        opts.addView(check("첫 등록 완료", meldDone, onMeldToggle))
        opts.addView(check("조커 사용", useJoker, onJokerToggle))
        opts.addView(check("인식 표시", labels, onLabelsToggle))
        r.addView(opts)
    }

    private fun setRow(s: Advisor.PlanSet): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (3 * d).toInt(), 0, (3 * d).toInt())
        }
        val col = setColor(s.number)
        row.addView(TextView(ctx).apply {
            text = s.number.toString()
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF000000.toInt())
            gravity = Gravity.CENTER
            background = roundRect(col, 10 * d)
        }, LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()))
        row.addView(label(s.status.label, 11f, col).apply { setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0) },
            LinearLayout.LayoutParams((44 * d).toInt(), -2))
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (pt in s.tiles) chips.addView(chip(pt.tile, pt.tile.fromHand, pt.moved, col))
        row.addView(chips)
        return row
    }

    private fun chip(t: SetTile, fromHand: Boolean, moved: Boolean, col: Int): View = TextView(ctx).apply {
        text = if (t.joker) "☺${t.value}" else t.value.toString()
        textSize = if (t.joker) 10f else 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(t.color.argb)
        background = when {
            fromHand -> roundRect(0xFFF2EFE6.toInt(), 4 * d, col, 3 * d)
            moved -> roundRect(0xFFF2EFE6.toInt(), 4 * d, col, 2 * d, dashed = true)
            else -> roundRect(0xFFCFCBC0.toInt(), 4 * d)
        }
        layoutParams = LinearLayout.LayoutParams((24 * d).toInt(), (30 * d).toInt()).apply { marginEnd = (2 * d).toInt() }
    }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
    }

    private fun check(s: String, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(ctx).apply {
        text = s; textSize = 12f; setTextColor(0xFFFFFFFF.toInt()); isChecked = checked
        buttonTintList = android.content.res.ColorStateList.valueOf(0xFFFFEB3B.toInt())
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(handle: View, win: View) {
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - dx).toInt(); lp.y = sy + (e.rawY - dy).toInt()
                    wm.updateViewLayout(win, lp)
                }
                MotionEvent.ACTION_UP -> prefs.edit().putInt("px", lp.x).putInt("py", lp.y).apply()
            }
            true
        }
    }
}

/** 세트가 많을 때 패널이 화면을 다 덮지 않도록 높이를 제한한 스크롤. */
class MaxHeightScroll(context: Context, private val maxH: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }

    init { layoutParams = ViewGroup.LayoutParams(-1, -2) }
}
