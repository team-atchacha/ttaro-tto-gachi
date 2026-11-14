package com.hyundaiapptest.together

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * 「따로 또 같이」의 판단 엔진. 알아보기 → 먼저 묻기 → 자리별 맞춤 → 조율 → 정리.
 * 모든 메서드는 메인 스레드에서 호출한다.
 */
class Engine(val store: Store, val driver: DriverProfile, private val onChange: () -> Unit) {

    /** 차량에 목표값을 보내는 쪽(VehicleBridge). */
    var actuator: (() -> Unit)? = null

    val profiles = store.profiles
    val seats = SeatPos.values().associateWith { SeatState(it) }
    val devices: Map<String, Device> = Cabin.keys.associate { it.id to Device(it) }
    val planner = Planner(this)
    var airbagOff = false
        private set
    private val logs = ArrayDeque<String>()

    // ── 상황(② 상황 판단) ───────────────────────────
    var gear = "?"
        private set
    var uxRestricted: Boolean? = null
        private set
    val driving get() = gear == "D" || gear == "R" || uxRestricted == true
    var night = true
    /** 시연용 시간 배속(1 = 실제 시간). 에뮬레이터는 실제로 달리지 않아 남은 시간이 줄지 않기 때문이다. */
    var speed = 1
    /** 주행 시계(초). 주행 중에만 흐른다. 실제 경과 + 시연용 빨리 감기. */
    var tripSec = 0.0
        private set
    private var realDriveSec = 0.0
    /** 연속 운전 시간(초). P로 서면 0. */
    var contDriveSec = 0.0
        private set
    private var recircSec = 0.0

    class Route(val name: String, val source: String, val durationSec: Int, val distanceM: Int, val startTripSec: Double, val startSimExtra: Double) {
        var naviRemainSec: Int? = null
        var naviRemainM: Int? = null
        var arrived = false
    }
    var route: Route? = null
        private set
    private val simExtra get() = tripSec - realDriveSec

    /** 남은 시간(초). 내비 값에서 시연용으로 빨리 감은 만큼을 뺀다. */
    val remainSec: Int?
        get() = route?.let { r ->
            val v = r.naviRemainSec?.let { it - (simExtra - r.startSimExtra) }
                ?: (r.durationSec - (tripSec - r.startTripSec))
            v.toInt().coerceAtLeast(0)
        }
    val remainMin get() = remainSec?.let { ceil(it / 60.0).toInt() }

    val wakeProgress: Float?
        get() {
            val r = route ?: return null
            val rem = remainSec ?: return null
            if (r.arrived || rem > WAKE_BEFORE_SEC) return null
            if (seats.values.none { it.occupied && it.mode == Mode.SLEEP }) return null
            return (1f - rem / WAKE_BEFORE_SEC.toFloat()).coerceIn(0f, 1f)
        }
    private var wakeLogged = false

    // ── 조율 상태 ───────────────────────────────────
    var cabinTempBias = 0f
        private set
    var freshAir = false
        private set
    private var freshDeclined = false
    var globalPending: Suggestion? = null
        private set
    private var restActive = false
    private val asked = mutableSetOf<String>()
    var notice: String? = null
        private set
    private var noticeUntil = 0L

    val peopleCount get() = seats.values.count { it.occupied && it.status != Status.OBJECT }
    val recircMin get() = (recircSec / 60).toInt()
    val driveMin get() = (contDriveSec / 60).toInt()

    fun profile(id: String?) = profiles.firstOrNull { it.id == id }
    val driverProfile get() = profile(store.owner)
    val driverLabel get() = driver.label

    fun nameOf(s: SeatState): String? = when {
        s.status == Status.OBJECT -> "짐·물건"
        s.personId == GUEST -> "손님"
        else -> profile(s.personId)?.name
    }

    /** 공유 설비 우선순위(0이 가장 높음) */
    fun rank(p: SeatPos): Int = order().indexOf(p)
    private fun order() = if (store.priority == "kids") listOf(SeatPos.RL, SeatPos.RR, SeatPos.FR, SeatPos.FL)
        else listOf(SeatPos.FL, SeatPos.FR, SeatPos.RL, SeatPos.RR)
    val priorityLabel get() = if (store.priority == "kids") "아이 우선 (뒷자리 → 조수석 → 운전석)" else "안전 우선 (운전석 → 조수석 → 뒷자리)"

    // ── ① 알아보기: SDK 입력 ─────────────────────────

