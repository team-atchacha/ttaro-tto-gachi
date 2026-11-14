package com.hyundaiapptest.together

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout

/** 앱 공통 색·글꼴·아이콘 */
object Ui {
    val BG = Color.parseColor("#0A0D14")
    val SURFACE = Color.parseColor("#121722")
    val SURFACE_2 = Color.parseColor("#1A2130")
    val SURFACE_3 = Color.parseColor("#232B3D")
    val LINE = Color.parseColor("#2A3346")
    val FG = Color.parseColor("#F4F6FA")
    val FG_2 = Color.parseColor("#B4BCCB")
    val FG_3 = Color.parseColor("#6E7890")
    val ACCENT = Color.parseColor("#5B9DFF")
    val WARM = Color.parseColor("#FFB547")
    val GOOD = Color.parseColor("#3DD68C")
    val HOT = Color.parseColor("#FF7A59")
    val COOL = Color.parseColor("#56B8FF")
    val ALERT = Color.parseColor("#FF6B6B")

    // ── 디자인 토큰: 모서리는 세 가지, 간격은 8의 배수만 쓴다 ──
    /** 바깥 판(실내, 오른쪽 카드, 알림 띠) */
    const val R_OUT = 32f
    /** 판 안의 요소(좌석 카드, 버튼, 타일). 판 안쪽 여백 16과 합쳐 바깥 모서리와 평행이 된다 */
    const val R_IN = 16f
    /** 칩·알약 */
    const val R_PILL = 999f
    const val S1 = 8; const val S2 = 16; const val S3 = 24; const val S4 = 32
    /** 버튼·타일 높이 */
    const val H_BTN = 72; const val H_CHIP = 64; const val H_HEAD = 48

    lateinit var icons: Typeface
    val bold: Typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    val semi: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    val medium: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)

    fun init(ctx: Context) {
        if (!::icons.isInitialized) icons = ctx.resources.getFont(R.font.material_icons)
    }

    // Material Icons 코드포인트
    const val I_SLEEP = ""
    const val I_VIDEO = ""
    const val I_READ = ""
    const val I_FOCUS = ""
    const val I_LAMP = ""
    const val I_HEAT = ""
    const val I_VENT = ""
    const val I_TEMP = ""
    const val I_SETTINGS = ""
    const val I_PERSON = ""
    const val I_CAR = ""
    const val I_BAG = ""
    const val I_NAV = ""
    const val I_TIME = ""
    const val I_CLOSE = ""
    const val I_WAKE = ""
    const val I_SEAT = ""
    const val I_FAMILY = ""
    const val I_SUN = ""
    const val I_LEAF = ""
    const val I_CHECK = ""
    const val I_HELP = ""
    const val I_SWAP = ""

    fun modeIcon(m: Mode) = when (m) {
        Mode.SLEEP -> I_SLEEP; Mode.VIDEO -> I_VIDEO; Mode.READ -> I_READ; Mode.FOCUS -> I_FOCUS; Mode.NONE -> I_SEAT
    }

    fun modeColor(m: Mode) = when (m) {
        Mode.SLEEP -> Color.parseColor("#9B8CFF")
        Mode.VIDEO -> Color.parseColor("#4FA3FF")
        Mode.READ -> Color.parseColor("#FFB547")
        Mode.FOCUS -> Color.parseColor("#3DD68C")
        Mode.NONE -> FG_3
    }

    fun modeLabel(m: Mode) = when (m) {
        Mode.SLEEP -> "쉬는 중"; Mode.VIDEO -> "영상 보는 중"; Mode.READ -> "책 읽는 중"; Mode.FOCUS -> "운전 집중"; Mode.NONE -> ""
    }

    fun round(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT, width: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius; if (width > 0) setStroke(width, stroke)
    }

    fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}

/**
 * 차를 위에서 본 실내. 실제 앰비언트 조명 색·밝기로 실내 가장자리가 은은하게 빛난다.
 * 좌석 카드는 자식 뷰로 얹는다.
 */
