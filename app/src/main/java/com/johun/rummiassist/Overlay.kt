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
import com.johun.rummiassist.core.Tracker

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
 * 게임 화면 위 표시(터치는 통과).
 * 기본(focus = 단계 번호): 남은 이동 중 앞의 4개만 순서대로 — ①은 굵게, ②③④는 흐리게.
 *   이동마다 출발 타일 테두리 + 놓을 자리(반투명 칸) + 화살표 + 순서 번호.
 * 전체(focus = null): 모든 이동을 세트 색으로.
 * 이미 옮긴 타일은 놓은 자리에 ✓.
 */
class HighlightView(context: Context) : View(context) {
    private var advice: Advisor.Advice? = null
    private var labels = false
    private var focus: Int? = null
    private var progress: Tracker.Progress? = null
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
    private var ox = 0f
    private var oy = 0f

    /** 인식 표시는 계획 당시가 아니라 지금 화면의 인식 결과로 */
    private var current: com.johun.rummiassist.core.Recognizer.Result? = null

    /** 뽑기를 권하는(시간 안에 못 끝내는) 계획은 사용자가 「그래도 보기」를 눌렀을 때만 화살표를 그린다 */
    private var showOverTime = false

    fun setAdvice(
        a: Advisor.Advice?, showLabels: Boolean, windowX: Int = 0, windowY: Int = 0,
        focusStep: Int? = null, prog: Tracker.Progress? = null, now: com.johun.rummiassist.core.Recognizer.Result? = null,
        overTimeVisible: Boolean = false, visibleSteps: Set<Int>? = null,
    ) {
        advice = a; labels = showLabels; winX = windowX; winY = windowY; focus = focusStep; progress = prog; current = now
        showOverTime = overTimeVisible; visible = visibleSteps; invalidate()
    }

    /** 남은 시간 안에 할 수 있는 단계(null = 전부) — 그 밖의 이동은 그리지 않는다 */
    private var visible: Set<Int>? = null

    /** 아직 안 옮긴 타일의 지금 위치 */
    private fun src(pt: Advisor.PlanTile) = progress?.source?.get(pt) ?: pt.screen
    private fun done(pt: Advisor.PlanTile) = progress?.done?.contains(pt) == true
    private fun box(r: com.johun.rummiassist.core.Rect) = RectF(r.left - ox, r.top - oy, r.right - ox, r.bottom - oy)

    override fun onDraw(c: Canvas) {
        val a = advice ?: return
        getLocationOnScreen(loc)
        ox = (loc[0] - winX).toFloat(); oy = (loc[1] - winY).toFloat()
        if (labels) {
            text.textSize = 12 * d
            for (t in (current ?: a.recognized).tiles) {
                val r = t.rect
                val bx = (r.left + r.right) / 2f - ox
                val by = r.top - oy + 2 * d
                fill.color = if (t.inHand) 0xCC2E7D32.toInt() else 0xCC1565C0.toInt()
                c.drawRoundRect(RectF(bx - 16 * d, by, bx + 16 * d, by + 16 * d), 4 * d, 4 * d, fill)
                text.color = 0xFFFFFFFF.toInt()
                c.drawText(t.kind.toString(), bx, by + 12 * d, text)
            }
        }
        if (a.overTime && !showOverTime) return
        // ✓ 는 세트가 '다 완성됐을 때'만: 그 세트 자리 전체를 초록 테두리로 감싼다(한 장만 옮겨진 건 완성이 아님)
        for (set in a.sets) {
            val moving = set.tiles.filter { it.target != null }
            if (moving.isEmpty() || !moving.all { done(it) }) continue
            val rects = set.tiles.mapNotNull { pt -> pt.target ?: pt.screen?.rect }.map { box(it) }
            val u = RectF(rects.minOf { it.left }, rects.minOf { it.top }, rects.maxOf { it.right }, rects.maxOf { it.bottom })
            u.inset(-3 * d, -3 * d)
            stroke.color = 0xFF43A047.toInt(); stroke.pathEffect = null; stroke.strokeWidth = 4 * d
            c.drawRoundRect(u, 8 * d, 8 * d, stroke)
            check(c, RectF(u))
        }

        if (focus == null) {
            for (set in a.sets) {
                val col = setColor(set.number)
                for (pt in set.tiles) if (pt.target != null && !done(pt)) drawMove(c, pt, col, 1f, null)
            }
            return
        }
        // 남은 이동 중 앞의 4개(지금 단계부터 이어서)
        val all = a.moves
        val vis = visible
        val inTime = { pt: Advisor.PlanTile -> vis == null || a.steps.firstOrNull { pt in it.moves }?.number in vis }
        val next = all.withIndex().filter { (_, pt) -> !done(pt) && src(pt) != null && inTime(pt) }.take(4)
        for ((k, iv) in next.withIndex().reversed()) {
            val (idx, pt) = iv
            val col = if (k == 0) 0xFFFFEB3B.toInt() else 0xFFFFFFFF.toInt()
            drawMove(c, pt, col, if (k == 0) 1f else 0.45f, idx + 1)
        }
    }

