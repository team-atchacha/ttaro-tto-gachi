package com.hyundaiapptest.together

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hyundaiapptest.together.Ui.FG
import com.hyundaiapptest.together.Ui.FG_2
import com.hyundaiapptest.together.Ui.FG_3

/**
 * 「따로 또 같이」 차 화면.
 *  - 왼쪽: 위에서 본 실내. 자리마다 누가 앉았고 무엇을 하는지, 그 자리에 켜 둔 설비. 실내 가장자리는 실제 앰비언트 조명 색으로 빛난다.
 *  - 오른쪽: 지금 필요한 한 가지(먼저 묻기·확인·자리 메뉴·설정)와, 함께 쓰는 설비를 어떻게 맞췄는지.
 * 판단은 DemoService(Hub)에서 돌고, 이 화면은 그리기와 탑승자 입력만 맡는다.
 */
class MainActivity : Activity() {

    private val engine get() = Hub.engine
    private lateinit var tripLine: TextView
    private lateinit var pills: LinearLayout
    private lateinit var cabin: CabinView
    private val seatViews = mutableMapOf<SeatPos, FrameLayout>()
    private lateinit var banner: LinearLayout
    private lateinit var card: LinearLayout
    private lateinit var shared: LinearLayout

    private var focus: SeatPos? = null
    private var choosingPerson = false
    private var showSettings = false
    private val render = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        startForegroundService(Intent(this, DemoService::class.java))
        setContentView(build())
    }

    override fun onStart() { super.onStart(); Hub.listen(render); Hub.main.post { render() } }
    override fun onStop() { Hub.unlisten(render); super.onStop() }

    // ── 뼈대 ────────────────────────────────────────

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(Ui.S4, Ui.S3, Ui.S4, Ui.S4)
        }
        // 머리글
        val head = row(Gravity.CENTER_VERTICAL)
        head.addView(circleIcon(Ui.I_FAMILY, Ui.ACCENT, (Ui.H_HEAD / 1.3f).toInt()))
        val titles = col()
        titles.addView(text("따로 또 같이", 30f, FG, Ui.bold))
        tripLine = text("", 17f, FG_3, Ui.medium)
        titles.addView(tripLine)
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.S2 })
        pills = row(Gravity.CENTER_VERTICAL)
        head.addView(pills)
        head.addView(iconButton(Ui.I_SETTINGS) { showSettings = !showSettings; focus = null; render() }, LinearLayout.LayoutParams(Ui.H_HEAD, Ui.H_HEAD).apply { leftMargin = Ui.S1 })
        root.addView(head)

        val body = row()
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Ui.S3 })

        // 실내
        cabin = CabinView(this)
        val grid = col().apply { setPadding(Ui.S2, Ui.S2, Ui.S2, Ui.S2) }
        listOf(SeatPos.FL to SeatPos.FR, SeatPos.RL to SeatPos.RR).forEachIndexed { i, (l, r) ->
            val line = row()
            grid.addView(line, LinearLayout.LayoutParams(-1, 0, 1f).apply { if (i == 1) topMargin = Ui.S2 })
            listOf(l, r).forEachIndexed { j, p ->
                val v = FrameLayout(this).apply { setOnClickListener { onSeatTap(p) }; clipChildren = false }.pressable()
                seatViews[p] = v
                line.addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (j == 1) leftMargin = Ui.S2 })
            }
        }
        cabin.addView(grid, FrameLayout.LayoutParams(-1, -1))
        body.addView(cabin, LinearLayout.LayoutParams(0, -1, 1.38f))

        // 오른쪽
        val side = col()
        body.addView(side, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = Ui.S3 })
        banner = row(Gravity.CENTER_VERTICAL).apply { setPadding(Ui.S3, Ui.S2, Ui.S4, Ui.S2); visibility = View.GONE; minimumHeight = Ui.H_BTN }
        card = col().apply { setPadding(Ui.S4, Ui.S4, Ui.S4, Ui.S4); background = Ui.round(Ui.SURFACE, Ui.R_OUT) }
        side.addView(card, lp(-1))
        side.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        shared = col().apply { setPadding(Ui.S4, Ui.S3, Ui.S4, Ui.S3); background = Ui.round(Ui.SURFACE, Ui.R_OUT) }
        side.addView(shared, lp(-1, top = Ui.S2))
        // 알림은 화면 위 가운데에 떠서 나타난다(아래 화면을 밀지 않는다)
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        banner.elevation = 24f
        frame.addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.S3 })
        return frame
    }

    // ── 그리기 ──────────────────────────────────────

    private fun render() {
        val e = engine ?: return
        renderHead(e)
        SeatPos.values().forEach { renderSeat(e, it) }
        renderBanner(e)
        renderCard(e)
        renderShared(e)
    }

    private fun renderHead(e: Engine) {
        val r = e.route
        tripLine.text = when {
            r != null && r.arrived -> "${r.name}에 도착했어요"
            r != null -> "${r.name}까지 ${fmtMin(e.remainMin ?: 0)}" + if (e.driveMin >= 30) " · ${fmtMin(e.driveMin)}째 운전 중" else ""
            e.peopleCount > 0 -> "${e.peopleCount}명 탑승"
            else -> "탑승을 기다리고 있어요"
        }
        pills.removeAllViews()
        if (e.driving) pills.addView(pill(Ui.I_CAR, "주행 중", Ui.GOOD), lpH(Ui.S1))
        else if (e.peopleCount > 0) pills.addView(pill(Ui.I_CAR, "정차 중", FG_3), lpH(Ui.S1))
        if (r != null && !r.arrived) pills.addView(pill(Ui.I_NAV, "안내 중", Ui.ACCENT), lpH(Ui.S1))
    }

    /** 자리마다 마지막으로 그린 상태. 바뀐 것만 다시 그리고, 무엇이 바뀌었는지에 따라 연출을 고른다. */
    private class SeatMemo(
        val sig: String, val status: Status, val person: String?, val mode: Mode,
        val lamp: Boolean, val heat: Int, val vent: Int, val temp: Float?,
    )
    private val memo = mutableMapOf<SeatPos, SeatMemo>()
    private val loops = mutableMapOf<SeatPos, MutableList<android.animation.Animator>>()

    private fun renderSeat(e: Engine, p: SeatPos) {
        val s = e.seats.getValue(p)
        val v = seatViews.getValue(p)
        val prof = e.profile(s.personId)
        val color = prof?.color ?: if (s.personId == GUEST) Color.parseColor("#A78BFA") else Ui.FG_3
        val reading = e.devices.getValue(DevKey(DevKind.READING, p).id)
        val lampOn = reading.actual == true
        val selected = focus == p
        fun dev(k: DevKind) = e.devices[DevKey(k, p).id]
        val heat = ((dev(DevKind.HEAT)?.actual as? Number)?.toInt() ?: 0).coerceAtLeast(0)
        val vent = (dev(DevKind.VENT)?.actual as? Number)?.toInt() ?: 0
        val temp = if (p.front) (dev(DevKind.TEMP)?.actual as? Number)?.toFloat() else null
        val deferred = reading.state == CmdState.DEFERRED
        val sig = listOf(s.status, s.personId, s.suggestion, s.mode, lampOn, deferred, heat, vent, temp, s.doorOpen, s.beltWarn, selected).joinToString("|")
        val prev = memo[p]
        if (prev?.sig == sig) return
        memo[p] = SeatMemo(sig, s.status, s.personId, s.mode, lampOn, heat, vent, temp)
        val anim = prev != null // 앱을 처음 열 때는 연출하지 않는다
        loops.remove(p)?.forEach { it.cancel() }
        v.removeAllViews()

        v.background = GradientDrawable().apply {
            cornerRadius = Ui.R_IN
            if (s.status == Status.EMPTY) { setColor(Ui.alpha(Ui.SURFACE, 150)); setStroke(2, Ui.LINE, 10f, 8f) }
            else { setColor(Ui.SURFACE_2); setStroke(if (selected) 4 else 2, when { selected -> FG; s.status == Status.ASKING -> Ui.WARM; lampOn -> Ui.alpha(Ui.WARM, 160); s.mode != Mode.NONE -> Ui.alpha(Ui.modeColor(s.mode), 120); else -> Ui.LINE }) }
        }
        // 모드 색으로 은은하게 물든 바닥
        if (s.status == Status.IDENTIFIED && s.mode != Mode.NONE) {
            val tint = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = Ui.R_IN; gradientType = GradientDrawable.RADIAL_GRADIENT
                    gradientRadius = 520f; setGradientCenter(0.15f, 1f)
                    colors = intArrayOf(Ui.alpha(Ui.modeColor(s.mode), 70), Color.TRANSPARENT)
                }
            }
            v.addView(tint, FrameLayout.LayoutParams(-1, -1))
            if (anim && prev?.mode != s.mode) { tint.alpha = 0f; tint.animate().alpha(1f).setDuration(900).start() }
        }
        // 독서등: 카드 위에서 따뜻한 빛이 켜진다
        if (lampOn || (anim && prev?.lamp == true)) {
            val glow = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = Ui.R_IN; gradientType = GradientDrawable.RADIAL_GRADIENT
                    gradientRadius = 420f; setGradientCenter(0.5f, 0f)
                    colors = intArrayOf(Ui.alpha(Ui.WARM, 120), Ui.alpha(Ui.WARM, 30), Color.TRANSPARENT)
                }
            }
            v.addView(glow, FrameLayout.LayoutParams(-1, -1))
            when {
                lampOn && anim && prev?.lamp != true -> { glow.alpha = 0f; glow.animate().alpha(1f).setDuration(1100).setInterpolator(android.view.animation.DecelerateInterpolator()).start() }
                !lampOn -> glow.animate().alpha(0f).setDuration(900).withEndAction { v.removeView(glow) }.start()
            }
        }

        val c = col().apply { setPadding(Ui.S3, Ui.S3, Ui.S3, Ui.S3); clipChildren = false; clipToPadding = false }
        v.addView(c, FrameLayout.LayoutParams(-1, -1))

        val top = row(Gravity.CENTER_VERTICAL)
        top.addView(text(p.label, 16f, FG_3, Ui.semi), LinearLayout.LayoutParams(0, -2, 1f))
        if (s.doorOpen) top.addView(text("문 열림", 14f, Ui.WARM, Ui.semi).also { if (anim) blink(it) })
        c.addView(top)

        when (s.status) {
            Status.EMPTY -> {
                c.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
                c.addView(icon(Ui.I_SEAT, 46f, Ui.alpha(FG_3, 120)).apply { gravity = Gravity.CENTER }, lp(-1))
                c.addView(text("빈 자리", 18f, FG_3, Ui.medium).apply { gravity = Gravity.CENTER }, lp(-1, top = Ui.S1))
                c.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
                if (anim && prev?.status != Status.EMPTY) fadeIn(c, 0)
                return
            }
            Status.DETECTED, Status.ASKING -> {
                val who = row(Gravity.CENTER_VERTICAL).apply { clipChildren = false; clipToPadding = false }
                val (box, av) = avatarWithRing("?", Ui.WARM, 64)
                who.addView(box)
                val t = col()
                t.addView(text("누구세요?", 28f, FG, Ui.bold))
                t.addView(text(e.profile(s.suggestion)?.let { it.name.josa("이에요", "예요") + "?" } ?: "확인이 필요해요", 16f, FG_2, Ui.medium))
                who.addView(t, lpH(16))
                c.addView(who, lp(-1, top = Ui.S2))
                if (anim && prev?.status != s.status) { pop(av); fadeIn(t, 120) }
                loops.getOrPut(p) { mutableListOf() } += breathe(av, 0.92f, 1400)
            }
            Status.OBJECT -> {
                val who = row(Gravity.CENTER_VERTICAL).apply { clipChildren = false; clipToPadding = false }
                who.addView(circleIcon(Ui.I_BAG, FG_3, 64))
                who.addView(text("짐", 28f, FG_2, Ui.bold), lpH(16))
                c.addView(who, lp(-1, top = Ui.S2))
                c.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
                if (anim && prev?.status != s.status) fadeIn(who, 0)
                return
            }
            Status.IDENTIFIED -> {
                val who = row(Gravity.CENTER_VERTICAL).apply { clipChildren = false; clipToPadding = false }
                val (box, av) = avatarWithRing(e.nameOf(s)?.take(1) ?: "?", color, 72)
                who.addView(box)
                val t = col()
                t.addView(text(e.nameOf(s) ?: "", 32f, FG, Ui.bold))
                val sub = prof?.role ?: if (s.personId == GUEST) "손님" else ""
                t.addView(text(sub, 15f, FG_3, Ui.medium))
                who.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.S2 })
                c.addView(who, lp(-1, top = Ui.S2))
                // 알아봤을 때: 얼굴이 톡 튀어나오고 물결이 퍼지며 인사한다
                if (anim && (prev?.status != Status.IDENTIFIED || prev.person != s.personId)) {
                    pop(av); ripple(box, color); fadeIn(t, 140)
                    if (s.personId != GUEST) greet("${e.nameOf(s)}, 어서 와요", color)
                }
                if (s.mode != Mode.NONE) {
                    val tag = modeTag(s.mode)
                    c.addView(tag, lp(top = Ui.S2))
                    if (anim && prev?.mode != s.mode) slideIn(tag, 80)
                    // 쉬는 중이면 달 아이콘이 천천히 숨쉰다
                    if (s.mode == Mode.SLEEP) loops.getOrPut(p) { mutableListOf() } += breathe((tag as LinearLayout).getChildAt(0), 0.45f, 2600, alphaOnly = true)
                }
            }
        }

        c.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        if (s.beltWarn) c.addView(text("벨트를 매지 않았어요 · 짐인가요?", 15f, Ui.ALERT, Ui.semi).also { if (anim) blink(it) }, lp(bottom = Ui.S1))

        // 이 자리에서 켜 둔 것
        val comforts = row(Gravity.CENTER_VERTICAL)
        val fresh = mutableListOf<View>()
        fun add(v0: View, isNew: Boolean) { comforts.addView(v0, lpH(if (comforts.childCount > 0) Ui.S1 else 0)); if (isNew) fresh += v0 }
        if (deferred) {
            val turnOn = (reading.target?.value ?: reading.baseline) == true
            add(comfort(Ui.I_LAMP, if (turnOn) "정차하면 켜요" else "정차하면 꺼요", FG_3), true)
        } else if (lampOn) add(comfort(Ui.I_LAMP, "독서등", Ui.WARM), prev?.lamp != true)
        if (heat > 0) add(comfort(Ui.I_HEAT, "$heat", Ui.HOT), prev?.heat != heat)
        if (vent > 0) add(comfort(Ui.I_VENT, "$vent", Ui.COOL), prev?.vent != vent)
        if (temp != null) {
            val chip = comfort(Ui.I_TEMP, "%.1f°".format(temp), FG_2)
            add(chip, false)
            val old = prev?.temp
            if (anim && old != null && old != temp) countTo(chip.getChildAt(1) as TextView, old, temp)
        }
        if (comforts.childCount > 0) c.addView(comforts)
        if (anim) fresh.forEachIndexed { i, f -> riseIn(f, 120L + i * 90) }
    }

    private var greeting: Pair<String, Int>? = null
    private var greetUntil = 0L
    private var bannerShown: String? = null

    private fun greet(msg: String, color: Int) {
        greeting = msg to color; greetUntil = System.currentTimeMillis() + 3500
        Hub.main.postDelayed({ render() }, 3600)
    }

    private fun renderBanner(e: Engine) {
        // 운전 집중 안내는 오른쪽 카드가 이미 보여 주므로 알림 띠에는 띄우지 않는다
        val n = e.notice?.takeUnless { it.startsWith("운전석만") }
        val g = greeting?.takeIf { System.currentTimeMillis() < greetUntil }
        val msg = n ?: g?.first
        if (msg == bannerShown) return
        val wasShown = bannerShown != null
        bannerShown = msg
        if (msg == null) {
            banner.animate().alpha(0f).translationY(-30f).setDuration(280).withEndAction { if (bannerShown == null) banner.visibility = View.GONE }.start()
            return
        }
        banner.removeAllViews()
        val arrival = msg.contains("도착")
        val color = when { n == null && g != null -> g.second; arrival -> Ui.WARM; else -> Ui.GOOD }
        val ic = when { n == null -> Ui.I_PERSON; arrival -> Ui.I_WAKE; else -> Ui.I_FOCUS }
        banner.visibility = View.VISIBLE
        // 불투명한 바탕 위에 색을 얹어 아래 화면이 비치지 않게 한다
        banner.background = android.graphics.drawable.LayerDrawable(arrayOf(Ui.round(Ui.SURFACE_2, Ui.R_PILL), Ui.round(Ui.alpha(color, 46), Ui.R_PILL, Ui.alpha(color, 170), 2)))
        banner.addView(circleIcon(ic, color, 44))
        banner.addView(text(msg, 20f, FG, Ui.semi).apply { maxLines = 1 }, lpH(Ui.S2))
        banner.alpha = 0f; banner.translationY = if (wasShown) 0f else -40f; banner.scaleX = if (wasShown) 1f else 0.92f; banner.scaleY = banner.scaleX
        banner.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
    }

    // ── 오른쪽 카드: 지금 필요한 한 가지 ─────────────────────

    private var cardKind = ""
    private var cardDetail = ""

    /** 카드 종류와 세부 내용. 종류가 바뀌면 연출하고, 세부만 바뀌면 조용히 다시 그린다. */
    private fun cardKey(e: Engine): Pair<String, String> {
        val f = focus?.takeIf { e.seats.getValue(it).occupied }
        if (showSettings) return "settings" to "${e.store.priority}|${e.store.focusAfterMin}|${e.store.owner}"
        if (f != null) e.seats.getValue(f).let { return "seat:$f:$choosingPerson" to "${it.status}|${it.personId}|${it.mode}|${e.driving}" }
        e.seats.values.firstOrNull { it.status == Status.ASKING && !(e.driving && it.pos == SeatPos.FL) }?.let { return "ask:${it.pos}" to "${it.suggestion}|${e.airbagOff}" }
        e.seats.values.firstOrNull { it.pending != null && it.occupied }?.let { return "mode:${it.pos}:${it.pending?.mode}" to "" }
        if (e.globalPending != null) return "global" to "${e.peopleCount}"
        if (e.seats.getValue(SeatPos.FL).mode == Mode.FOCUS) return "focus" to "${e.driveMin}"
        return "idle" to e.seats.values.joinToString { "${it.status}${it.personId}${it.mode}" } + e.driving
    }

    private fun renderCard(e: Engine) {
        val f = focus?.takeIf { e.seats.getValue(it).occupied }
        if (f == null) { focus = null; choosingPerson = false }
        val (kind, detail) = cardKey(e)
        if (kind == cardKind && detail == cardDetail) return
        val animate = kind != cardKind && cardKind.isNotEmpty()
        cardKind = kind; cardDetail = detail
        card.removeAllViews()
        if (animate) card.post { for (i in 0 until card.childCount) riseIn(card.getChildAt(i), i * 50L) }

        if (showSettings) { settings(e); return }
        if (f != null) { seatMenu(e, e.seats.getValue(f)); return }
        e.seats.values.firstOrNull { it.status == Status.ASKING && !(e.driving && it.pos == SeatPos.FL) }?.let { askWho(e, it); return }
        e.seats.values.firstOrNull { it.pending != null && it.occupied }?.let { askMode(e, it); return }
        e.globalPending?.let { g ->
            header(Ui.I_LEAF, Ui.GOOD, "환기할까요?", "${e.peopleCount}명이 오래 함께 탔어요. 바깥 공기로 바꾸면 덜 답답해요.")
            card.addView(primary("바깥 공기로 바꾸기", Ui.GOOD) { e.accept(null) }, lp(-1, top = Ui.S3))
            card.addView(secondary("괜찮아요") { e.decline(null) }, lp(-1, top = Ui.S1))
            return
        }
        val fl = e.seats.getValue(SeatPos.FL)
        if (fl.mode == Mode.FOCUS) {
            header(Ui.I_FOCUS, Ui.GOOD, "운전석을 시원하게 바꿨어요", "${fmtMin(e.driveMin)}째 운전 중이에요. 운전석만 통풍을 켜고 온도를 낮췄어요. 가까운 휴게소에서 쉬어 가세요.")
            card.addView(secondary("끄기") { e.setMode(SeatPos.FL, Mode.NONE) }, lp(-1, top = Ui.S3))
            return
        }
        idle(e)
    }

    private fun idle(e: Engine) {
        val people = e.seats.values.filter { it.status == Status.IDENTIFIED }
        if (people.isEmpty()) {
            header(Ui.I_FAMILY, Ui.ACCENT, "어서 오세요", "자리에 앉으면 누가 탔는지 알아보고, 자리마다 편하게 맞춰 드릴게요.")
            return
        }
        header(Ui.I_CHECK, Ui.GOOD, "모두 편안해요", if (e.driving) "자리를 누르면 쉬기·영상·독서로 바꿀 수 있어요." else "자리를 누르면 쉬기·영상·독서로 바꿀 수 있어요. 출발 전에 맞춰 두면 좋아요.")
        val list = col()
        people.forEach { s ->
            val r = row(Gravity.CENTER_VERTICAL)
            r.addView(avatar(e.nameOf(s)?.take(1) ?: "", e.profile(s.personId)?.color ?: Color.parseColor("#A78BFA"), 40))
            r.addView(text(e.nameOf(s) ?: "", 19f, FG, Ui.semi), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.S2 })
            r.addView(text(if (s.mode == Mode.NONE) s.pos.label else Ui.modeLabel(s.mode), 16f, if (s.mode == Mode.NONE) FG_3 else Ui.modeColor(s.mode), Ui.medium))
            list.addView(r, lp(-1, top = Ui.S1))
        }
        card.addView(list, lp(-1, top = Ui.S2))
    }

    private fun askWho(e: Engine, s: SeatState) {
        val sug = e.profile(s.suggestion)
        header(Ui.I_PERSON, Ui.WARM, "${s.pos.label}에 누가 앉았나요?",
            when {
                s.pos == SeatPos.FL -> "한 번 알려 주시면 이 프로필로 탈 때마다 기억할게요."
                sug != null && e.airbagOff && s.pos == SeatPos.FR -> "조수석에 아이가 앉은 것 같아요."
                sug != null -> "자주 이 자리에 앉는 분이에요."
                else -> "처음 보는 자리예요."
            })
        if (sug != null) card.addView(primary(sug.name.josa("이에요", "예요"), sug.color) { pick(e, s.pos, sug.id) }, lp(-1, top = Ui.S3))
        peopleGrid(e, s, sug?.id)
        val waiting = e.seats.values.count { it.status == Status.ASKING } - 1
        if (waiting > 0) card.addView(text("다음 자리 ${waiting}곳도 확인할게요", 15f, FG_3, Ui.medium), lp(top = Ui.S2))
    }

    private fun peopleGrid(e: Engine, s: SeatState, except: String?) {
        val taken = e.seats.values.filter { it.pos != s.pos && it.status == Status.IDENTIFIED }.mapNotNull { it.personId }.toSet()
        val items = e.profiles.filter { it.id !in taken && it.id != except }
        val grid = row()
        items.forEachIndexed { i, p -> grid.addView(personChip(p.name, p.color) { pick(e, s.pos, p.id) }, cell(i)) }
        card.addView(grid, lp(-1, top = Ui.S1))
        val other = row()
        other.addView(personChip("손님", Color.parseColor("#A78BFA")) { pick(e, s.pos, GUEST) }, cell(0))
        other.addView(personChip("짐이에요", FG_3, Ui.I_BAG) { pick(e, s.pos, OBJECT) }, cell(1))
        card.addView(other, lp(-1, top = Ui.S1))
    }

    private fun askMode(e: Engine, s: SeatState) {
        val sug = s.pending!!
        val m = sug.mode!!
        val who = e.nameOf(s) ?: s.pos.label
        val (title, body, yes) = when (m) {
            Mode.SLEEP -> Triple(sug.title, "시트를 따뜻하게 하고 ${who} 자리 불을 끌게요. 도착 15분 전에 조명으로 살며시 깨워 드려요.", "네, 쉴게요")
            Mode.READ -> Triple(sug.title, "${who} 자리 독서등만 켜고, 실내는 눈이 편한 따뜻한 색으로 맞출게요.", "네, 책 읽을래요")
            Mode.VIDEO -> Triple(sug.title, "${who} 자리 불을 끄고 실내 조명을 낮춰 화면이 잘 보이게 할게요.", "네, 영상 볼래요")
            else -> Triple(sug.title, "", "네")
        }
        header(Ui.modeIcon(m), Ui.modeColor(m), title, body)
        val why = when {
            sug.reason.startsWith("지난") -> "지난번에도 이 자리에서 그렇게 했어요"
            sug.reason.startsWith("야간") -> "밤길이 길어 물어봤어요"
            sug.reason.startsWith("다들") -> "다들 쉬는 중이에요"
            else -> ""
        }
        if (why.isNotEmpty()) card.addView(text(why, 15f, FG_3, Ui.medium), lp(top = Ui.S1))
        card.addView(primary(yes, Ui.modeColor(m)) { e.accept(s.pos) }, lp(-1, top = Ui.S3))
        card.addView(secondary("괜찮아요") { e.decline(s.pos) }, lp(-1, top = Ui.S1))
    }

    private fun seatMenu(e: Engine, s: SeatState) {
        val who = e.nameOf(s)
        val head = row(Gravity.CENTER_VERTICAL)
        head.addView(text(if (s.status == Status.IDENTIFIED) "$who · ${s.pos.label}" else s.pos.label, 24f, FG, Ui.bold), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(iconButton(Ui.I_CLOSE) { focus = null; choosingPerson = false; render() })
        card.addView(head)

        if (choosingPerson || s.status != Status.IDENTIFIED) {
            card.addView(text("누가 앉았나요?", 17f, FG_2, Ui.medium), lp(top = Ui.S1))
            peopleGrid(e, s, null)
            return
        }
        if (s.pos == SeatPos.FL) {
            if (e.driving) {
                card.addView(text("운전 중에는 운전석을 바꾸지 않아요. 필요하면 앱이 알아서 맞추고, 끄기만 할 수 있어요.", 17f, FG_2, Ui.medium).apply { setLineSpacing(4f, 1f) }, lp(-1, top = Ui.S2))
            } else {
                val on = s.mode == Mode.FOCUS
                card.addView(modeTile(Mode.FOCUS, "운전 집중", "운전석만 시원하게", on) { e.setMode(s.pos, if (on) Mode.NONE else Mode.FOCUS); focus = null }, lp(-1, top = Ui.S2))
            }
        } else {
            card.addView(text("무엇을 할까요?", 17f, FG_2, Ui.medium), lp(top = Ui.S1))
            val grid = row()
            listOf(Triple(Mode.SLEEP, "쉬기", "따뜻하게, 불 끄고"), Triple(Mode.VIDEO, "영상", "주변을 어둡게"), Triple(Mode.READ, "독서", "내 자리만 밝게")).forEachIndexed { i, (m, t, d) ->
                val on = s.mode == m
                grid.addView(modeTile(m, t, d, on) { e.setMode(s.pos, if (on) Mode.NONE else m); focus = null },
                    cell(i, 168))
            }
            card.addView(grid, lp(-1, top = Ui.S1))
        }
        if (!(s.pos == SeatPos.FL && e.driving)) {
            card.addView(text("온도는 어떠세요?", 17f, FG_2, Ui.medium), lp(top = Ui.S3))
            val feel = row()
            feel.addView(feelButton(Ui.I_SUN, "더워요", Ui.HOT) { e.complain(s.pos, true) }, cell(0))
            feel.addView(feelButton(Ui.I_HEAT, "추워요", Ui.COOL) { e.complain(s.pos, false) }, cell(1))
            card.addView(feel, lp(-1, top = Ui.S1))
        }
        card.addView(linkButton(Ui.I_SWAP, "다른 사람이에요") { choosingPerson = true; render() }, lp(top = Ui.S3))
    }

    private fun settings(e: Engine) {
        val head = row(Gravity.CENTER_VERTICAL)
        head.addView(text("설정", 24f, FG, Ui.bold), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(iconButton(Ui.I_CLOSE) { showSettings = false; render() })
        card.addView(head)

        card.addView(text("함께 쓰는 조명이 부딪히면", 17f, FG_2, Ui.semi), lp(top = Ui.S2))
        val prio = row()
        prio.addView(segment("운전석부터", "안전 우선", e.store.priority == "safety") { e.setPriority("safety") }, cell(0, 96))
        prio.addView(segment("아이부터", "뒷자리 우선", e.store.priority == "kids") { e.setPriority("kids") }, cell(1, 96))
        card.addView(prio, lp(-1, top = Ui.S1))
        card.addView(text("운전자 졸음 방지는 언제나 먼저 챙겨요.", 14f, FG_3, Ui.medium), lp(top = Ui.S1))

        card.addView(text("운전석을 시원하게 바꾸는 때", 17f, FG_2, Ui.semi), lp(top = Ui.S3))
        val focusRow = row()
        listOf(60, 90, 120).forEachIndexed { i, m ->
            focusRow.addView(segment("${fmtMin(m)}", "연속 운전", e.store.focusAfterMin == m) { e.setFocusAfter(m) }, cell(i, 96))
        }
        card.addView(focusRow, lp(-1, top = Ui.S1))

        card.addView(text("이 차량 프로필", 17f, FG_2, Ui.semi), lp(top = Ui.S3))
        val owner = e.driverProfile
        val o = row(Gravity.CENTER_VERTICAL)
        o.addView(avatar(owner?.name?.take(1) ?: "?", owner?.color ?: Ui.FG_3, 40))
        o.addView(text(owner?.let { "${it.name}의 프로필" } ?: "아직 정하지 않았어요", 19f, FG, Ui.semi), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.S2 })
        if (owner != null) o.addView(linkButton(Ui.I_SWAP, "다시 정하기") { e.forgetOwner(); render() })
        card.addView(o, lp(-1, top = Ui.S1))
    }

    // ── 함께 쓰는 설비 ───────────────────────────────

    private var sharedSig = ""

    private fun renderShared(e: Engine) {
        val on = e.devices.getValue(DevKey(DevKind.AMBIENT_ON).id).actual == true
        val lvl = (e.devices.getValue(DevKey(DevKind.AMBIENT_LEVEL).id).actual as? Number)?.toInt() ?: 0
        val rgb = (e.devices.getValue(DevKey(DevKind.AMBIENT_COLOR).id).actual as? Number)?.toInt()?.let { it or 0xFF000000.toInt() } ?: Color.WHITE
        val recirc = e.devices.getValue(DevKey(DevKind.RECIRC).id).actual == true
        cabin.setAmbient(on, rgb, lvl)
        cabin.breathing = e.seats.values.any { it.occupied && it.mode == Mode.SLEEP } && e.wakeProgress == null
        cabin.setSunrise(e.wakeProgress ?: if (e.route?.arrived == true && e.peopleCount > 0) 1f else 0f)
        val lines = explain(e)
        val sig = "$on|$lvl|$rgb|$recirc|${lines.joinToString()}"
        if (sig == sharedSig) return
        val animate = sharedSig.isNotEmpty()
        sharedSig = sig
        shared.removeAllViews()

        shared.addView(text("함께 쓰는 설비", 16f, FG_3, Ui.semi))
        val r = row(Gravity.CENTER_VERTICAL)
        val sw = View(this).apply { background = Ui.round(if (on) Ui.alpha(rgb, (90 + lvl * 1.6f).toInt().coerceAtMost(255)) else Ui.SURFACE_3, 999f, Ui.LINE, 2) }
        r.addView(sw, LinearLayout.LayoutParams(40, 40))
        r.addView(text(if (on) "실내 조명 $lvl%" else "실내 조명 꺼짐", 19f, FG, Ui.semi), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.S2 })
        r.addView(icon(if (recirc) Ui.I_VENT else Ui.I_LEAF, 24f, FG_2))
        r.addView(text(if (recirc) "실내 순환" else "바깥 공기", 19f, FG, Ui.semi), lpH(8))
        shared.addView(r, lp(-1, top = Ui.S2))

        lines.forEachIndexed { i, l ->
            val tv = text("· $l", 16f, FG_2, Ui.medium).apply { setLineSpacing(4f, 1f) }
            shared.addView(tv, lp(-1, top = Ui.S1))
            if (animate) riseIn(tv, 80L + i * 80)
        }
    }

    /** 공유 설비를 왜 이렇게 맞췄는지 사람 말로 */
    private fun explain(e: Engine): List<String> {
        val out = mutableListOf<String>()
        val r = e.route
        val seats = e.seats.values.filter { it.occupied && it.status == Status.IDENTIFIED }
        fun name(s: SeatState) = e.nameOf(s) ?: ""
        when {
            r?.arrived == true && e.peopleCount > 0 -> out += "도착했어요. 내리기 편하게 조명을 밝혔어요."
            e.wakeProgress != null -> out += "곧 도착해요. 알람 대신 조명을 천천히 밝히고 있어요."
            else -> {
                val sleepers = seats.filter { it.mode == Mode.SLEEP }
                val videos = seats.filter { it.mode == Mode.VIDEO }
                val readers = seats.filter { it.mode == Mode.READ }
                if (sleepers.isNotEmpty()) out += "${sleepers.joinToString { name(it) }.josa("이", "가")} 쉬고 있어 실내 조명을 낮췄어요."
                else if (videos.isNotEmpty()) out += "${videos.joinToString { name(it) }.josa("이", "가")} 영상을 보고 있어 실내 조명을 낮췄어요."
                if (readers.isNotEmpty() && (sleepers.isNotEmpty() || videos.isNotEmpty())) out += "대신 ${readers.joinToString { name(it) }} 자리 독서등은 켜 두었어요."
                else if (readers.isNotEmpty()) out += "${readers.joinToString { name(it) }} 자리에 독서등을 켜고 조명을 따뜻하게 맞췄어요."
            }
        }
        val fl = e.seats.getValue(SeatPos.FL)
        if (fl.mode == Mode.FOCUS) out += "졸음을 막으려고 바깥 공기로 바꿨어요. 운전이 먼저예요."
        else if (e.freshAir) out += "오래 함께 타서 바깥 공기로 환기하고 있어요."
        if (out.isEmpty()) out += "지금은 모두 기본 설정이에요."
        return out
    }

    // ── 입력 ────────────────────────────────────────

    private fun onSeatTap(p: SeatPos) {
        val e = engine ?: return
        if (!e.seats.getValue(p).occupied) return
        showSettings = false; choosingPerson = false
        focus = if (focus == p) null else p
        render()
    }

    private fun pick(e: Engine, p: SeatPos, id: String) {
        focus = null; choosingPerson = false
        e.choose(p, id)
    }

    // ── 부품 ────────────────────────────────────────

    private fun header(ic: String, color: Int, title: String, body: String) {
        card.addView(circleIcon(ic, color, 60))
        card.addView(text(title, 28f, FG, Ui.bold), lp(-1, top = Ui.S2))
        if (body.isNotEmpty()) card.addView(text(body, 17f, FG_2, Ui.medium).apply { setLineSpacing(5f, 1f) }, lp(-1, top = Ui.S1))
    }

    /** 차 화면은 멀리서 보므로 글자를 크게 쓴다 */
    private val K = 1.3f

    private fun text(s: String, size: Float, color: Int, face: android.graphics.Typeface = Ui.medium) = TextView(this).apply {
        text = s; textSize = size * K; setTextColor(color); typeface = face; includeFontPadding = false
    }

    private fun icon(code: String, size: Float, color: Int) = TextView(this).apply {
        text = code; textSize = size * K; setTextColor(color); typeface = Ui.icons; includeFontPadding = false
    }

    private fun circleIcon(code: String, color: Int, d0: Int) = TextView(this).apply {
        val d = (d0 * K).toInt()
        text = code; textSize = d * 0.5f; setTextColor(color); typeface = Ui.icons; gravity = Gravity.CENTER
        background = Ui.round(Ui.alpha(color, 40), 999f)
        layoutParams = LinearLayout.LayoutParams(d, d)
    }

    private fun avatar(letter: String, color: Int, d0: Int) = TextView(this).apply {
        val d = (d0 * K).toInt()
        text = letter; textSize = d * 0.42f; setTextColor(Color.WHITE); typeface = Ui.bold; gravity = Gravity.CENTER
        background = Ui.round(color, 999f)
        layoutParams = LinearLayout.LayoutParams(d, d)
    }

    private fun pill(ic: String, label: String, color: Int) = row(Gravity.CENTER_VERTICAL).apply {
        setPadding(Ui.S2, 0, Ui.S2 + 4, 0)
        background = Ui.round(Ui.alpha(color, 36), Ui.R_PILL)
        layoutParams = LinearLayout.LayoutParams(-2, Ui.H_HEAD)
        addView(icon(ic, 18f, color))
        addView(text(label, 16f, color, Ui.semi), lpH(6))
    }

    private fun modeTag(m: Mode) = row(Gravity.CENTER_VERTICAL).apply {
        val c = Ui.modeColor(m)
        setPadding(Ui.S2 - 2, Ui.S1, Ui.S2, Ui.S1)
        background = Ui.round(Ui.alpha(c, 40), Ui.R_PILL)
        addView(icon(Ui.modeIcon(m), 18f, c))
        addView(text(Ui.modeLabel(m), 16f, c, Ui.semi), lpH(6))
    }

    private fun comfort(ic: String, label: String, color: Int) = row(Gravity.CENTER_VERTICAL).apply {
        setPadding(Ui.S2 - 4, Ui.S1, Ui.S2, Ui.S1)
        background = Ui.round(Ui.SURFACE_3, Ui.R_PILL)
        addView(icon(ic, 17f, color))
        addView(text(label, 15f, FG_2, Ui.semi), lpH(5))
    }

    private fun primary(label: String, color: Int, onClick: () -> Unit) = text(label, 22f, Color.WHITE, Ui.bold).apply {
        gravity = Gravity.CENTER; setPadding(Ui.S3, 0, Ui.S3, 0)
        background = Ui.round(color, Ui.R_IN)
        height = Ui.H_BTN + 8
        setOnClickListener { onClick() }
        pressable()
    }

    private fun secondary(label: String, onClick: () -> Unit) = text(label, 19f, FG_2, Ui.semi).apply {
        gravity = Gravity.CENTER; setPadding(Ui.S3, 0, Ui.S3, 0)
        background = Ui.round(Ui.SURFACE_3, Ui.R_IN)
        height = Ui.H_BTN
        setOnClickListener { onClick() }
        pressable()
    }

    private fun personChip(label: String, color: Int, ic: String? = null, onClick: () -> Unit) = row(Gravity.CENTER).apply {
        setPadding(Ui.S1, 0, Ui.S1, 0)
        background = Ui.round(Ui.SURFACE_2, Ui.R_IN, Ui.alpha(color, 160), 2)
        if (ic != null) addView(icon(ic, 20f, color)) else addView(View(context).apply { background = Ui.round(color, 999f) }, LinearLayout.LayoutParams(12, 12))
        addView(text(label, 18f, FG, Ui.semi).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lpH(8))
        setOnClickListener { onClick() }
        pressable()
    }

    private fun modeTile(m: Mode, title: String, desc: String, on: Boolean, onClick: () -> Unit) = col().apply {
        val c = Ui.modeColor(m)
        setPadding(Ui.S2 + 4, Ui.S2 + 4, Ui.S2, Ui.S2)
        background = Ui.round(if (on) Ui.alpha(c, 50) else Ui.SURFACE_2, Ui.R_IN, if (on) c else Ui.LINE, 2)
        addView(icon(Ui.modeIcon(m), 32f, c))
        addView(text(title, 20f, FG, Ui.bold), lp(top = Ui.S1))
        addView(text(if (on) "켜짐 · 누르면 꺼요" else desc, 14f, if (on) c else FG_3, Ui.medium).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(top = 4))
        setOnClickListener { onClick() }
        pressable()
    }

    private fun feelButton(ic: String, label: String, color: Int, onClick: () -> Unit) = row(Gravity.CENTER).apply {
        setPadding(Ui.S1, 0, Ui.S1, 0)
        background = Ui.round(Ui.SURFACE_2, Ui.R_IN, Ui.LINE, 2)
        addView(icon(ic, 22f, color))
        addView(text(label, 18f, FG, Ui.semi), lpH(8))
        setOnClickListener { onClick() }
        pressable()
    }

    private fun segment(title: String, sub: String, on: Boolean, onClick: () -> Unit) = col().apply {
        setPadding(Ui.S2 + 4, 0, Ui.S2, 0)
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.round(if (on) Ui.alpha(Ui.ACCENT, 45) else Ui.SURFACE_2, Ui.R_IN, if (on) Ui.ACCENT else Ui.LINE, 2)
        addView(text(title, 18f, FG, Ui.bold))
        addView(text(sub, 13f, if (on) Ui.ACCENT else FG_3, Ui.medium), lp(top = 4))
        setOnClickListener { onClick() }
        pressable()
    }

    private fun linkButton(ic: String, label: String, onClick: () -> Unit) = row(Gravity.CENTER_VERTICAL).apply {
        setPadding(4, 8, 4, 8)
        addView(icon(ic, 20f, Ui.ACCENT))
        addView(text(label, 17f, Ui.ACCENT, Ui.semi), lpH(6))
        setOnClickListener { onClick() }
        pressable()
    }

    private fun iconButton(ic: String, onClick: () -> Unit) = TextView(this).apply {
        text = ic; typeface = Ui.icons; textSize = 26f; setTextColor(FG_2); gravity = Gravity.CENTER
        background = Ui.round(Ui.SURFACE, Ui.R_PILL)
        layoutParams = LinearLayout.LayoutParams(Ui.H_HEAD, Ui.H_HEAD)
        setOnClickListener { onClick() }
        pressable()
    }

    // ── 연출 ────────────────────────────────────────

    private val decel = android.view.animation.DecelerateInterpolator(1.6f)

    /** 아래에서 살짝 떠오르며 나타남 */
    private fun riseIn(v: View, delay: Long = 0) {
        v.alpha = 0f; v.translationY = 18f
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(360).setInterpolator(decel).start()
    }

    private fun fadeIn(v: View, delay: Long) {
        v.alpha = 0f
        v.animate().alpha(1f).setStartDelay(delay).setDuration(420).setInterpolator(decel).start()
    }

    private fun slideIn(v: View, delay: Long) {
        v.alpha = 0f; v.translationX = -24f
        v.animate().alpha(1f).translationX(0f).setStartDelay(delay).setDuration(420).setInterpolator(decel).start()
    }

    /** 톡 튀어나옴 */
    private fun pop(v: View) {
        v.scaleX = 0.4f; v.scaleY = 0.4f; v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(520).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
    }

    /** 얼굴 뒤로 퍼지는 물결 두 번 */
    private fun ripple(box: FrameLayout, color: Int) {
        for (i in 0..1) {
            val ring = View(this).apply { background = Ui.round(Color.TRANSPARENT, Ui.R_PILL, color, 4); alpha = 0f }
            box.addView(ring, 0, FrameLayout.LayoutParams(-1, -1))
            ring.animate().setStartDelay(150L + i * 260).withStartAction { ring.alpha = 0.9f }
                .scaleX(2.1f).scaleY(2.1f).alpha(0f).setDuration(1100).setInterpolator(decel)
                .withEndAction { box.removeView(ring) }.start()
        }
    }

    /** 천천히 숨쉬듯 반복(주의를 끌지 않을 만큼만) */
    private fun breathe(v: View, low: Float, ms: Long, alphaOnly: Boolean = false): android.animation.Animator {
        val a = android.animation.ValueAnimator.ofFloat(1f, low).apply {
            duration = ms; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { x -> val f = x.animatedValue as Float; if (alphaOnly) v.alpha = f else { v.scaleX = f.coerceAtLeast(0.9f).let { 1f - (1f - it) * 0.5f }; v.scaleY = v.scaleX } }
        }
        a.start(); return a
    }

    private fun blink(v: View) {
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(300).withEndAction { v.animate().alpha(0.55f).setDuration(500).withEndAction { v.animate().alpha(1f).setDuration(500).start() }.start() }.start()
    }

    /** 온도 숫자가 세어 올라감 */
    private fun countTo(tv: TextView, from: Float, to: Float) {
        android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = 900; interpolator = decel
            addUpdateListener { tv.text = "%.1f°".format(it.animatedValue as Float) }
        }.start()
        tv.animate().scaleX(1.12f).scaleY(1.12f).setDuration(220).withEndAction { tv.animate().scaleX(1f).scaleY(1f).setDuration(320).start() }.start()
    }

    /** 누르면 살짝 눌리는 느낌 */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun <T : View> T.pressable(): T {
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }
            false
        }
        return this
    }

    private fun avatarWithRing(letter: String, color: Int, d0: Int): Pair<FrameLayout, View> {
        val av = avatar(letter, color, d0)
        val size = (av.layoutParams as LinearLayout.LayoutParams).width
        val box = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        av.layoutParams = FrameLayout.LayoutParams(size, size)
        box.addView(av)
        box.layoutParams = LinearLayout.LayoutParams(size, size)
        return box to av
    }

    private fun fmtMin(m: Int) = if (m >= 60) "${m / 60}시간" + (if (m % 60 > 0) " ${m % 60}분" else "") else "${m}분"
    private fun row(g: Int = Gravity.NO_GRAVITY) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = g }
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun lp(w: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(w, -2).apply { topMargin = top; bottomMargin = bottom }
    private fun lpH(left: Int) = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = left }
    /** 한 줄에 나란히 놓는 같은 높이의 칸. 간격은 S1 */
    private fun cell(i: Int, h: Int = Ui.H_CHIP) = LinearLayout.LayoutParams(0, h, 1f).apply { if (i > 0) leftMargin = Ui.S1 }
}