    fun onOccupancy(p: SeatPos, occ: Boolean) {
        val s = seat(p)
        if (s.occupied == occ) return
        s.occupied = occ
        if (occ) {
            s.occupiedSince = System.currentTimeMillis()
            s.status = Status.DETECTED
            log("${p.label}: 착석 감지 (좌석 점유 센서)")
            identify(s)
        } else {
            val who = nameOf(s)
            val hadMode = s.mode != Mode.NONE
            log(if (who != null) "${p.label}: $who 하차 → 자리 설정 원래대로" + (if (hadMode) " (${s.mode.label} 종료)" else "") else "${p.label}: 좌석 비움")
            s.clearIdentity()
            s.simBelt = false
        }
        changed()
    }

    fun onDoor(p: SeatPos, open: Boolean) {
        val s = seat(p)
        if (s.doorOpen == open) return
        s.doorOpen = open
        log("${p.label} 문 ${if (open) "열림" else "닫힘"}" + if (open && !s.occupied) " → 탑승 준비" else "")
        changed()
    }

    fun onSdkBelt(p: SeatPos, v: Boolean) { seat(p).sdkBelt = v; beltChanged(seat(p)) }
    fun onSimBelt(p: SeatPos, v: Boolean) { seat(p).simBelt = v; beltChanged(seat(p)) }

    fun onAirbagOff(on: Boolean) {
        if (airbagOff == on) return
        airbagOff = on
        log("조수석 에어백 ${if (on) "OFF 표시 켜짐 → 무게 센서가 어린이로 판단" else "OFF 표시 꺼짐"}")
        seat(SeatPos.FR).let { if (it.occupied && !it.manual) identify(it) }
        changed()
    }

    // ── ② 상황 판단: SDK 입력 ────────────────────────

    fun onGear(g: String) {
        if (gear == g) return
        val wasDriving = driving
        gear = g
        log("기어 $g" + when {
            !wasDriving && driving -> " → 주행 중. 이제 운전자에게는 묻지 않아요"
            wasDriving && !driving -> " → 정차"
            else -> ""
        })
        if (g == "P") contDriveSec = 0.0
        changed()
    }

    fun onUxRestricted(v: Boolean) {
        if (uxRestricted == v) return
        uxRestricted = v
        log("주행 제한 상태: ${if (v) "제한 필요 (주행 중)" else "제한 없음"}")
        changed()
    }

    fun onRouteStarted(name: String, durationSec: Int, distanceM: Int, source: String) {
        route = Route(name, source, durationSec, distanceM, tripSec, simExtra)
        wakeLogged = false
        log("경로 안내 시작: $name · ${durationSec / 60}분 · ${"%.1f".format(distanceM / 1000.0)}km ($source)")
        changed()
    }

    fun onDestinationInfo(remainSec: Int, remainM: Int) {
        val r = route ?: return
        r.naviRemainSec = remainSec; r.naviRemainM = remainM
        changed()
    }

    fun onRouteEnded(why: String) {
        if (route == null) return
        route = null
        log("경로 안내 종료 ($why)")
        changed()
    }

    fun onDeviceRead() = changed(replan = true)

    // ── 탑승자·시연 조작 ─────────────────────────────

    fun choose(p: SeatPos, id: String) {
        val s = seat(p)
        if (!s.occupied) return
        val wasSuggestion = id == s.suggestion
        s.manual = true
        s.beltWarn = false
        if (id == OBJECT) {
            s.status = Status.OBJECT; s.personId = null; s.method = "짐으로 지정"; s.mode = Mode.NONE; s.pending = null
            log("${p.label}: 짐·물건으로 지정 → 맞춤 대상에서 제외")
            changed(); return
        }
        s.status = Status.IDENTIFIED
        s.personId = id
        s.method = if (wasSuggestion) "추천 확인 · ${s.suggestionReason}" else "화면에서 선택"
        log("${p.label}: ${nameOf(s)} 확인 (${s.method})")
        if (id != GUEST) store.inc(p, id)
        if (p == SeatPos.FL && id != GUEST) {
            val owner = profile(store.owner)
            if (owner == null) {
                store.owner = id
                log("이 차량 프로필($driverLabel)을 ${nameOf(s)}의 프로필로 기억 → 다음부터 자동 인식")
            } else if (owner.id != id) {
                log("운전석: 프로필 주인(${owner.name})과 다른 사람이 운전 중")
            }
        }
        changed()
    }