    private fun check(c: Canvas, r: RectF) {
        r.inset(3 * d, 3 * d)
        fill.color = 0xCC2E7D32.toInt()
        c.drawCircle(r.right - 9 * d, r.top + 9 * d, 9 * d, fill)
        text.color = 0xFFFFFFFF.toInt(); text.textSize = 12 * d
        c.drawText("✓", r.right - 9 * d, r.top + 13 * d, text)
    }

    /** 이동 하나: 놓을 자리 + 화살표 + 출발 타일 테두리 + 번호(order) */
    private fun drawMove(c: Canvas, pt: Advisor.PlanTile, col: Int, alpha: Float, order: Int?) {
        val s = src(pt) ?: return
        val t = pt.target ?: return
        val a8 = (alpha * 255).toInt()
        fun withA(cc: Int, aa: Int) = (cc and 0x00FFFFFF) or (aa shl 24)
        val to = box(t).apply { inset(3 * d, 3 * d) }
        fill.color = withA(col, (0x55 * alpha).toInt())
        c.drawRoundRect(to, 6 * d, 6 * d, fill)
        stroke.color = withA(col, a8); stroke.pathEffect = dash; stroke.strokeWidth = 2.5f * d
        c.drawRoundRect(to, 6 * d, 6 * d, stroke)
        text.color = withA(0xFFFFFFFF.toInt(), a8); text.textSize = 12 * d
        c.drawText(if (pt.isPark) "임시" else if (pt.tile.joker) "☺" else pt.tile.value.toString(), to.centerX(), to.centerY() + 4 * d, text)
        val from = box(s.rect)
        arrow(c, from.centerX(), from.centerY(), to.centerX(), to.centerY(), col, alpha)
        val r = RectF(from).apply { inset(2 * d, 2 * d) }
        stroke.color = withA(col, a8); stroke.strokeWidth = if (alpha >= 1f) 4 * d else 2.5f * d
        stroke.pathEffect = if (pt.tile.fromHand) null else dash
        c.drawRoundRect(r, 6 * d, 6 * d, stroke)
        if (order != null) {
            val rad = (if (alpha >= 1f) 13 else 10) * d
            fill.color = withA(col, a8)
            c.drawCircle(r.left + rad * 0.6f, r.top + rad * 0.6f, rad, fill)
            c.drawCircle(to.left + rad * 0.6f, to.top + rad * 0.6f, rad, fill)
            text.color = withA(0xFF000000.toInt(), a8); text.textSize = (if (alpha >= 1f) 15 else 12) * d
            c.drawText(order.toString(), r.left + rad * 0.6f, r.top + rad * 0.6f + 5 * d, text)
            c.drawText(order.toString(), to.left + rad * 0.6f, to.top + rad * 0.6f + 5 * d, text)
        }
    }