class CabinView(ctx: Context) : FrameLayout(ctx) {
    // 화면에 그리는 값(목표값으로 부드럽게 따라간다)
    private var shownColor = Color.WHITE
    private var shownLevel = 0f
    private var shownSun = 0f
    private var breath = 1f
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Ui.LINE }
    private var ambientAnim: android.animation.ValueAnimator? = null
    private var sunAnim: android.animation.ValueAnimator? = null
    private var breathAnim: android.animation.ValueAnimator? = null
    private var target: Triple<Boolean, Int, Int>? = null

    init { setWillNotDraw(false); clipChildren = false }

    /** 실제 앰비언트 조명 값. 색·밝기가 툭 바뀌지 않고 1.2초에 걸쳐 넘어간다. */
    fun setAmbient(on: Boolean, color: Int, level: Int) {
        val t = Triple(on, color, level)
        if (t == target) return
        target = t
        val fromC = shownColor; val fromL = shownLevel
        val toC = if (on) color else shownColor
        val toL = if (on) level.toFloat() else 0f
        ambientAnim?.cancel()
        ambientAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200; interpolator = android.view.animation.DecelerateInterpolator()
            val ev = android.animation.ArgbEvaluator()
            addUpdateListener { a ->
                val f = a.animatedFraction
                shownColor = ev.evaluate(f, fromC, toC) as Int
                shownLevel = fromL + (toL - fromL) * f
                invalidate()
            }
            start()
        }
    }

    /** 누군가 쉬는 중이면 실내 빛이 아주 느리게 숨쉰다 */
    var breathing = false
        set(v) {
            if (field == v) return
            field = v
            breathAnim?.cancel()
            if (v) breathAnim = android.animation.ValueAnimator.ofFloat(1f, 0.55f).apply {
                duration = 3200; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { breath = it.animatedValue as Float; invalidate() }
                start()
            } else { breath = 1f; invalidate() }
        }

    /** 도착 전 깨우기: 실내 아래쪽에서 해가 뜨듯 빛이 차오른다(0~1) */
    fun setSunrise(p: Float) {
        if (kotlin.math.abs(p - shownSun) < 0.01f && sunAnim?.isRunning != true) return
        sunAnim?.cancel()
        val from = shownSun
        sunAnim = android.animation.ValueAnimator.ofFloat(from, p).apply {
            duration = 1500; interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { shownSun = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        ambientAnim?.cancel(); sunAnim?.cancel(); breathAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = RectF(1f, 1f, w - 1f, h - 1f)
        val rad = Ui.R_OUT
        body.shader = LinearGradient(0f, 0f, 0f, h, Ui.SURFACE, Ui.alpha(Ui.SURFACE, 230), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, body)
        // 도착 전 해돋이
        if (shownSun > 0.01f) {
            glow.style = Paint.Style.FILL
            val top = h * (1f - 0.85f * shownSun)
            glow.shader = LinearGradient(0f, h, 0f, top, intArrayOf(Ui.alpha(Color.parseColor("#FFB547"), (110 * shownSun).toInt()), Ui.alpha(Color.parseColor("#FFD180"), (40 * shownSun).toInt()), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, glow)
            glow.shader = null
        }
        // 앰비언트: 실내 가장자리 빛(실제 조명 색·밝기)
        if (shownLevel > 0.5f) {
            val a = ((40 + shownLevel * 1.6f) * breath).toInt().coerceIn(0, 200)
            glow.style = Paint.Style.STROKE
            for (i in 0 until 5) {
                val inset = 2f + i * 3f
                glow.strokeWidth = 6f
                glow.color = Ui.alpha(shownColor, a * (5 - i) / 5)
                c.drawRoundRect(RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset), rad - inset, rad - inset, glow)
            }
            glow.style = Paint.Style.FILL
            glow.shader = RadialGradient(w / 2, h / 2, maxOf(w, h) * 0.75f, intArrayOf(Color.TRANSPARENT, Ui.alpha(shownColor, a / 4)), floatArrayOf(0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, glow)
            glow.shader = null
        } else {
            c.drawRoundRect(r, rad, rad, edge)
        }
        super.onDraw(c)
    }
}