    fun confirmAllSuggestions() {
        seats.values.filter { it.status == Status.ASKING && it.suggestion != null }.forEach { choose(it.pos, it.suggestion!!) }
    }

    fun reidentify(p: SeatPos) {
        val s = seat(p)
        if (!s.occupied) return
        s.manual = false
        identify(s, force = true)
        changed()
    }

    /** 모드를 켜거나 끈다. 운전석은 운전 집중만, 그 밖의 자리는 운전 집중을 고를 수 없다. */
    fun setMode(p: SeatPos, m: Mode, src: ModeSource = ModeSource.USER, why: String = "") {
        val s = seat(p)
        if (!s.occupied || s.status != Status.IDENTIFIED) return
        if (p == SeatPos.FL && m != Mode.NONE && m != Mode.FOCUS) return
        if (p != SeatPos.FL && m.driverOnly) return
        if (s.mode == m) return
        val prev = s.mode
        s.mode = m; s.modeSource = src; s.pending = null
        if (m == Mode.NONE) {
            if (prev != Mode.NONE) { s.declined += prev; log("${p.label}: ${nameOf(s)} ${prev.label} 끝 → 그 자리 원래대로") }
        } else {
            log("${p.label}: ${nameOf(s)} ${m.label} 모드 (${src.label})" + if (why.isNotEmpty()) " — $why" else "")
            s.personId?.takeIf { it != GUEST }?.let { store.incMode(it, m) }
        }
        changed()
    }

    fun accept(p: SeatPos?) {
        if (p == null) {
            val g = globalPending ?: return
            globalPending = null
            if (g.kind == "fresh") { freshAir = true; log("환기 제안 수락 → 외기로 바꿈") }
            changed(); return
        }
        val s = seat(p)
        val sug = s.pending ?: return
        setMode(p, sug.mode!!, ModeSource.ACCEPTED, sug.reason)
    }

    fun decline(p: SeatPos?) {
        if (p == null) {
            if (globalPending?.kind == "fresh") { freshDeclined = true; log("환기 제안 거절") }
            globalPending = null; changed(); return
        }
        val s = seat(p)
        s.pending?.mode?.let { s.declined += it; log("${p.label}: ${nameOf(s)} ${it.label} 제안 거절 → 이번 여정에서는 다시 묻지 않음") }
        s.pending = null
        changed()
    }

    /** 덥다·춥다(실차에서는 음성. TTS·STT SDK가 열리기 전이라 화면·조작판으로 받는다) */
    fun complain(p: SeatPos, hot: Boolean) {
        val s = seat(p)
        if (!s.occupied || s.status == Status.OBJECT) return
        val who = nameOf(s) ?: p.label
        val heatNow = effective(DevKey(DevKind.HEAT, p))
        if (p.front) {
            if (hot && heatNow > 0) { s.heatBias -= 1; log("$who(${p.label}) 덥다 → 그 자리 시트 온열 한 단계 낮춤") }
            else if (hot) { s.tempBias = (s.tempBias - 1).coerceAtLeast(-3f); log("$who(${p.label}) 덥다 → ${p.label} 쪽 설정 온도만 1도 낮춤 (좌우 따로 조절)") }
            else { s.tempBias = (s.tempBias + 1).coerceAtMost(3f); log("$who(${p.label}) 춥다 → ${p.label} 쪽 설정 온도만 1도 올림 (좌우 따로 조절)") }
            changed(); return
        }
        if (!hot) {
            s.heatBias = (s.heatBias + 1).coerceAtMost(3)
            log("$who(${p.label}) 춥다 → 그 자리 시트 온열 한 단계 올림")
            changed(); return
        }
        if (heatNow > 0) {
            s.heatBias -= 1
            log("$who(${p.label}) 덥다 → 그 자리 시트 온열부터 한 단계 낮춤")
            changed(); return
        }
        // 뒷자리는 온도를 따로 나눌 수 없다 → 앞 온도를 낮춰야 하는데, 앞자리에 따뜻해야 하는 사람이 있으면 우선순위로 정한다
        val warmWanted = seats.values.filter { it.occupied && it.pos.front && it.mode == Mode.SLEEP }
        val blockers = warmWanted.filter { rank(it.pos) < rank(p) }
        if (blockers.isNotEmpty()) {
            val b = blockers.first()
            log("$who(${p.label}) 덥다 → 시트 온열은 이미 꺼짐. 실내 온도는 ${b.pos.label}의 ${(nameOf(b) ?: "").josa("이", "가")} 자는 중이고 우선순위가 더 높아 그대로 둠")
            note("$who: 시트 온열을 껐어요. ${(nameOf(b) ?: "").josa("이", "가")} 자고 있어 실내 온도는 그대로 둘게요")
        } else {
            cabinTempBias = (cabinTempBias - 1).coerceAtLeast(-3f)
            warmWanted.forEach { it.heatBias += 1 }
            log("$who(${p.label}) 덥다 → 뒷자리는 온도를 따로 못 나눠 앞 온도를 1도 낮춤" +
                if (warmWanted.isNotEmpty()) " · ${warmWanted.joinToString { nameOf(it) ?: "" }.josa("은", "는")} 시트 온열을 올려 보완" else "")
        }
        changed()
    }

