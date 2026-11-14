package com.hyundaiapptest.seatid

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import android.content.Context
import android.os.Handler

/**
 * Pleos Vehicle SDK 구독을 Engine 입력으로 바꾼다. 콜백은 모두 메인 스레드로 넘긴다.
 */
class VehicleBridge(ctx: Context, private val engine: Engine, private val main: Handler) {

    private val vehicle = Vehicle(ctx)
    private val unregister = mutableListOf<() -> Unit>()

    fun start() {
        try {
            vehicle.initialize()
        } catch (e: Exception) {
            engine.log("Vehicle SDK 초기화 실패: ${e.message}"); return
        }
        // 서비스 바인딩을 기다린 뒤 구독한다
        main.postDelayed({ subscribe(); readInitial() }, 2500)
    }

    fun stop() {
        unregister.forEach { runCatching { it() } }
        runCatching { vehicle.release() }
    }

    /**
     * 에뮬레이터 v2.1.0에서는 좌석 점유 값이 반대로 들어온다(Console에서 Occupied → SDK false, 4좌석 재현).
     * 실차나 고쳐진 에뮬레이터에서는 false로 바꾼다.
     */
    private val invertOccupancy = true
    private fun occ(v: Boolean) = if (invertOccupancy) !v else v

    private fun onMain(block: () -> Unit) = main.post(block)

    private fun subscribe() {
        val seat = vehicle.getSeat()
        val door = vehicle.getDoor()
        val safety = vehicle.getSafety()
        SeatPos.values().forEach { p ->
            val area = SeatArea(p.sdkSeat)
            safe("점유 구독 ${p.label}") {
                val l = object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(p0: Boolean?) { p0?.let { onMain { engine.onOccupancy(p, occ(it)) } } }
                    override fun onFailed(p0: Exception) = fail("점유 ${p.label}", p0)
                }
                seat.registerSeatOccupancy(area, l); unregister += { seat.unregisterSeatOccupancy(area, l) }
            }
            safe("벨트 구독 ${p.label}") {
                val l = object : SeatBeltBuckledStateListener {
                    override fun onSeatBeltBuckledStateUpdated(p0: Boolean?) { p0?.let { onMain { engine.onSdkBelt(p, it) } } }
                    override fun onFailed(p0: Exception) = fail("벨트 ${p.label}", p0)
                }
                seat.registerSeatBeltBuckledState(area, l); unregister += { seat.unregisterSeatBeltBuckledState(area, l) }
            }
            val darea = DoorArea(p.sdkDoor)
            safe("도어 구독 ${p.label}") {
                val l = object : DoorPositionListener {
                    override fun onPositionUpdated(p0: Int?) { p0?.let { onMain { engine.onDoor(p, it > 0) } } }
                    override fun onFailed(p0: Exception) = fail("도어 ${p.label}", p0)
                }
                door.registerDoorPosition(darea, l); unregister += { door.unregisterDoorPosition(darea, l) }
            }
        }
        safe("경고등 구독") {
            val l = object : TelltaleInfoListener {
                override fun onTelltaleInfoUpdated(p0: Telltales?) { p0?.let { t -> onMain { applyTelltales(t) } } }
                override fun onFailed(p0: Exception) = fail("경고등", p0)
            }
            safety.registerTelltales(l); unregister += { safety.unregisterTelltales(l) }
        }
        onMain { engine.log("Vehicle SDK 구독 시작 (좌석 점유·벨트·도어·경고등)") }
    }

    private fun readInitial() {
        val seat = vehicle.getSeat()
        val door = vehicle.getDoor()
        SeatPos.values().forEach { p ->
            safe("점유 조회") { seat.getCurrentSeatOccupancy(SeatArea(p.sdkSeat), { v -> v?.let { onMain { engine.onOccupancy(p, occ(it)) } } }, { fail("점유 조회 ${p.label}", it) }) }
            safe("도어 조회") { door.getCurrentDoorPosition(DoorArea(p.sdkDoor), { v -> v?.let { onMain { engine.onDoor(p, it > 0) } } }, { fail("도어 조회 ${p.label}", it) }) }
        }
        safe("경고등 조회") { vehicle.getSafety().getTelltalesInfo({ t -> t?.let { onMain { applyTelltales(it) } } }, { fail("경고등 조회", it) }) }
    }

    private fun applyTelltales(t: Telltales) {
        val on = t.telltales.any { it.type == TelltaleType.PASSENGER_AIRBAG_OFF && it.state != TelltaleState.OFF }
        engine.onAirbagOff(on)
    }

    private fun safe(name: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { onMain { engine.log("$name 예외: ${e.message}") } }
    }

    private fun fail(name: String, e: Exception) {
        // 에뮬레이터가 지원하지 않는 속성은 실패 콜백이 온다. 시연에는 영향이 없으므로 로그만 남긴다.
        android.util.Log.w("SEATID", "$name 실패: ${e.message}")
    }
}
