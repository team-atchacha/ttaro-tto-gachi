package com.hyundaiapptest.seatid

import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue

/** 데모가 다루는 좌석 4개. Virtual Console의 영역 이름과 SDK 영역 값을 함께 가진다. */
enum class SeatPos(
    val label: String,
    val sdkSeat: SeatValue,
    val sdkDoor: DoorValue,
    val consoleArea: String,
    val consoleDoor: String,
    val front: Boolean,
) {
    FL("운전석", SeatValue.ROW_1_LEFT, DoorValue.ROW_1_LEFT, "driver", "door_fl", true),
    FR("조수석", SeatValue.ROW_1_RIGHT, DoorValue.ROW_1_RIGHT, "passenger", "door_fr", true),
    RL("뒷좌석 왼쪽", SeatValue.ROW_2_LEFT, DoorValue.ROW_2_LEFT, "rear_left", "door_rl", false),
    RR("뒷좌석 오른쪽", SeatValue.ROW_2_RIGHT, DoorValue.ROW_2_RIGHT, "rear_right", "door_rr", false),
}

class Profile(
    val id: String,
    val name: String,
    val role: String,
    /** 어린이 여부. 조수석 무게 센서(에어백 OFF) 신호와 맞춰 본다. */
    val child: Boolean,
    val color: Int,
)

enum class Status { EMPTY, DETECTED, ASKING, IDENTIFIED, OBJECT }

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

    fun clearIdentity() {
        status = Status.EMPTY; personId = null; method = ""
        suggestion = null; suggestionReason = ""; manual = false; beltWarn = false
    }
}

const val GUEST = "guest"
const val OBJECT = "object"
