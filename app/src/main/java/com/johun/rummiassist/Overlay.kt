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

/**
 * 게임 화면 위 표시(터치는 통과):
 * - 낼 손패 타일(실선)과 옮길 판 타일(점선) 테두리 + 세트 번호
 * - 놓을 자리(반투명 점선 칸)와 그 자리로 가는 화살표
 * 화살표와 놓을 자리는 focus 세트만(null 이면 전부), 테두리는 모든 세트.
 */
class HighlightView(context: Context) : View(context) {
    private var advice: Advisor.Advice? = null
    private var labels = false
    private var focus: Int? = null
    /** 인식 좌표(게임 창 기준) → 화면 좌표 */
    private var winX = 0
    private var winY = 0
    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = 0xFF000000.toInt()
    }
    private val dash = DashPathEffect(floatArrayOf(10 * d, 6 * d), 0f)
    private val loc = IntArray(2)

    fun setAdvice(a: Advisor.Advice?, showLabels: Boolean, windowX: Int = 0, windowY: Int = 0, focusSet: Int? = null) {
        advice = a; labels = showLabels; winX = windowX; winY = windowY; focus = focusSet; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val a = advice ?: return
        getLocationOnScreen(loc)
        val ox = (loc[0] - winX).toFloat(); val oy = (loc[1] - winY).toFloat()
        fun box(r: com.johun.rummiassist.core.Rect) = RectF(r.left - ox, r.top - oy, r.right - ox, r.bottom - oy)
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
        val sets = a.sets.filter { focus == null || it.number == focus }
        val all = a.sets
        // 1) 놓을 자리
        for (set in sets) {
            val col = setColor(set.number)
            for (pt in set.tiles) {
                val t = pt.target ?: continue
                val r = box(t).apply { inset(3 * d, 3 * d) }
                fill.color = (col and 0x00FFFFFF) or 0x55000000
                c.drawRoundRect(r, 6 * d, 6 * d, fill)
                stroke.color = col; stroke.pathEffect = dash; stroke.strokeWidth = 2.5f * d
                c.drawRoundRect(r, 6 * d, 6 * d, stroke)
                text.color = 0xFFFFFFFF.toInt(); text.textSize = 12 * d
                c.drawText(if (pt.tile.joker) "☺" else pt.tile.value.toString(), r.centerX(), r.centerY() + 4 * d, text)
            }
        }
        // 2) 화살표 (검은 테두리로 어느 배경에서도 보이게)
        for (set in sets) {
            val col = setColor(set.number)
            for (pt in set.tiles) {
                val s = pt.screen ?: continue
                val t = pt.target ?: continue
                val from = box(s.rect); val to = box(t)
                arrow(c, from.centerX(), from.centerY(), to.centerX(), to.centerY(), col)
            }
        }
        // 3) 움직일 타일 테두리 + 세트 번호
        for (set in all) {
            val col = setColor(set.number)
            for (pt in set.tiles) {
                val s = pt.screen ?: continue
                if (!pt.tile.fromHand && !pt.moved) continue
                val r = box(s.rect).apply { inset(2 * d, 2 * d) }
                stroke.color = if (set in sets) col else (col and 0x00FFFFFF) or 0x99000000.toInt()
                stroke.strokeWidth = if (set in sets) 4 * d else 2.5f * d
                stroke.pathEffect = if (pt.tile.fromHand) null else dash
                c.drawRoundRect(r, 6 * d, 6 * d, stroke)
                val rad = 11 * d
                fill.color = col
                c.drawCircle(r.left + rad * 0.6f, r.top + rad * 0.6f, rad, fill)
                text.color = 0xFF000000.toInt()
                text.textSize = 13 * d
                c.drawText(set.number.toString(), r.left + rad * 0.6f, r.top + rad * 0.6f + 4.5f * d, text)
            }
        }
    }

    private fun arrow(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, col: Int) {
        val dx = x1 - x0; val dy = y1 - y0
        val len = kotlin.math.hypot(dx, dy)
        if (len < 4 * d) return
        val ux = dx / len; val uy = dy / len
        val head = 14 * d
        val ex = x1 - ux * head * 0.6f; val ey = y1 - uy * head * 0.6f
        val path = android.graphics.Path().apply {
            moveTo(x1, y1)
            lineTo(x1 - ux * head - uy * head * 0.55f, y1 - uy * head + ux * head * 0.55f)
            lineTo(x1 - ux * head + uy * head * 0.55f, y1 - uy * head - ux * head * 0.55f)
            close()
        }
        line.color = 0xAA000000.toInt(); line.strokeWidth = 7 * d
        c.drawLine(x0, y0, ex, ey, line)
        fill.color = 0xAA000000.toInt()
        line.style = Paint.Style.FILL_AND_STROKE; line.strokeWidth = 3 * d
        c.drawPath(path, line)
        line.style = Paint.Style.STROKE
        line.color = col; line.strokeWidth = 3.5f * d
        c.drawLine(x0, y0, ex, ey, line)
        fill.color = col
        c.drawPath(path, fill)
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

    /** 다른 표시 창보다 위로 */
    fun bringToFront() {
        val r = root ?: return
        wm.removeView(r); wm.addView(r, lp)
    }

    fun remove() {
        root?.let { wm.removeView(it) }
        root = null
    }

    /** a 가 null 이면 제안 없이 status 문구만(화면이 바뀌는 중 등). */
    fun show(
        a: Advisor.Advice?, status: String?, meldDone: Boolean, labels: Boolean, useJoker: Boolean,
        onMeldToggle: (Boolean) -> Unit, onLabelsToggle: (Boolean) -> Unit, onJokerToggle: (Boolean) -> Unit,
        onClose: () -> Unit, focus: Int? = null, onFocus: (Int) -> Unit = {},
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
            r.addView(label(
                if (meldDone) "모드: 판 재배치 포함 최대 장수" else "모드: 첫 등록 전 — 손패만으로 30점 (판 사용 불가)",
                11f, if (meldDone) 0xFF80CBC4.toInt() else 0xFFFFB74D.toInt(), bold = true,
            ))
            r.addView(label(a.message, 16f, 0xFFFFFFFF.toInt(), bold = true))
            a.jokerHint?.let { r.addView(label("☺ $it", 12f, 0xFFFFEB3B.toInt())) }
            r.addView(label(
                "인식: 판 ${a.recognized.board.size}장 · 손패 ${a.recognized.hand.size}장" +
                    (if (a.keptSets > 0 && a.sets.isNotEmpty()) " · 그대로 둘 세트 ${a.keptSets}개" else ""),
                12f, 0xFFB0C4E8.toInt(),
            ))
            for (w in a.warnings) r.addView(label("⚠ $w", 12f, 0xFFFF8A80.toInt()))
            if (a.sets.isNotEmpty()) {
                // 세트 하나씩 넘겨 보기(판을 덜 가림). 「전체」면 목록 전부.
                val n = a.sets.size
                val nav = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                fun navBtn(t: String, onClick: () -> Unit) = label(t, 16f, 0xFFFFFFFF.toInt(), bold = true).apply {
                    setPadding((10 * d).toInt(), (2 * d).toInt(), (10 * d).toInt(), (2 * d).toInt())
                    background = roundRect(0x33FFFFFF, 6 * d)
                    setOnClickListener { onClick() }
                }
                val cur = focus ?: 0
                nav.addView(navBtn("◀") { onFocus(if (cur <= 1) n else cur - 1) })
                nav.addView(label(if (focus == null) "  전체 $n  " else "  $cur / $n  ", 14f, 0xFFFFFFFF.toInt(), bold = true))
                nav.addView(navBtn("▶") { onFocus(if (cur >= n) 1 else cur + 1) })
                nav.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                nav.addView(navBtn(if (focus == null) "하나씩" else "전체") { onFocus(if (focus == null) 1 else 0) })
                r.addView(nav)
                val shownSets = if (focus == null) a.sets else a.sets.filter { it.number == focus }
                val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                for (s in shownSets) list.addView(setRow(s, focus == s.number).apply { setOnClickListener { onFocus(s.number) } })
                val scroll = MaxHeightScroll(ctx, (ctx.resources.displayMetrics.heightPixels * 0.45f).toInt())
                scroll.addView(list)
                r.addView(scroll)
                r.addView(label("화살표 = 놓을 자리", 11f, 0xFF9FB8E8.toInt()))
            }
        }

        val opts = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        opts.addView(check("첫 등록 완료", meldDone, onMeldToggle))
        opts.addView(check("조커 사용", useJoker, onJokerToggle))
        opts.addView(check("인식 표시", labels, onLabelsToggle))
        r.addView(opts)
    }

    private fun setRow(s: Advisor.PlanSet, focused: Boolean): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((2 * d).toInt(), (3 * d).toInt(), 0, (3 * d).toInt())
            if (focused) background = roundRect(0x33FFFFFF, 6 * d)
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
        row.addView(label(if (s.noRoom) "자리 부족" else s.status.label, 11f, if (s.noRoom) 0xFFFF8A80.toInt() else col).apply { setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0) },
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