    fun setPriority(v: String) {
        store.priority = v
        log("공유 설비 우선순위: $priorityLabel (운전자 졸음 방지는 항상 우선)")
        changed()
    }

    /** 이 차량 프로필의 주인을 잊는다(다음에 운전석에 앉으면 다시 묻는다) */
    fun forgetOwner() {
        store.owner = null
        log("이 차량 프로필($driverLabel)의 주인을 지움 → 다음 탑승 때 다시 물어봄")
        seats.getValue(SeatPos.FL).let { if (it.occupied) { it.manual = false; identify(it, force = true) } }
        changed()
    }

    fun setFocusAfter(min: Int) { store.focusAfterMin = min; log("운전 집중 제안 시점: 연속 운전 ${min}분"); changed() }
    fun setNightMode(v: Boolean) { night = v; log(if (v) "야간 주행으로 설정" else "주간으로 설정"); changed() }
    fun setSpeedMode(v: Int) { speed = v; log(if (v > 1) "시연용 시간 ${v}배속" else "실제 시간"); changed() }

    /** 시연용 빨리 감기(분). 주행 시계·연속 운전·탑승 시간이 함께 흐른다. */
    fun jump(min: Int) {
        if (min <= 0) return
        advance(min * 60.0, simulated = true)
        log("시연용 시간 ${min}분 빨리 감기 → 연속 운전 ${driveMin}분" + (remainMin?.let { ", 남은 시간 ${it}분" } ?: ""))
        changed()
    }

    fun jumpToRemain(min: Int) { remainMin?.let { jump(it - min) } }

    fun startSimRoute() = onRouteStarted("집 (시연용 경로)", 90 * 60, 82_000, "내비 응답 없음 → 시연용")

    fun resetAll() {
        store.seed()
        seats.values.forEach { it.clearIdentity(); it.simBelt = false; if (it.occupied) { it.status = Status.DETECTED; identify(it) } }
        tripSec = 0.0; realDriveSec = 0.0; contDriveSec = 0.0; recircSec = 0.0
        route = null; cabinTempBias = 0f; freshAir = false; freshDeclined = false; globalPending = null
        restActive = false; asked.clear(); notice = null; wakeLogged = false; speed = 1
        // 시연을 반복할 수 있게 설비를 차량 기본값으로 돌린다(SDK로 보냄)
        devices.values.forEach { it.baseline = Cabin.default(it.key.kind); it.touched = true; it.target = null; it.lastSent = null; it.state = CmdState.IDLE; it.message = "" }
        logs.clear()
        log("시연 초기화 (가족 기록을 예시 데이터로 되돌림, 설비는 원래값으로)")
        changed()
    }

    // ── 시간 흐름 ───────────────────────────────────

    private var lastTick = System.currentTimeMillis()

    /** 1초마다 호출 */
    fun tick() {
        val now = System.currentTimeMillis()
        val dt = (now - lastTick) / 1000.0
        lastTick = now
        if (driving) {
            realDriveSec += dt
            advance(dt * speed, simulated = false)
        }
        if (notice != null && now > noticeUntil) notice = null
        beltCheck(now)
        situation()
        changed()
    }

    private fun advance(sec: Double, simulated: Boolean) {
        tripSec += sec
        if (driving || simulated) contDriveSec += sec
        if (devices.getValue(DevKey(DevKind.RECIRC).id).actual == true) recircSec += sec else recircSec = 0.0
    }

