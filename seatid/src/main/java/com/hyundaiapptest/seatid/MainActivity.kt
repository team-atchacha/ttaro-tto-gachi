package com.hyundaiapptest.seatid

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ① 알아보기 시연 화면. 왼쪽은 좌석 지도, 오른쪽은 탑승자 확인 질문과 판단 기록이다.
 * 차량 값은 Vehicle SDK로 받고, 같은 앱이 웹 조작판(8090)을 띄운다.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var engine: Engine
    private lateinit var bridge: VehicleBridge
    private lateinit var console: ConsoleClient
    private lateinit var server: PanelServer

    private val cards = mutableMapOf<SeatPos, LinearLayout>()
    private lateinit var promptBox: LinearLayout
    private lateinit var logView: TextView
    private lateinit var statusLine: TextView
    /** 카드를 눌러 직접 고르려는 자리. 없으면 확인 대기 중인 첫 자리를 묻는다. */
    private var focus: SeatPos? = null
    private var renderQueued = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = Engine(Store(this), DriverProfile.read(this)) { scheduleRender() }
        console = ConsoleClient { msg -> main.post { engine.log(msg); scheduleRender() } }
        bridge = VehicleBridge(this, engine, main)
        server = PanelServer(this, engine, console, main)

        setContentView(buildLayout())
        engine.log("앱 시작 · 로그인된 차량 프로필 ${engine.driverLabel}" +
            (engine.driverProfile?.let { " → 주인 ${it.name}" } ?: " → 주인 미등록 (처음 운전석에 앉을 때 물어봄)"))
        // 차 화면에서 사용자 프로필을 바꾸면 이 사용자는 뒤로 물러난다. 조작판 포트를 새 사용자 앱에 넘기고 닫는다.
        registerReceiver(userSwitch, IntentFilter(Intent.ACTION_USER_BACKGROUND))
        console.start()
        server.start()
        bridge.start()
        tick()
        render()
    }

    private val userSwitch = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            server.stop(); console.stop(); finish()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(userSwitch) }
        main.removeCallbacksAndMessages(null)
        bridge.stop(); server.stop(); console.stop()
        super.onDestroy()
    }

    private fun tick() {
        engine.tick()
        main.postDelayed({ tick() }, 1000)
    }

    private fun scheduleRender() {
        if (renderQueued) return
        renderQueued = true
        main.post { renderQueued = false; render() }
    }

    // ── 레이아웃 ────────────────────────────────────

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(48, 36, 48, 36)
        }
        root.addView(text("탑승자 인식", 44f, FG, bold = true))
        statusLine = text("", 22f, MUTED).also { root.addView(it, lp(top = 4)) }

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 28 })

        // 왼쪽: 차량 실내 지도
        val car = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(PANEL, 48f, BORDER)
            setPadding(40, 28, 40, 40)
        }
        car.addView(text("▲ 차량 앞쪽", 20f, MUTED).apply { gravity = Gravity.CENTER }, lp(w = -1))
        listOf(SeatPos.FL to SeatPos.FR, SeatPos.RL to SeatPos.RR).forEachIndexed { i, (l, r) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            car.addView(row, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = if (i == 0) 20 else 36 })
            listOf(l, r).forEachIndexed { j, p ->
                val c = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 26, 32, 26)
                    setOnClickListener { onCardTap(p) }
                }
                cards[p] = c
                row.addView(c, LinearLayout.LayoutParams(0, -1, 1f).apply { if (j == 1) leftMargin = 36 })
            }
        }
        body.addView(car, LinearLayout.LayoutParams(0, -1, 1.35f))

        // 오른쪽: 질문 + 기록
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(side, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = 36 })
        promptBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(PANEL, 32f, BORDER)
            setPadding(36, 30, 36, 30)
        }
        side.addView(promptBox, lp(w = -1))
        val logBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(PANEL, 32f, BORDER)
            setPadding(36, 26, 36, 26)
        }
        logBox.addView(text("판단 기록", 22f, MUTED, bold = true))
        logView = text("", 19f, FG).apply { setLineSpacing(6f, 1f) }
        logBox.addView(logView, lp(top = 10))
        side.addView(logBox, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 24 })
        return root
    }

    // ── 그리기 ──────────────────────────────────────

    private fun render() {
        val occupied = engine.seats.values.count { it.occupied }
        val known = engine.seats.values.count { it.status == Status.IDENTIFIED }
        statusLine.text = "차량 프로필 ${engine.driverLabel}(${engine.driverProfile?.name ?: "주인 미등록"}) · 탑승 $occupied · 확인 $known · 웹 조작판 http://127.0.0.1:${PanelServer.PORT}" +
            if (engine.airbagOff) " · 조수석 에어백 OFF" else ""
        SeatPos.values().forEach { renderCard(it) }
        renderPrompt()
        logView.text = engine.logLines().take(16).joinToString("\n")
    }

    private fun renderCard(p: SeatPos) {
        val s = engine.seats.getValue(p)
        val c = cards.getValue(p)
        c.removeAllViews()
        val prof = engine.profile(s.personId)
        val accent = when (s.status) {
            Status.IDENTIFIED -> prof?.color ?: GUEST_C
            Status.ASKING, Status.DETECTED -> AMBER
            Status.OBJECT -> MUTED
            Status.EMPTY -> BORDER
        }
        c.background = round(if (s.status == Status.EMPTY) CARD_EMPTY else CARD, 36f, accent, if (s.status == Status.EMPTY) 2 else 5)

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(p.label, 22f, MUTED, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        if (s.doorOpen) top.addView(chip("문 열림", AMBER))
        c.addView(top)

        val (big, color) = when (s.status) {
            Status.EMPTY -> "빈자리" to DIM
            Status.DETECTED -> "착석 감지…" to AMBER
            Status.ASKING -> "누구세요?" to AMBER
            Status.IDENTIFIED -> (engine.nameOf(s) ?: "?") to FG
            Status.OBJECT -> "짐·물건" to MUTED
        }
        c.addView(text(big, 56f, color, bold = true), lp(top = 10))
        val sub = when (s.status) {
            Status.IDENTIFIED -> listOfNotNull(prof?.role, s.method).joinToString(" · ")
            Status.ASKING -> engine.profile(s.suggestion)?.let { "추천: ${it.name} (${s.suggestionReason})" } ?: s.suggestionReason
            Status.OBJECT -> s.method
            else -> ""
        }
        if (sub.isNotEmpty()) c.addView(text(sub, 20f, MUTED), lp(top = 4))

        val spacer = View(this)
        c.addView(spacer, LinearLayout.LayoutParams(-1, 0, 1f))
        if (s.beltWarn) c.addView(text("벨트 미착용 · 짐일 수 있어요", 20f, RED, bold = true), lp(bottom = 8))
        val sensors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sensors.addView(chip(if (s.occupied) "점유 ●" else "점유 ○", if (s.occupied) GREEN else DIM))
        sensors.addView(chip(if (s.belt) "벨트 ✓" else "벨트 —", if (s.belt) GREEN else DIM), lpH(8))
        if (p == SeatPos.FL) sensors.addView(chip("프로필 ${engine.driverProfile?.name ?: "미등록"}", if (engine.driverProfile != null) BLUE else DIM), lpH(8))
        if (p == SeatPos.FR && engine.airbagOff) sensors.addView(chip("에어백 OFF", AMBER), lpH(8))
        c.addView(sensors)
    }

    private fun renderPrompt() {
        promptBox.removeAllViews()
        val f = focus?.takeIf { engine.seats.getValue(it).occupied }
        if (f == null) focus = null
        val waiting = engine.seats.values.filter { it.status == Status.ASKING }
        val target = f?.let { engine.seats.getValue(it) } ?: waiting.firstOrNull()

        if (target == null) {
            promptBox.addView(text("탑승자 확인", 22f, MUTED, bold = true))
            val seated = engine.seats.values.filter { it.status == Status.IDENTIFIED }
            promptBox.addView(text(if (seated.isEmpty()) "탑승을 기다리는 중" else "모두 확인했어요", 36f, FG, bold = true), lp(top = 8))
            promptBox.addView(text(
                if (seated.isEmpty()) "문이 열리고 좌석에 앉으면 누구인지 알아봅니다."
                else seated.joinToString("\n") { "${it.pos.label}  ${engine.nameOf(it)}" },
                22f, MUTED,
            ).apply { setLineSpacing(6f, 1f) }, lp(top = 8))
            return
        }

        val others = waiting.size - if (target.status == Status.ASKING) 1 else 0
        promptBox.addView(text("탑승자 확인" + if (others > 0) " · 대기 ${others}자리" else "", 22f, MUTED, bold = true))
        promptBox.addView(text("${target.pos.label}에 누가 타셨나요?", 36f, FG, bold = true), lp(top = 8))
        val sug = engine.profile(target.suggestion)
        if (sug != null && target.status == Status.ASKING) {
            promptBox.addView(text(target.suggestionReason, 20f, MUTED), lp(top = 6))
            promptBox.addView(button("${sug.name} 맞아요", sug.color, big = true) { pick(target.pos, sug.id) }, lp(w = -1, top = 18))
        }
        val taken = engine.seats.values.filter { it.pos != target.pos && it.status == Status.IDENTIFIED }.mapNotNull { it.personId }.toSet()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        engine.profiles.filter { it.id !in taken && it.id != sug?.id }.forEach { p ->
            grid.addView(button(p.name, p.color) { pick(target.pos, p.id) }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 12 })
        }
        grid.addView(button("손님", GUEST_C) { pick(target.pos, GUEST) }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 12 })
        grid.addView(button("짐이에요", MUTED) { pick(target.pos, OBJECT) }, LinearLayout.LayoutParams(0, -2, 1f))
        promptBox.addView(grid, lp(w = -1, top = 14))
        if (target.pos == SeatPos.FL) promptBox.addView(text("한 번 고르면 이 차량 프로필의 주인으로 기억해, 다음부터 이 프로필로 로그인해 있으면 묻지 않습니다.", 18f, DIM), lp(top = 14))
    }

    private fun onCardTap(p: SeatPos) {
        if (!engine.seats.getValue(p).occupied) return
        focus = if (focus == p) null else p
        render()
    }

    private fun pick(p: SeatPos, id: String) {
        focus = null
        engine.choose(p, id)
    }

    // ── 뷰 도우미 ───────────────────────────────────

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun chip(s: String, color: Int) = text(s, 18f, color, bold = true).apply {
        setPadding(16, 6, 16, 6)
        background = round(Color.TRANSPARENT, 999f, color, 2)
    }

    private fun button(label: String, color: Int, big: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        textSize = if (big) 30f else 22f
        setTextColor(if (big) Color.WHITE else FG)
        typeface = Typeface.DEFAULT_BOLD
        background = round(if (big) color else CARD, 24f, color, 3)
        setPadding(12, if (big) 26 else 18, 12, if (big) 26 else 18)
        setOnClickListener { onClick() }
    }

    private fun round(fill: Int, radius: Float, stroke: Int, width: Int = 2) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius; setStroke(width, stroke)
    }

    private fun lp(w: Int = -2, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(w, -2).apply { topMargin = top; bottomMargin = bottom }
    private fun lpH(left: Int) = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = left }

    companion object {
        val BG = Color.parseColor("#0B0F17")
        val PANEL = Color.parseColor("#111827")
        val CARD = Color.parseColor("#172033")
        val CARD_EMPTY = Color.parseColor("#0F1522")
        val BORDER = Color.parseColor("#263042")
        val FG = Color.parseColor("#F3F4F6")
        val MUTED = Color.parseColor("#9CA3AF")
        val DIM = Color.parseColor("#4B5563")
        val AMBER = Color.parseColor("#F59E0B")
        val GREEN = Color.parseColor("#22C55E")
        val BLUE = Color.parseColor("#60A5FA")
        val RED = Color.parseColor("#F87171")
        val GUEST_C = Color.parseColor("#A78BFA")
    }
}