    private fun arrow(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, col: Int, alpha: Float) {
        val dx = x1 - x0; val dy = y1 - y0
        val len = kotlin.math.hypot(dx, dy)
        if (len < 4 * d) return
        val ux = dx / len; val uy = dy / len
        val head = (if (alpha >= 1f) 18 else 12) * d
        val ex = x1 - ux * head * 0.6f; val ey = y1 - uy * head * 0.6f
        val path = android.graphics.Path().apply {
            moveTo(x1, y1)
            lineTo(x1 - ux * head - uy * head * 0.55f, y1 - uy * head + ux * head * 0.55f)
            lineTo(x1 - ux * head + uy * head * 0.55f, y1 - uy * head - ux * head * 0.55f)
            close()
        }
        val a8 = (alpha * 255).toInt()
        line.color = ((0xAA * alpha).toInt() shl 24); line.strokeWidth = (if (alpha >= 1f) 9 else 5) * d
        c.drawLine(x0, y0, ex, ey, line)
        line.style = Paint.Style.FILL_AND_STROKE; line.strokeWidth = 3 * d
        c.drawPath(path, line)
        line.style = Paint.Style.STROKE
        line.color = (col and 0x00FFFFFF) or (a8 shl 24); line.strokeWidth = (if (alpha >= 1f) 5 else 3) * d
        c.drawLine(x0, y0, ex, ey, line)
        fill.color = line.color
        c.drawPath(path, fill)
    }
}

/** 추천 세트 목록 패널. 머리 부분을 끌어 옮길 수 있다. */
class ResultPanel(private val ctx: Context, private val wm: WindowManager) {
    private val d = ctx.resources.displayMetrics.density
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var root: LinearLayout? = null
    private var lastKey: String? = null
    private val lp = AssistService.overlayParams(
        (300 * d).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, touchable = true,
    ).apply {
        x = prefs.getInt("px", (70 * d).toInt())
        y = prefs.getInt("py", (8 * d).toInt())
    }

    /** 패널이 화면 밖으로 나가지 않게(일부라도 벗어나면 안쪽으로 당긴다) */
    fun clamp() {
        val r = root ?: return
        val b = wm.currentWindowMetrics.bounds
        if (r.width == 0) return
        val nx = lp.x.coerceIn(0, maxOf(0, b.width() - r.width))
        val ny = lp.y.coerceIn(0, maxOf(0, b.height() - r.height))
        if (nx != lp.x || ny != lp.y) { lp.x = nx; lp.y = ny }
        wm.updateViewLayout(r, lp)
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
        lastKey = null
    }

