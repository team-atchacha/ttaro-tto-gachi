package com.hyundaiapptest.seatid

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ① 알아보기 — 누가 어느 자리에 탔는지 판단한다.
 *
 * 판단 순서(좌석마다):
 *  1. 좌석 점유 센서가 켜지면 "착석 감지".
 *  2. 운전석은 차 화면에 로그인된 사용자 프로필이 가족 중 한 명이면 자동 인식.
 *  3. 조수석은 에어백 OFF(무게 센서가 어린이로 판단)면 어린이 중에서 추천.
 *  4. 그 밖에는 그 자리의 평소 탑승 기록으로 추천하고 화면에서 확인받는다.
 *  5. 앉았는데 벨트를 8초 넘게 안 매면 "짐일 수 있음"을 표시한다.
 *  6. 좌석이 비면 하차로 보고 그 자리를 초기화한다.
 *
 * 시트 자세로 사람을 구분하는 방식은 쓰지 않는다. 동승자는 대개 시트를 자기에게 맞추지 않고 그대로 앉기 때문이다.
 *
 * 모든 메서드는 메인 스레드에서 호출한다.
 */
class Engine(private val store: Store, val driver: DriverProfile, private val onChange: () -> Unit) {

    val profiles = store.loadProfiles()
    val seats = SeatPos.values().associateWith { SeatState(it) }
    var airbagOff = false
        private set
    private val logs = ArrayDeque<String>()

    fun profile(id: String?) = profiles.firstOrNull { it.id == id }

    /** 로그인된 차량 사용자 프로필의 주인. 아직 정하지 않았으면 null. */
    val driverProfile get() = profile(store.owner)
    val driverLabel get() = driver.name?.let { "'$it'" } ?: "사용자 ${driver.userId}"

    fun nameOf(s: SeatState): String? = when {
        s.status == Status.OBJECT -> "짐·물건"
        s.personId == GUEST -> "손님"
        else -> profile(s.personId)?.name
    }

    // ── 입력: SDK ───────────────────────────────────────

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
            log(if (who != null) "${p.label}: $who 하차 → 자리 초기화" else "${p.label}: 좌석 비움")
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


    fun onAirbagOff(on: Boolean) {
        if (airbagOff == on) return
        airbagOff = on
        log("조수석 에어백 ${if (on) "OFF 경고등 켜짐 → 무게 센서가 어린이로 판단" else "OFF 경고등 꺼짐"}")
        seat(SeatPos.FR).let { if (it.occupied) identify(it) }
        changed()
    }

    // ── 입력: 시뮬레이션(에뮬레이터가 못 주는 값) ─────────────

    fun onSimBelt(p: SeatPos, v: Boolean) { seat(p).simBelt = v; beltChanged(seat(p)) }


    // ── 입력: 탑승자 응답 ────────────────────────────────

    /** 화면(또는 웹 조작판)에서 그 자리 사람을 고른다. id는 프로필 id, GUEST, OBJECT. */
    fun choose(p: SeatPos, id: String) {
        val s = seat(p)
        if (!s.occupied) return
        val wasSuggestion = id == s.suggestion
        s.manual = true
        s.beltWarn = false
        if (id == OBJECT) {
            s.status = Status.OBJECT; s.personId = null; s.method = "짐으로 지정"
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

    /** 확인을 취소하고 다시 판단한다. */
    fun reidentify(p: SeatPos) {
        val s = seat(p)
        if (!s.occupied) return
        s.manual = false
        identify(s, force = true)
        changed()
    }

    fun resetAll() {
        store.seed()
        seats.values.forEach { it.clearIdentity(); it.simBelt = false; if (it.occupied) { it.status = Status.DETECTED; identify(it) } }
        logs.clear()
        log("시연 초기화 (가족 기록을 예시 데이터로 되돌림)")
        changed()
    }

    /** 1초마다 호출. 벨트 미착용 → 짐 가능성 판단. */
    fun tick() {
        val now = System.currentTimeMillis()
        seats.values.forEach { s ->
            // 이미 사람으로 확인된 자리(프로필 자동 인식 포함)는 짐 경고를 띄우지 않는다
            val warn = s.occupied && !s.belt && !s.manual && s.status != Status.IDENTIFIED &&
                now - s.occupiedSince > BELT_GRACE_MS
            if (warn != s.beltWarn) {
                s.beltWarn = warn
                if (warn) log("${s.pos.label}: 앉았는데 벨트를 안 맴 → 짐일 수 있음")
                changed()
            }
        }
    }

    // ── 판단 ────────────────────────────────────────

    private fun identify(s: SeatState, force: Boolean = false) {
        if (!s.occupied || (s.manual && !force)) return
        val taken = seats.values.filter { it.pos != s.pos && it.status == Status.IDENTIFIED }.mapNotNull { it.personId }.toSet()
        val candidates = profiles.filter { it.id !in taken }

        // 1) 운전석: 차 화면에 로그인된 사용자 프로필
        if (s.pos == SeatPos.FL) {
            val d = driverProfile
            if (d != null && d.id !in taken) {
                setIdentified(s, d.id, "로그인된 차량 프로필($driverLabel)의 주인")
                return
            }
        }
        // 2) 조수석: 무게 센서(에어백 OFF) → 어린이
        if (s.pos == SeatPos.FR && airbagOff) {
            val kids = candidates.filter { it.child }
            val top = kids.maxByOrNull { store.count(s.pos, it.id) } ?: kids.firstOrNull()
            ask(s, top?.id, "무게 센서가 어린이로 판단")
            return
        }
        // 3) 평소 탑승 기록
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

    private fun seat(p: SeatPos) = seats.getValue(p)

    fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        logs.addFirst("$ts  $msg")
        while (logs.size > 40) logs.removeLast()
        android.util.Log.i("SEATID", msg)
    }

    fun logLines(): List<String> = logs.toList()

    private fun changed() = onChange()

    // ── 웹 조작판용 상태 ───────────────────────────────

    fun toJson(): JSONObject = JSONObject().apply {
        put("airbagOff", airbagOff)
        put("driverUser", JSONObject().put("id", driver.userId).put("label", driverLabel).put("profile", driverProfile?.name ?: JSONObject.NULL))
        put("seats", JSONArray().apply {
            SeatPos.values().forEach { p ->
                val s = seat(p)
                put(JSONObject().apply {
                    put("pos", p.name); put("label", p.label); put("front", p.front)
                    put("occupied", s.occupied); put("doorOpen", s.doorOpen)
                    put("belt", s.belt); put("beltSource", s.beltSource); put("beltWarn", s.beltWarn)
                    put("status", s.status.name); put("person", nameOf(s) ?: JSONObject.NULL)
                    put("method", s.method)
                    put("suggestion", s.suggestion ?: JSONObject.NULL)
                    put("suggestionName", profile(s.suggestion)?.name ?: JSONObject.NULL)
                    put("suggestionReason", s.suggestionReason)
                })
            }
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
    }
}
