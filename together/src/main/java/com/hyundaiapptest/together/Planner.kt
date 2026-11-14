package com.hyundaiapptest.together

/**
 * ③ 자리별 맞춤 + ④ 조율.
 * 지금 탑승자·모드·상황을 보고 설비마다 목표값과 이유를 정한다. 차량을 직접 건드리지 않는 순수 계산이다.
 *
 * 규칙
 *  - 자리에만 있는 설비(독서등·시트 온열, 앞자리 통풍·온도)는 그 자리 사람의 모드대로 바로 맞춘다.
 *  - 공유 설비(앰비언트 조명·내외기)는 값이 하나뿐이라 요청을 모아 정한다.
 *      밝기: 가장 어두운 요청에 맞춘다. 밝아야 하는 사람은 자기 독서등으로 보완할 수 있기 때문이다.
 *      색: 우선순위가 가장 높은 요청자의 색. 우선순위는 가족 설정(기본: 운전석→조수석→뒷자리).
 *      내외기: 운전자 졸음 방지(외기)가 항상 이긴다.
 *  - 도착 15분 전에는 깨우기가 수면보다 앞선다(조명을 서서히 밝힘).
 *  - 목표값이 없는 설비는 원래값으로 돌린다(하차·모드 종료 = 정리).
 */
class Planner(private val e: Engine) {

    class Decision(val device: String, val value: String, val why: String, val compensation: String? = null)

    var decisions: List<Decision> = emptyList()
        private set