    /** a 가 null 이면 제안 없이 status 문구만(화면이 바뀌는 중 등). */
    fun show(
        a: Advisor.Advice?, status: String?, meldDone: Boolean, labels: Boolean, useJoker: Boolean,
        onMeldToggle: (Boolean) -> Unit, onLabelsToggle: (Boolean) -> Unit, onJokerToggle: (Boolean) -> Unit,
        onClose: () -> Unit, focus: Int? = null, onFocus: (Int) -> Unit = {},
        progress: Tracker.Progress? = null, overTimeVisible: Boolean = false, onShowAnyway: () -> Unit = {},
        seconds: Double? = null, visibleSteps: Set<Int>? = null,
    ) {
        val r = root ?: LinearLayout(ctx).also {
            it.orientation = LinearLayout.VERTICAL
            it.background = roundRect(0xEE102040.toInt(), 12 * d, 0xFF4A6FB5.toInt(), 1 * d)
            it.setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            wm.addView(it, lp)
            root = it
            // 내용이 바뀌어 크기가 달라질 때마다 화면 안으로
            it.addOnLayoutChangeListener { _, l, t, rr, b, ol, ot, orr, ob -> if (rr - l != orr - ol || b - t != ob - ot) it.post { clamp() } }
        }
        // 내용이 같으면 다시 만들지 않는다(실시간 갱신 중에 누른 터치가 사라지지 않게)
        val key = listOf(
            System.identityHashCode(a), status, meldDone, labels, useJoker, focus, progress?.done?.size, overTimeVisible,
            seconds?.toInt(), visibleSteps,
            prefs.getBoolean("panelMin", true),
        ).joinToString("|")
        if (key == lastKey && r.childCount > 0) return
        lastKey = key
        r.removeAllViews()
        val again = { lastKey = null; show(a, status, meldDone, labels, useJoker, onMeldToggle, onLabelsToggle, onJokerToggle, onClose, focus, onFocus, progress, overTimeVisible, onShowAnyway, seconds, visibleSteps) }

        // 최소화: 한 줄(지금 단계 · 남은 이동 · 다음 이동)만. 누르면 펼침.
        val minimized = prefs.getBoolean("panelMin", true)
        val wantW = if (minimized) WindowManager.LayoutParams.WRAP_CONTENT else (300 * d).toInt()
        if (lp.width != wantW) { lp.width = wantW; wm.updateViewLayout(r, lp) }
        fun toggleMin() { prefs.edit().putBoolean("panelMin", !minimized).apply(); again() }

        // 머리: 제목(끌기) + 최소화 + 닫기
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (minimized) {
            // 요약 줄: 누르면 펼침, 끌면 이동
            header.addView(label((seconds?.let { "⏱${it.toInt().coerceAtLeast(0)} · " } ?: "") + summary(a, status, progress, visibleSteps), 13f, 0xFFFFFFFF.toInt(), bold = true).apply {
                makeDraggable(this) { toggleMin() }
            })
        } else {
            header.addView(label("루미 도우미 · 실시간  ⠿", 13f, 0xFF9FB8E8.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
        }
        header.addView(label(if (minimized) "▢" else "–", 18f, 0xFFFFFFFF.toInt(), bold = true).apply {
            setPadding((12 * d).toInt(), 0, (6 * d).toInt(), 0)
            setOnClickListener { toggleMin() }
        })
        header.addView(label("✕", 18f, 0xFFFFFFFF.toInt()).apply {
            setPadding((10 * d).toInt(), 0, (4 * d).toInt(), 0)
            setOnClickListener { onClose() }
        })
        makeDraggable(header)
        r.addView(header)
        if (minimized) return

        if (a == null) {
            r.addView(label(status ?: "", 15f, 0xFFFFFFFF.toInt(), bold = true))
        } else {
            r.addView(label(
                if (meldDone) "모드: 판 재배치 포함 최대 장수" else "모드: 첫 등록 전 — 손패만으로 30점 (판 사용 불가)",
                11f, if (meldDone) 0xFF80CBC4.toInt() else 0xFFFFB74D.toInt(), bold = true,
            ))
            r.addView(label(a.message, 16f, if (a.overTime) 0xFFFFB74D.toInt() else 0xFFFFFFFF.toInt(), bold = true))
            if (seconds != null && visibleSteps != null) {
                val hidden = a.steps.count { !it.blocked && it.number !in visibleSteps }
                r.addView(label("⏱ 남은 ${seconds.toInt().coerceAtLeast(0)}초" + (if (hidden > 0) " · 시간 안에 못 하는 ${hidden}단계는 숨김" else ""), 12f, 0xFF80CBC4.toInt()))
            }
            a.jokerHint?.let { r.addView(label("☺ $it", 12f, 0xFFFFEB3B.toInt())) }
            r.addView(label(
                "인식: 판 ${a.recognized.board.size}장 · 손패 ${a.recognized.hand.size}장" +
                    (if (a.keptSets > 0 && a.sets.isNotEmpty()) " · 그대로 둘 세트 ${a.keptSets}개" else ""),
                12f, 0xFFB0C4E8.toInt(),
            ))
            for (w in a.warnings) r.addView(label("⚠ $w", 12f, 0xFFFF8A80.toInt()))
            if (a.overTime && !overTimeVisible) {
                r.addView(label("시간 안에 다 못 하면 판이 깨진 채 끝나 벌칙을 받아요. 시간이 넉넉하면 아래를 눌러 안내를 보세요.", 12f, 0xFFFFB74D.toInt()))
                r.addView(label("  그래도 보기  ", 14f, 0xFFFFFFFF.toInt(), bold = true).apply {
                    background = roundRect(0x33FFFFFF, 6 * d)
                    setOnClickListener { onShowAnyway() }
                })
            } else if (a.steps.isNotEmpty()) {
                // 단계 하나씩(판을 덜 가림). 단계를 끝까지 하면 그때의 판이 유효하다.
                val n = a.steps.size
                val nav = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                fun navBtn(t: String, onClick: () -> Unit) = label(t, 16f, 0xFFFFFFFF.toInt(), bold = true).apply {
                    setPadding((10 * d).toInt(), (2 * d).toInt(), (10 * d).toInt(), (2 * d).toInt())
                    background = roundRect(0x33FFFFFF, 6 * d)
                    setOnClickListener { onClick() }
                }
                val cur = focus ?: 0
                nav.addView(navBtn("◀") { onFocus(if (cur <= 1) n else cur - 1) })
                nav.addView(label(if (focus == null) "  전체 ${n}단계  " else "  단계 $cur / $n  ", 14f, 0xFFFFFFFF.toInt(), bold = true))
                nav.addView(navBtn("▶") { onFocus(if (cur >= n) 1 else cur + 1) })
                nav.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                nav.addView(navBtn(if (focus == null) "하나씩" else "전체") { onFocus(if (focus == null) 1 else 0) })
                r.addView(nav)
                val prog = progress
                val runnable = a.steps.filter { !it.blocked }
                if (prog != null && runnable.isNotEmpty() && runnable.all { prog.stepDone(it) }) {
                    r.addView(label("✅ 모든 단계 완료 — 판이 유효해요. 턴을 끝내세요", 13f, 0xFFA5D6A7.toInt(), bold = true))
                }
                val step = a.steps.firstOrNull { it.number == focus }
                if (step != null) r.addView(label(
                    when {
                        step.blocked -> "⚠ 판에 빈 자리가 부족해 할 수 없는 단계"
                        prog != null && prog.stepDone(step) -> "✓ 완료한 단계"
                        step.sets.any { it.relaxed } -> "손패 ${step.handTiles}장 · 판이 꽉 차면 게임이 자동으로 줄여 자리를 만들어요"
                        else -> "손패 ${step.handTiles}장 · 이 단계까지 하면 판이 유효해요"
                    },
                    12f, if (step.blocked) 0xFFFF8A80.toInt() else 0xFFA5D6A7.toInt(),
                ))
                val shownSets = if (focus == null) a.sets else a.sets.filter { it.step == focus }
                val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                for (s in shownSets) list.addView(setRow(s, false, prog))
                val scroll = MaxHeightScroll(ctx, (ctx.resources.displayMetrics.heightPixels * 0.45f).toInt())
                scroll.addView(list)
                r.addView(scroll)
                r.addView(label("① 노란 화살표부터 순서대로 · 단계가 끝날 때마다 판이 유효 · 「전체」= 모든 화살표", 11f, 0xFF9FB8E8.toInt()))
            }
        }

        val opts = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        opts.addView(check("첫 등록 완료", meldDone, onMeldToggle))
        opts.addView(check("조커 사용", useJoker, onJokerToggle))
        opts.addView(check("인식 표시", labels, onLabelsToggle))
        r.addView(opts)
    }

    /** 최소화 때 한 줄 요약 */
    private fun summary(a: Advisor.Advice?, status: String?, prog: Tracker.Progress?, visible: Set<Int>? = null): String {
        if (a == null) return status ?: "루미"
        if (a.steps.isEmpty() || a.overTime) return a.message
        val left = a.moves.filter { prog?.done?.contains(it) != true }
        if (left.isEmpty()) return "✅ 완료 — 턴을 끝내세요"
        // 남은 시간 안에 더 할 수 있는 단계가 없으면 여기서 끝내라고
        if (visible != null && left.none { m -> a.steps.firstOrNull { m in it.moves }?.number in visible }) return "⏱ 시간 부족 — 여기서 턴을 끝내세요"
        val next = left.first()
        val step = a.steps.first { next in it.moves }
        val t = next.tile
        val name = if (t.joker) "조커" else "${t.color.label}${t.value}"
        val verb = when { next.isPark -> "빈 칸에 잠시 두기"; t.fromHand -> "내기"; else -> "옮기기" }
        return "단계 ${step.number}/${a.steps.size} · 남은 ${left.size} · ① $name $verb"
    }

    private fun setRow(s: Advisor.PlanSet, focused: Boolean, prog: Tracker.Progress?): View {
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
        for (pt in s.tiles) chips.addView(chip(pt.tile, pt.tile.fromHand, pt.moved, col).apply {
            if (prog?.done?.contains(pt) == true) alpha = 0.35f
        })
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
    /** 끌면 패널 이동, 거의 안 움직이고 떼면 onTap */
    private fun makeDraggable(handle: View, onTap: (() -> Unit)? = null) {
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.rawX - dx) > slop || kotlin.math.abs(e.rawY - dy) > slop) moved = true
                    if (moved) { lp.x = sx + (e.rawX - dx).toInt(); lp.y = sy + (e.rawY - dy).toInt(); clamp() }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) prefs.edit().putInt("px", lp.x).putInt("py", lp.y).apply() else onTap?.invoke()
                }
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
