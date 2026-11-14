package com.hyundaiapptest.together

/**
 * 앱이 제어하는 설비 목록과 각 설비의 현재값·원래값·목표값.
 *
 * 설비는 두 종류다.
 *  - 자리별 설비: 독서등·시트 온열(4자리), 통풍·설정 온도(앞 두 자리)
 *  - 공유 설비: 앰비언트 조명(켜기·밝기·색), 내·외기 순환 — 차 전체가 값 하나를 나눠 쓴다
 */
enum class DevKind(val label: String, val shared: Boolean) {
    READING("독서등", false),
    HEAT("시트 온열", false),
    VENT("시트 통풍", false),
    TEMP("설정 온도", false),
    AMBIENT_ON("앰비언트 조명", true),
    AMBIENT_LEVEL("조명 밝기", true),
    AMBIENT_COLOR("조명 색", true),
    RECIRC("내·외기", true),
}

data class DevKey(val kind: DevKind, val seat: SeatPos? = null) {
    val id get() = kind.name + (seat?.let { ".${it.name}" } ?: "")
    val label get() = (seat?.let { "${it.label} " } ?: "") + kind.label
}

/** 계획(Planner)이 정한 목표값과 그 이유 */
data class Target(val value: Any, val reason: String)

/** DEFERRED: 주행 중 제한으로 거부됨 → 정차하면 다시 보낸다 */
enum class CmdState { IDLE, PENDING, VERIFIED, FAILED, BLOCKED, DEFERRED }

class Device(val key: DevKey) {
    /** 차량에서 마지막으로 읽은 값 */
    var actual: Any? = null
    /** 앱이 처음 읽은 값. 하차하거나 모드가 끝나면 이 값으로 돌린다. */
    var baseline: Any? = null
    /** 앱이 이 설비를 한 번이라도 바꿨는지(바꾼 적 없으면 원래값으로 되돌릴 필요도 없다) */
    var touched = false
    var target: Target? = null
    var lastSent: Any? = null
    var state = CmdState.IDLE
    var message = ""
    /** SDK가 알려 준 지원 범위(숫자형 설비) */
    var min: Float? = null
    var max: Float? = null
    var supported = true
    var retries = 0
}

object Cabin {
    val keys: List<DevKey> = buildList {
        SeatPos.values().forEach { p ->
            add(DevKey(DevKind.READING, p))
            add(DevKey(DevKind.HEAT, p))
            if (p.front) { add(DevKey(DevKind.VENT, p)); add(DevKey(DevKind.TEMP, p)) }
        }
        add(DevKey(DevKind.AMBIENT_ON)); add(DevKey(DevKind.AMBIENT_LEVEL)); add(DevKey(DevKind.AMBIENT_COLOR))
        add(DevKey(DevKind.RECIRC))
    }

    fun same(a: Any?, b: Any?): Boolean = when {
        a == null || b == null -> a == b
        a is Number && b is Number -> kotlin.math.abs(a.toFloat() - b.toFloat()) < 0.26f
        else -> a == b
    }

    /** 시연 초기화 때 돌려 둘 차량 기본값 */
    fun default(kind: DevKind): Any = when (kind) {
        DevKind.READING, DevKind.AMBIENT_ON, DevKind.RECIRC -> false
        DevKind.HEAT, DevKind.VENT -> 0
        DevKind.TEMP -> 22f
        DevKind.AMBIENT_LEVEL -> 100
        DevKind.AMBIENT_COLOR -> 0xFFFFFF
    }

    fun fmt(kind: DevKind, v: Any?): String = when {
        v == null -> "-"
        kind == DevKind.READING || kind == DevKind.AMBIENT_ON -> if (v == true) "켜짐" else "꺼짐"
        kind == DevKind.RECIRC -> if (v == true) "내기" else "외기"
        kind == DevKind.TEMP -> "%.1f°".format((v as Number).toFloat())
        kind == DevKind.AMBIENT_LEVEL -> "${(v as Number).toInt()}%"
        kind == DevKind.AMBIENT_COLOR -> "#%06X".format((v as Number).toInt() and 0xFFFFFF)
        // 앞자리 온열 값이 음수면 통풍 중이라는 뜻이다
        kind == DevKind.HEAT && (v as Number).toInt() < 0 -> "꺼짐"
        kind == DevKind.HEAT || kind == DevKind.VENT -> if ((v as Number).toInt() == 0) "꺼짐" else "${v.toInt()}단"
        else -> v.toString()
    }
}