    fun plan(dev: Map<String, Device>): Map<String, Target> {
        val out = mutableMapOf<String, Target>()
        val notes = mutableListOf<Decision>()
        val wake = e.wakeProgress // null이면 깨우기 아님, 0..1
        fun base(k: DevKey) = dev[k.id]?.baseline
        fun bounds(k: DevKey) = dev[k.id]?.let { it.min to it.max } ?: (null to null)
        fun clampF(k: DevKey, v: Float): Float {
            val (lo, hi) = bounds(k); var x = v
            if (lo != null) x = maxOf(x, lo); if (hi != null) x = minOf(x, hi); return x
        }

        // ── 자리별 설비 ───────────────────────────────
        e.seats.values.forEach { s ->
            val p = s.pos
            if (!s.occupied || s.status == Status.OBJECT) return@forEach
            val who = e.nameOf(s) ?: p.label
            val reading = DevKey(DevKind.READING, p)
            val heat = DevKey(DevKind.HEAT, p)
            when (s.mode) {
                Mode.READ -> out[reading.id] = Target(true, "$who 독서 — 그 자리만 밝게")
                Mode.SLEEP, Mode.VIDEO -> out[reading.id] = Target(false, "$who ${s.mode.label} — 그 자리 불 끄기")
                else -> {}
            }
            // 시트 온열: 수면은 따뜻하게(깨우기 중에는 한 단계 낮춤), 운전 집중은 끔, 덥다·춥다 요청 반영
            val baseHeat = ((base(heat) as? Number)?.toInt() ?: 0).coerceAtLeast(0)
            val heatWanted: Pair<Int, String>? = when (s.mode) {
                Mode.SLEEP -> (if (wake != null) 1 else 2) + s.heatBias to (if (wake != null) "$who 깨우기 — 온열 한 단계 낮춤" else "$who 수면 — 따뜻하게")
                // 앞자리는 온열과 통풍이 한 값(-3~3)을 나눠 쓴다(통풍 3 = 온열 -3). 운전 집중은 통풍만 정한다.
                Mode.FOCUS -> null
                else -> if (s.heatBias != 0) baseHeat + s.heatBias to "$who ${if (s.heatBias > 0) "춥다" else "덥다"} 요청" else null
            }
            heatWanted?.let { (v, why) -> out[heat.id] = Target(clampF(heat, v.toFloat()).toInt().coerceAtLeast(0), why) }

            if (p.front) {
                val vent = DevKey(DevKind.VENT, p)
                val temp = DevKey(DevKind.TEMP, p)
                when (s.mode) {
                    Mode.FOCUS -> out[vent.id] = Target(clampF(vent, 3f).toInt(), "운전 집중 — 통풍 최대")
                    else -> {}
                }
                val baseTemp = (base(temp) as? Number)?.toFloat()
                if (baseTemp != null) {
                    val modeDelta = when (s.mode) { Mode.FOCUS -> -2f; Mode.SLEEP -> if (wake != null) 0f else 1f; else -> 0f }
                    val delta = modeDelta + s.tempBias + e.cabinTempBias
                    if (delta != 0f) {
                        val why = listOfNotNull(
                            when (s.mode) { Mode.FOCUS -> "운전 집중 −2°"; Mode.SLEEP -> if (modeDelta > 0) "$who 수면 +1°" else null; else -> null },
                            if (s.tempBias != 0f) "$who ${if (s.tempBias > 0) "춥다" else "덥다"} ${"%+.0f".format(s.tempBias)}°" else null,
                            if (e.cabinTempBias != 0f) "뒷자리 덥다 ${"%+.0f".format(e.cabinTempBias)}°" else null,
                        ).joinToString(" · ")
                        out[temp.id] = Target(clampF(temp, baseTemp + delta), why)
                    }
                }
            }
        }

        // ── 공유 설비: 앰비언트 조명 ───────────────────────
        class Req(val s: SeatState, val level: Int, val color: Int, val what: String)
        val reqs = e.seats.values.filter { it.occupied && it.status == Status.IDENTIFIED }.mapNotNull { s ->
            when (s.mode) {
                Mode.SLEEP -> Req(s, 10, COLOR_SLEEP, "수면")
                Mode.VIDEO -> Req(s, 15, COLOR_VIDEO, "영상")
                Mode.READ -> Req(s, 35, COLOR_READ, "독서")
                else -> null
            }
        }
        val on = DevKey(DevKind.AMBIENT_ON); val level = DevKey(DevKind.AMBIENT_LEVEL); val color = DevKey(DevKind.AMBIENT_COLOR)
        val arrived = e.route?.arrived == true && e.peopleCount > 0
        if (arrived) {
            out[on.id] = Target(true, "도착 — 내리기 좋게 밝게")
            out[level.id] = Target(70, "도착 — 내리기 좋게 밝게")
            out[color.id] = Target(COLOR_WAKE, "도착 — 햇살 색 유지")
            notes += Decision("조명", "밝기 70%", "도착했어요 — 각자 모드를 끝내고 내리기 좋게 밝힘", "하차하면 원래대로")
        } else if (wake != null) {
            val lv = (10 + (70 - 10) * wake).toInt() / 5 * 5
            out[on.id] = Target(true, "도착 전 깨우기")
            out[level.id] = Target(lv, "도착 ${e.remainMin}분 전 — 소리 대신 조명을 서서히 밝힘")
            out[color.id] = Target(COLOR_WAKE, "깨우기 — 아침 햇살 색")
            notes += Decision("조명", "밝기 $lv% · 햇살 색", "도착 ${e.remainMin}분 전이라 깨우기가 수면보다 앞섬", "알람을 울리지 않아 다른 사람은 방해받지 않음")
        } else if (reqs.isNotEmpty()) {
            val dim = reqs.minBy { it.level }
            val top = reqs.minBy { e.rank(it.s.pos) }
            out[on.id] = Target(true, "${reqs.joinToString { (e.nameOf(it.s) ?: "") + " " + it.what }} 요청")
            out[level.id] = Target(dim.level, "${e.nameOf(dim.s)}(${dim.what}) 요청 — 가장 어두운 쪽에 맞춤")
            out[color.id] = Target(top.color, "${e.nameOf(top.s)}(${top.what}) 요청 — 우선순위 ${e.rank(top.s.pos) + 1}번 자리")
            val brighter = reqs.filter { it.level > dim.level && it.s.mode == Mode.READ }
            notes += Decision(
                "조명 밝기", "${dim.level}%",
                if (reqs.size == 1) "${e.nameOf(dim.s)}(${dim.what})만 요청" else "요청이 부딪힘 → 가장 어두운 ${e.nameOf(dim.s)}(${dim.what})에 맞춤",
                brighter.takeIf { it.isNotEmpty() }?.joinToString { "${e.nameOf(it.s)}(${it.what}) 자리는 독서등으로 보완" },
            )
            if (reqs.map { it.color }.distinct().size > 1)
                notes += Decision("조명 색", Cabin.fmt(DevKind.AMBIENT_COLOR, top.color), "색이 부딪힘 → 우선순위가 높은 ${e.nameOf(top.s)}(${top.s.pos.label}) 쪽", "우선순위: ${e.priorityLabel}")
        }

        // ── 공유 설비: 내·외기 ─────────────────────────
        val recirc = DevKey(DevKind.RECIRC)
        val focus = e.seats.getValue(SeatPos.FL).let { it.occupied && it.mode == Mode.FOCUS }
        when {
            focus -> {
                out[recirc.id] = Target(false, "운전 집중 — 외기로 졸음 방지 (운전자 안전 최우선)")
                val sleepers = e.seats.values.filter { it.mode == Mode.SLEEP }
                notes += Decision("내·외기", "외기", "운전자 졸음 방지가 항상 우선",
                    sleepers.takeIf { it.isNotEmpty() }?.let { "${it.joinToString { s -> e.nameOf(s) ?: "" }.josa("은", "는")} 시트 온열 유지로 보완" })
            }
            e.freshAir -> {
                out[recirc.id] = Target(false, "환기 — 탑승 ${e.peopleCount}명·내기 ${e.recircMin}분 (공기질 센서가 없어 인원·시간으로 추정)")
                notes += Decision("내·외기", "외기", "탑승 인원과 경과 시간으로 환기가 필요하다고 추정")
            }
        }

        decisions = notes
        return out
    }

    companion object {
        val COLOR_SLEEP = 0xFF6D00   // 짙은 호박색
        val COLOR_VIDEO = 0x2979FF   // 파랑
        val COLOR_READ = 0xFFB74D    // 따뜻한 색
        val COLOR_WAKE = 0xFFD180    // 햇살 색
    }
}