    /** 먼저 묻기와 자동 적용 */
    private fun situation() {
        val fl = seat(SeatPos.FL)
        val passengers = seats.values.filter { it.pos != SeatPos.FL && it.occupied && it.status == Status.IDENTIFIED }

        // 다 같이 휴식: 야간에 동승자 절반 이상이 수면
        val sleepers = passengers.count { it.mode == Mode.SLEEP }
        val rest = night && passengers.size >= 2 && sleepers * 2 >= passengers.size
        if (rest != restActive) {
            restActive = rest
            if (rest) log("다 같이 휴식: 동승자 ${passengers.size}명 중 ${sleepers}명 수면 → 나머지 자리에도 수면을 제안, 운전자는 집중 유지")
        }

        // 도착한 뒤에는 새로 제안하지 않는다
        val arrived = route?.arrived == true

        // 운전자: 묻지 않고 자동 적용, 끄기만 가능
        if (driving && !arrived && fl.occupied && fl.status == Status.IDENTIFIED && fl.mode == Mode.NONE && Mode.FOCUS !in fl.declined) {
            val why = when {
                contDriveSec >= store.focusAfterMin * 60 -> "연속 운전 ${driveMin}분 (설정 ${store.focusAfterMin}분)"
                rest -> "다들 쉬는 동안 운전자 집중 유지"
                else -> null
            }
            if (why != null) {
                setMode(SeatPos.FL, Mode.FOCUS, ModeSource.AUTO, why)
                note("운전석만 시원하게 바꿨어요. 가까운 휴게소에서 쉬어 가세요")
            }
        }

        // 동승자: 큰 버튼 하나로 묻는다
        if (arrived) passengers.forEach { it.pending = null }
        passengers.filter { !arrived && it.mode == Mode.NONE && it.pending == null }.forEach { s ->
            val who = nameOf(s) ?: return@forEach
            val ride = rideMin(s)
            val pid = s.personId
            val hist = pid?.takeIf { it != GUEST }?.let { id ->
                listOf(Mode.READ, Mode.VIDEO).map { it to store.modeCount(id, it) }.filter { it.second >= 2 }.maxByOrNull { it.second }
            }
            when {
                rest && Mode.SLEEP !in s.declined ->
                    s.pending = Suggestion(s.pos, Mode.SLEEP, "$who, 같이 쉴래요?", "다들 쉬는 중")
                night && driving && ride >= 20 && Mode.SLEEP !in s.declined && ask(s, "sleep") ->
                    s.pending = Suggestion(s.pos, Mode.SLEEP, "$who, 좀 주무실래요?", "야간 주행 · 탄 지 ${ride}분")
                // 지난번 모드는 출발 전에 묻는다(탑승하자마자). 독서등 같은 설비는 정차 중에 맞춰 두는 편이 안전하다.
                hist != null && hist.first !in s.declined && ask(s, "hist") ->
                    s.pending = Suggestion(s.pos, hist.first, "$who, 지난번처럼 ${if (hist.first == Mode.READ) "책 읽을래요?" else "영상 볼래요?"}", "지난 탑승 ${hist.second}번 ${hist.first.label}")
            }
            s.pending?.let { log("${s.pos.label}: 먼저 묻기 — ${it.title} (${it.reason})") }
        }

        // 환기: 공기질 센서가 없어 인원·시간으로 추정
        if (!freshAir && !freshDeclined && globalPending == null && peopleCount >= 3 && recircMin >= 30 && fl.mode != Mode.FOCUS) {
            val fr = seat(SeatPos.FR)
            val canAsk = fr.status == Status.IDENTIFIED && fr.mode != Mode.SLEEP && profile(fr.personId)?.child != true
            if (canAsk) {
                globalPending = Suggestion(SeatPos.FR, null, "환기할까요?", "${peopleCount}명이 내기로 ${recircMin}분 탔어요", kind = "fresh")
                log("먼저 묻기 — 환기 (${peopleCount}명 · 내기 ${recircMin}분, 조수석에 물음)")
            } else {
                freshAir = true
                log("환기: ${peopleCount}명 · 내기 ${recircMin}분 → 물어볼 앞자리 동승자가 없어 외기로 자동 전환")
            }
        }

        // 도착 전 깨우기와 도착
        val r = route
        if (r != null && !r.arrived) {
            if (wakeProgress != null && !wakeLogged) {
                wakeLogged = true
                log("도착 ${remainMin}분 전 → 수면 중인 자리를 조명으로 서서히 깨움 (알람 없음)")
                note("곧 도착해요")
            }
            if ((remainSec ?: 1) <= 0) {
                r.arrived = true
                log("목적지 도착 (남은 시간 0 — 에뮬레이터는 도착 이벤트가 없어 남은 시간으로 판단)")
                seats.values.filter { it.mode != Mode.NONE }.forEach { s ->
                    val m = s.mode
                    setMode(s.pos, Mode.NONE)
                    s.declined -= m // 다음 여정에서 다시 제안할 수 있게
                }
                log("도착 → 각자 모드 종료, 조명은 내리기 좋게 밝힘")
                note("도착했어요")
            }
        }
    }

