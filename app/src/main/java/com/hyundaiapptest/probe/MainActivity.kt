package com.hyundaiapptest.probe

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 아이디어 검증용 프로브 앱.
 * 각 Vehicle SDK API를 호출해 결과(값 또는 예외)를 화면과 logcat(태그 PROBE)에 남긴다.
 */
class MainActivity : Activity() {

    private val tag = "PROBE"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var out: TextView
    private lateinit var vehicle: Vehicle

    private val seats = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)
    private val doors = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)

    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(p0: Telltales?) = log("[구독] 경고등 변경: ${p0?.let { describe(it) }}")
        override fun onFailed(p0: Exception) = log("[구독] registerTelltales 실패: ${err(p0)}")
    }
    private val seatListeners = seats.associateWith { seat ->
        object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(p0: Boolean?) = log("[구독] 좌석 $seat 점유=$p0")
            override fun onFailed(p0: Exception) = log("[구독] 좌석 $seat 실패: ${err(p0)}")
        }
    }
    private val doorListeners = doors.associateWith { door ->
        object : DoorPositionListener {
            override fun onPositionUpdated(p0: Int?) = log("[구독] 도어 $door 위치=$p0")
            override fun onFailed(p0: Exception) = log("[구독] 도어 $door 실패: ${err(p0)}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 14f; setPadding(24, 24, 24, 24) }
        val rerun = Button(this).apply { text = "조회 다시 실행"; setOnClickListener { runQueries() } }
        val control = Button(this).apply { text = "제어 테스트 실행"; setOnClickListener { runControls() } }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val ident = Button(context).apply { text = "좌석 인식 테스트"; setOnClickListener { runSeatIdent() } }
            addView(rerun); addView(control); addView(ident)
            addView(ScrollView(context).apply { addView(out) })
        }
        setContentView(root)

        checkPermissions()
        vehicle = Vehicle(this)
        try {
            vehicle.initialize()
            log("Vehicle.initialize() 반환")
        } catch (e: Exception) {
            log("Vehicle.initialize() 예외: ${err(e)}")
        }
        // 서비스 바인딩을 기다린 뒤 실행
        main.postDelayed({ runQueries(); subscribe() }, 3000)
        // adb에서 `am start ... --es run ident`로 실행하면 좌석 인식 테스트를 자동으로 돌린다
        if (intent.getStringExtra("run") == "ident") main.postDelayed({ runSeatIdent() }, 5000)
    }

    private fun checkPermissions() {
        listOf(
            "CAR_SAFETY", "CAR_SEATS", "CAR_DOORS", "CAR_LIGHTS",
            "CONTROL_CAR_SEATS", "CONTROL_CAR_LIGHTS", "PRIVATE_CAR_SAFETY",
        ).forEach {
            val p = "pleos.car.permission.$it"
            val granted = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            log("권한 $it: ${if (granted) "GRANTED" else "DENIED"}")
        }
    }

    private fun runQueries() {
        val safety = vehicle.getSafety()
        val seat = vehicle.getSeat()
        val door = vehicle.getDoor()

        call("getTelltalesInfo") {
            safety.getTelltalesInfo({ log("getTelltalesInfo 성공: ${describe(it)}") }, { log("getTelltalesInfo 실패: ${err(it)}") })
        }
        call("checkTelltalesCapabilities") {
            safety.checkTelltalesCapabilities({ log("checkTelltalesCapabilities 성공: $it") }, { log("checkTelltalesCapabilities 실패: ${err(it)}") })
        }
        call("hasLowWasherFluidWarning") {
            safety.hasLowWasherFluidWarning({ log("워셔액 부족 경고=$it") }, { log("hasLowWasherFluidWarning 실패: ${err(it)}") })
        }
        seats.forEach { s ->
            call("getCurrentSeatOccupancy $s") {
                seat.getCurrentSeatOccupancy(SeatArea(s), { log("좌석 $s 점유=$it") }, { log("좌석 $s 점유 조회 실패: ${err(it)}") })
            }
            call("hasSeatBeltNotBuckledWarning $s") {
                safety.hasSeatBeltNotBuckledWarning(SeatArea(s), { log("좌석 $s 벨트 미착용 경고=$it") }, { log("좌석 $s 벨트 경고 조회 실패: ${err(it)}") })
            }
        }
        doors.forEach { d ->
            call("getCurrentDoorPosition $d") {
                door.getCurrentDoorPosition(DoorArea(d), { log("도어 $d 위치=$it") }, { log("도어 $d 조회 실패: ${err(it)}") })
            }
        }
    }

    private fun subscribe() {
        call("registerTelltales") { vehicle.getSafety().registerTelltales(telltaleListener) }
        seatListeners.forEach { (s, l) -> call("registerSeatOccupancy $s") { vehicle.getSeat().registerSeatOccupancy(SeatArea(s), l) } }
        doorListeners.forEach { (d, l) -> call("registerDoorPosition $d") { vehicle.getDoor().registerDoorPosition(DoorArea(d), l) } }
    }

    private fun runControls() {
        val light = vehicle.getLight()
        val seat = vehicle.getSeat()
        call("setReadingLightSwitch ROW_2_LEFT ON") {
            light.setReadingLightSwitch(SeatArea(SeatValue.ROW_2_LEFT), LightSwitch.ON,
                { log("독서등 ROW_2_LEFT 요청 완료: $it"); readLight() },
                { log("독서등 제어 실패: ${err(it)}") })
        }
        call("setHvacSeatTemperature ROW_1_RIGHT 2") {
            seat.setHvacSeatTemperature(SeatArea(SeatValue.ROW_1_RIGHT), 2,
                { a, v -> log("시트 온열 요청 완료: ${a.values.toList()} -> $v"); readSeatTemp() },
                { log("시트 온열 제어 실패: ${err(it)}") })
        }
    }

    /** 좌석 인식용 API(벨트·시트 위치)가 에뮬레이터에서 값을 주는지 확인한다. */
    private fun runSeatIdent() {
        val seat = vehicle.getSeat()
        call("checkSeatBeltBuckledCapabilities") {
            seat.checkSeatBeltBuckledCapabilities({ log("벨트 지원 좌석: ${it.map { a -> a.values.toList() }}") }, { log("벨트 지원 확인 실패: ${err(it)}") })
        }
        call("checkSeatForeAftCapabilities") {
            seat.checkSeatForeAftCapabilities({ log("앞뒤 위치 범위: ${it.mapKeys { (a, _) -> a.values.toList() }}") }, { log("앞뒤 범위 실패: ${err(it)}") })
        }
        call("checkSeatHeightCapabilities") {
            seat.checkSeatHeightCapabilities({ log("높이 범위: ${it.mapKeys { (a, _) -> a.values.toList() }}") }, { log("높이 범위 실패: ${err(it)}") })
        }
        call("checkSeatBackrestAngleCapabilities") {
            seat.checkSeatBackrestAngleCapabilities({ log("등받이 범위: ${it.mapKeys { (a, _) -> a.values.toList() }}") }, { log("등받이 범위 실패: ${err(it)}") })
        }
        seats.forEach { s ->
            val a = SeatArea(s)
            call("belt $s") { seat.getCurrentSeatBeltBuckledState(a, { log("좌석 $s 벨트=$it") }, { log("좌석 $s 벨트 실패: ${err(it)}") }) }
            call("foreaft $s") { seat.getCurrentSeatForeAftPosition(a, { log("좌석 $s 앞뒤=$it") }, { log("좌석 $s 앞뒤 실패: ${err(it)}") }) }
            call("height $s") { seat.getCurrentSeatHeightPosition(a, { log("좌석 $s 높이=$it") }, { log("좌석 $s 높이 실패: ${err(it)}") }) }
            call("backrest $s") { seat.getCurrentSeatBackrestAnglePosition(a, { log("좌석 $s 등받이=$it") }, { log("좌석 $s 등받이 실패: ${err(it)}") }) }
        }
        // 운전석을 앞으로 1초 움직였다 멈춘 뒤 위치가 바뀌는지 본다
        val driver = SeatArea(SeatValue.ROW_1_LEFT)
        call("moveSeatForeAft FORWARD") {
            seat.moveSeatForeAft(driver, MoveControl.FORWARD, { _, m -> log("운전석 이동 요청 완료: $m") }, { log("운전석 이동 실패: ${err(it)}") })
        }
        main.postDelayed({
            call("moveSeatForeAft STOP") { seat.moveSeatForeAft(driver, MoveControl.STOP, { _, m -> log("운전석 정지: $m") }, { log("운전석 정지 실패: ${err(it)}") }) }
            main.postDelayed({ seat.getCurrentSeatForeAftPosition(driver, { log("운전석 이동 후 앞뒤=$it") }, { log("이동 후 앞뒤 실패: ${err(it)}") }) }, 1000)
        }, 1500)
    }

    private fun readLight() = main.postDelayed({
        vehicle.getLight().getCurrentReadingLightState(SeatArea(SeatValue.ROW_2_LEFT),
            { log("독서등 ROW_2_LEFT 재조회=$it") }, { log("독서등 재조회 실패: ${err(it)}") })
    }, 1000)

    private fun readSeatTemp() = main.postDelayed({
        vehicle.getSeat().getHvacSeatTemperature(SeatArea(SeatValue.ROW_1_RIGHT),
            { log("시트 온열 ROW_1_RIGHT 재조회=$it") }, { log("시트 온열 재조회 실패: ${err(it)}") })
    }, 1000)

    private fun call(name: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { log("$name 동기 예외: ${err(e)}") }
    }

    private fun describe(t: Telltales): String {
        val active = t.telltales.filter { it.state.name != "OFF" }
        return "전체 ${t.telltales.size}개, 켜짐 ${active.size}개 " +
            active.joinToString(prefix = "[", postfix = "]") { "${it.type}:${it.state}" }
    }

    private fun err(e: Exception) = "${e.javaClass.simpleName}: ${e.message}"

    private fun log(msg: String) {
        Log.i(tag, msg)
        val ts = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        main.post { out.append("$ts  $msg\n") }
    }

    override fun onDestroy() {
        call("unregister") {
            vehicle.getSafety().unregisterTelltales(telltaleListener)
            seatListeners.forEach { (s, l) -> vehicle.getSeat().unregisterSeatOccupancy(SeatArea(s), l) }
            doorListeners.forEach { (d, l) -> vehicle.getDoor().unregisterDoorPosition(DoorArea(d), l) }
        }
        vehicle.release()
        super.onDestroy()
    }
}
