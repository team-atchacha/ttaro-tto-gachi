package com.hyundaiapptest.together

import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue

/** 데모가 다루는 좌석 4개. Virtual Console의 영역 이름과 SDK 영역 값을 함께 가진다. */
enum class SeatPos(
    val label: String,
    val short: String,
    val sdkSeat: SeatValue,
    val sdkDoor: DoorValue,
    val consoleArea: String,
    val consoleDoor: String,
    val front: Boolean,
) {
    FL("운전석", "운전", SeatValue.ROW_1_LEFT, DoorValue.ROW_1_LEFT, "driver", "door_fl", true),
    FR("조수석", "조수", SeatValue.ROW_1_RIGHT, DoorValue.ROW_1_RIGHT, "passenger", "door_fr", true),
    RL("뒷자리 왼쪽", "뒤 왼쪽", SeatValue.ROW_2_LEFT, DoorValue.ROW_2_LEFT, "rear_left", "door_rl", false),
    RR("뒷자리 오른쪽", "뒤 오른쪽", SeatValue.ROW_2_RIGHT, DoorValue.ROW_2_RIGHT, "rear_right", "door_rr", false),
}

class Profile(
    val id: String,
    val name: String,
    val role: String,
    /** 어린이 여부. 조수석 무게 센서(에어백 OFF) 신호와 맞춰 본다. */
    val child: Boolean,
    val color: Int,
)

/** 탑승자 인식 상태 */
enum class Status { EMPTY, DETECTED, ASKING, IDENTIFIED, OBJECT }

/** 자리별 모드. 운전 집중은 운전석 전용이다. */
enum class Mode(val label: String, val icon: String, val driverOnly: Boolean = false) {
    NONE("기본", "·"),
    SLEEP("수면", "☾"),
    VIDEO("영상 시청", "▶"),
    READ("독서·공부", "✎"),
    FOCUS("운전 집중", "◎", driverOnly = true),
}

/** 모드가 어떻게 켜졌는지. 운전자에게는 묻지 않고 자동으로 켠다. */
enum class ModeSource(val label: String) { USER("직접 고름"), ACCEPTED("제안 수락"), AUTO("자동 적용") }

/** 앱이 먼저 묻는 제안 하나. */
class Suggestion(val pos: SeatPos?, val mode: Mode?, val title: String, val reason: String, val kind: String = "mode")

class SeatState(val pos: SeatPos) {
    var occupied = false
    var doorOpen = false

    var sdkBelt = false
    var simBelt = false
    val belt get() = sdkBelt || simBelt
    val beltSource get() = when { sdkBelt -> "SDK"; simBelt -> "시뮬레이션"; else -> "-" }

    var status = Status.EMPTY
    var personId: String? = null
    var method = ""
    var suggestion: String? = null
    var suggestionReason = ""
    /** 사람이 직접 고른 결과면 자동 판정으로 덮어쓰지 않는다. */
    var manual = false
    var occupiedSince = 0L
    var beltWarn = false

    var mode = Mode.NONE
    var modeSource = ModeSource.USER
    /** 이 자리에 대한 모드 제안(먼저 묻기). 한 번 거절하면 이번 여정에서는 다시 묻지 않는다. */
    var pending: Suggestion? = null
    val declined = mutableSetOf<Mode>()
    /** 덥다(+1)·춥다(-1) 요청이 누적된 보정값 */
    var heatBias = 0
    var tempBias = 0f

    fun clearIdentity() {
        status = Status.EMPTY; personId = null; method = ""
        suggestion = null; suggestionReason = ""; manual = false; beltWarn = false
        mode = Mode.NONE; pending = null; declined.clear(); heatBias = 0; tempBias = 0f
    }
}

const val GUEST = "guest"
const val OBJECT = "object"

/** 받침에 맞춘 조사: "엄마는"·"준호는"·"지민은" */
fun String.josa(withBatchim: String, without: String): String {
    val c = lastOrNull() ?: return this + without
    if (c !in '가'..'힣') return this + without
    return this + if ((c - '가') % 28 != 0) withBatchim else without
}