    private fun ask(s: SeatState, what: String) = asked.add("${s.pos}:${s.personId}:$what")
    private fun rideMin(s: SeatState) = ((tripSec - s.boardTrip) / 60).toInt()
    private val SeatState.boardTrip get() = boardedAt[pos] ?: 0.0
    private val boardedAt = mutableMapOf<SeatPos, Double>()

    private fun beltCheck(now: Long) {
        seats.values.forEach { s ->
            val warn = s.occupied && !s.belt && !s.manual && s.status != Status.IDENTIFIED &&
                now - s.occupiedSince > BELT_GRACE_MS
            if (warn != s.beltWarn) {
                s.beltWarn = warn
                if (warn) log("${s.pos.label}: 앉았는데 벨트를 안 맴 → 짐일 수 있음")
            }
        }
    }

    // ── 판단 ────────────────────────────────────────

    private fun identify(s: SeatState, force: Boolean = false) {
        if (!s.occupied || (s.manual && !force)) return
        boardedAt[s.pos] = tripSec
        val taken = seats.values.filter { it.pos != s.pos && it.status == Status.IDENTIFIED }.mapNotNull { it.personId }.toSet()
        val candidates = profiles.filter { it.id !in taken }

        if (s.pos == SeatPos.FL) {
            val d = driverProfile
            if (d != null && d.id !in taken) { setIdentified(s, d.id, "로그인된 차량 프로필($driverLabel)의 주인"); return }
        }
        if (s.pos == SeatPos.FR && airbagOff) {
            val kids = candidates.filter { it.child }
            val top = kids.maxByOrNull { store.count(s.pos, it.id) } ?: kids.firstOrNull()
            ask(s, top?.id, "무게 센서가 어린이로 판단 (에어백 OFF)")
            return
        }
        val top = candidates.map { it to store.count(s.pos, it.id) }.filter { it.second > 0 }.maxByOrNull { it.second }
        if (top != null) ask(s, top.first.id, "평소 이 자리 ${top.second}회")
        else ask(s, null, if (s.pos == SeatPos.FL) "이 차량 프로필($driverLabel)의 주인을 아직 몰라요" else "기록 없음")
    }

    private fun setIdentified(s: SeatState, id: String, method: String) {
        if (s.status == Status.IDENTIFIED && s.personId == id && s.method == method) return
        s.status = Status.IDENTIFIED; s.personId = id; s.method = method
        s.suggestion = null; s.manual = false
        log("${s.pos.label}: ${profile(id)?.name} 자동 인식 ($method)")
    }

    private fun ask(s: SeatState, suggestion: String?, reason: String) {
        if (s.status == Status.ASKING && s.suggestion == suggestion && s.suggestionReason == reason) return
        s.status = Status.ASKING; s.personId = null; s.method = ""
        s.suggestion = suggestion; s.suggestionReason = reason
        val sug = profile(suggestion)?.name
        log("${s.pos.label}: 누구인지 확인 요청" + if (sug != null) " — 추천 $sug ($reason)" else " ($reason)")
    }

    private fun beltChanged(s: SeatState) {
        if (s.belt && s.beltWarn) { s.beltWarn = false; log("${s.pos.label}: 벨트 착용 → 사람으로 판단") }
        changed()
    }

    private fun effective(k: DevKey): Int {
        val d = devices.getValue(k.id)
        return ((d.target?.value ?: d.actual) as? Number)?.toInt() ?: 0
    }

    private fun seat(p: SeatPos) = seats.getValue(p)

    private fun note(msg: String) { notice = msg; noticeUntil = System.currentTimeMillis() + 9000 }

    fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        logs.addFirst("$ts  $msg")
        while (logs.size > 60) logs.removeLast()
        android.util.Log.i("TOGETHER", msg)
    }

    fun logLines(): List<String> = logs.toList()

    private fun changed(replan: Boolean = true) {
        if (replan) {
            val targets = planner.plan(devices)
            devices.values.forEach { d -> d.target = targets[d.key.id] }
            actuator?.invoke()
        }
        onChange()
    }

    // ── 웹 조작판용 상태 ───────────────────────────────

    fun toJson(): JSONObject = JSONObject().apply {
        put("airbagOff", airbagOff)
        put("driverUser", JSONObject().put("id", driver.userId).put("label", driverLabel).put("profile", driverProfile?.name ?: JSONObject.NULL))
        put("trip", JSONObject().apply {
            put("gear", gear); put("ux", uxRestricted ?: JSONObject.NULL); put("driving", driving)
            put("night", night); put("speed", speed); put("driveMin", driveMin); put("recircMin", recircMin)
            put("remainMin", remainMin ?: JSONObject.NULL)
            put("wake", wakeProgress?.toDouble() ?: JSONObject.NULL)
            put("route", route?.let { r -> JSONObject().put("name", r.name).put("source", r.source).put("durationMin", r.durationSec / 60).put("arrived", r.arrived)
                .put("naviRemainMin", r.naviRemainSec?.let { it / 60 } ?: JSONObject.NULL) } ?: JSONObject.NULL)
        })
        put("settings", JSONObject().put("priority", store.priority).put("priorityLabel", priorityLabel).put("focusAfterMin", store.focusAfterMin))
        put("notice", notice ?: JSONObject.NULL)
        put("globalPending", globalPending?.let { JSONObject().put("title", it.title).put("reason", it.reason) } ?: JSONObject.NULL)
        put("seats", JSONArray().apply {
            SeatPos.values().forEach { p ->
                val s = seat(p)
                put(JSONObject().apply {
                    put("pos", p.name); put("label", p.label); put("front", p.front)
                    put("occupied", s.occupied); put("doorOpen", s.doorOpen)
                    put("belt", s.belt); put("beltSource", s.beltSource); put("beltWarn", s.beltWarn)
                    put("status", s.status.name); put("person", nameOf(s) ?: JSONObject.NULL); put("personId", s.personId ?: JSONObject.NULL)
                    put("method", s.method)
                    put("suggestion", s.suggestion ?: JSONObject.NULL)
                    put("suggestionName", profile(s.suggestion)?.name ?: JSONObject.NULL)
                    put("suggestionReason", s.suggestionReason)
                    put("mode", s.mode.name); put("modeLabel", s.mode.label); put("modeSource", s.modeSource.label)
                    put("pending", s.pending?.let { JSONObject().put("mode", it.mode?.name).put("title", it.title).put("reason", it.reason) } ?: JSONObject.NULL)
                })
            }
        })
        put("devices", JSONArray().apply {
            devices.values.forEach { d ->
                put(JSONObject().apply {
                    put("id", d.key.id); put("kind", d.key.kind.name); put("seat", d.key.seat?.name ?: JSONObject.NULL)
                    put("label", d.key.label); put("shared", d.key.kind.shared); put("supported", d.supported)
                    put("actual", Cabin.fmt(d.key.kind, d.actual)); put("baseline", Cabin.fmt(d.key.kind, d.baseline))
                    put("target", d.target?.let { Cabin.fmt(d.key.kind, it.value) } ?: JSONObject.NULL)
                    put("why", d.target?.reason ?: JSONObject.NULL)
                    put("state", d.state.name); put("message", d.message)
                    if (d.key.kind == DevKind.AMBIENT_COLOR) put("rgb", (d.actual as? Number)?.toInt()?.let { "#%06X".format(it and 0xFFFFFF) } ?: JSONObject.NULL)
                })
            }
        })
        put("decisions", JSONArray().apply {
            planner.decisions.forEach { put(JSONObject().put("device", it.device).put("value", it.value).put("why", it.why).put("compensation", it.compensation ?: JSONObject.NULL)) }
        })
        put("profiles", JSONArray().apply {
            profiles.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("name", p.name); put("role", p.role); put("child", p.child)
                    put("history", JSONObject().apply { SeatPos.values().forEach { sp -> put(sp.name, store.count(sp, p.id)) } })
                })
            }
        })
        put("log", JSONArray(logs.toList()))
    }

    companion object {
        const val BELT_GRACE_MS = 8000L
        const val WAKE_BEFORE_SEC = 15 * 60
    }
}
