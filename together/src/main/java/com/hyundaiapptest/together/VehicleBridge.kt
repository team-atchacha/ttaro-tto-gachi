package com.hyundaiapptest.together

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import android.content.Context
import android.os.Handler

/**
 * Pleos Vehicle SDK 연결.
 *  - 입력: 좌석 점유·벨트·문·경고등·기어 구독, 주행 제한 상태(CarUxRestrictionsManager)
 *  - 출력: Engine이 정한 목표값을 설비에 보내고, 다시 읽어 반영됐는지 확인한다(실패하면 재시도하지 않고 화면에 알림).
 *  - 안전: SDK가 알려 준 지원 범위 밖의 값은 보내지 않는다.
 * 콜백은 모두 메인 스레드로 넘긴다.
 */
class VehicleBridge(private val ctx: Context, private val engine: Engine, private val main: Handler) {

    private val vehicle = Vehicle(ctx)
    private val unregister = mutableListOf<() -> Unit>()
    private var ready = false
    private val areas = mutableMapOf<String, SeatArea>()
    private val lastLog = mutableMapOf<String, Long>()

    fun start() {
        try {
            vehicle.initialize()
        } catch (e: Exception) {
            engine.log("Vehicle SDK 초기화 실패: ${e.message}"); return
        }
        engine.actuator = { sync() }
        // 서비스 바인딩을 기다린 뒤 구독한다
        main.postDelayed({ subscribe(); readInitial(); loadCapabilities(); startUxRestrictions() }, 2500)
        main.postDelayed(object : Runnable { override fun run() { readDevices(); main.postDelayed(this, 2000) } }, 5000)
    }

    fun stop() {
        engine.actuator = null
        unregister.forEach { runCatching { it() } }
        runCatching { vehicle.release() }
        runCatching { car?.disconnect() }
    }

    /**
     * 에뮬레이터 v2.1.0에서는 좌석 점유 값이 반대로 들어온다(Console에서 Occupied → SDK false, 4좌석 재현).
     * 실차나 고쳐진 에뮬레이터에서는 false로 바꾼다. 점유 판단은 이 한 곳에서만 뒤집는다.
     */
    private val invertOccupancy = true
    private fun occ(v: Boolean) = if (invertOccupancy) !v else v

    private fun onMain(block: () -> Unit) = main.post(block)

    // ── 입력 ────────────────────────────────────────

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
        safe("기어 구독") {
            val gear = vehicle.getGear()
            val l = object : GearSelectionListener {
                override fun onSelected(p0: VehicleGear?) { p0?.let { onMain { engine.onGear(gearName(it)) } } }
                override fun onFailed(p0: Exception) = fail("기어", p0)
            }
            gear.registerGearSelection(l); unregister += { gear.unregisterGearSelection(l) }
        }
        onMain { engine.log("Vehicle SDK 구독 시작 (좌석 점유·벨트·문·경고등·기어)") }
    }

    private fun readInitial() {
        val seat = vehicle.getSeat()
        val door = vehicle.getDoor()
        SeatPos.values().forEach { p ->
            safe("점유 조회") { seat.getCurrentSeatOccupancy(SeatArea(p.sdkSeat), { v -> v?.let { onMain { engine.onOccupancy(p, occ(it)) } } }, { fail("점유 조회 ${p.label}", it) }) }
            safe("도어 조회") { door.getCurrentDoorPosition(DoorArea(p.sdkDoor), { v -> v?.let { onMain { engine.onDoor(p, it > 0) } } }, { fail("도어 조회 ${p.label}", it) }) }
        }
        safe("경고등 조회") { vehicle.getSafety().getTelltalesInfo({ t -> t?.let { onMain { applyTelltales(it) } } }, { fail("경고등 조회", it) }) }
        safe("기어 조회") { vehicle.getGear().getCurrentGearSelectionState({ g -> g?.let { onMain { engine.onGear(gearName(it)) } } }, { fail("기어 조회", it) }) }
    }

    private fun gearName(g: VehicleGear) = when (g) {
        VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_REVERSE -> "R"; VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_UNKNOWN -> "?"; else -> "D"
    }

    private fun applyTelltales(t: Telltales) {
        val on = t.telltales.any { it.type == TelltaleType.PASSENGER_AIRBAG_OFF && it.state != TelltaleState.OFF }
        engine.onAirbagOff(on)
    }

    // 주행 제한(Android Car API). 기어와 함께 "주행 중"을 판단한다.
    private var car: android.car.Car? = null
    private fun startUxRestrictions() {
        try {
            val c = android.car.Car.createCar(ctx) ?: return
            car = c
            val m = c.getCarManager(android.car.Car.CAR_UX_RESTRICTION_SERVICE) as android.car.drivingstate.CarUxRestrictionsManager
            m.registerListener { r -> onMain { engine.onUxRestricted(r.isRequiresDistractionOptimization) } }
            m.currentCarUxRestrictions?.let { r -> engine.onUxRestricted(r.isRequiresDistractionOptimization) }
            unregister += { m.unregisterListener() }
        } catch (t: Throwable) {
            engine.log("주행 제한 상태를 읽지 못함 (${t.javaClass.simpleName}) → 기어로만 판단")
        }
    }

    // ── 설비: 지원 범위 ─────────────────────────────

    private val all5 = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)

    private fun dev(kind: DevKind, seat: SeatPos? = null) = engine.devices.getValue(DevKey(kind, seat).id)

    private fun area(d: Device): SeatArea = areas[d.key.id] ?: when (d.key.kind) {
        DevKind.AMBIENT_ON, DevKind.AMBIENT_LEVEL, DevKind.AMBIENT_COLOR, DevKind.RECIRC -> all5
        else -> SeatArea(d.key.seat!!.sdkSeat)
    }

    /** 영역 목록에서 이 자리를 포함하는 영역을 찾는다 */
    private fun pick(list: Collection<SeatArea>, s: SeatValue) = list.firstOrNull { s in it.values }

    private fun loadCapabilities() {
        val light = vehicle.getLight(); val seat = vehicle.getSeat(); val hvac = vehicle.getHVAC()
        safe("범위: 시트 온열") {
            seat.checkSeatTemperatureCapabilities({ m: Map<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>> ->
                onMain { SeatPos.values().forEach { p -> pick(m.keys, p.sdkSeat)?.let { a -> dev(DevKind.HEAT, p).apply { min = m[a]!!.minValue.toFloat(); max = m[a]!!.maxValue.toFloat() }; areas[DevKey(DevKind.HEAT, p).id] = a } ?: run { dev(DevKind.HEAT, p).supported = false } } }
            }, { fail("시트 온열 범위", it) })
        }
        safe("범위: 시트 통풍") {
            seat.checkSeatVentilationCapabilities({ m: Map<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>> ->
                onMain { SeatPos.values().filter { it.front }.forEach { p -> pick(m.keys, p.sdkSeat)?.let { a -> dev(DevKind.VENT, p).apply { min = m[a]!!.minValue.toFloat(); max = m[a]!!.maxValue.toFloat() }; areas[DevKey(DevKind.VENT, p).id] = a } ?: run { dev(DevKind.VENT, p).supported = false } } }
            }, { fail("통풍 범위", it) })
        }
        safe("범위: 설정 온도") {
            hvac.checkHvacTemperatureCapabilities({ m: Map<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Float>> ->
                onMain { SeatPos.values().filter { it.front }.forEach { p -> pick(m.keys, p.sdkSeat)?.let { a -> dev(DevKind.TEMP, p).apply { min = m[a]!!.minValue; max = m[a]!!.maxValue }; areas[DevKey(DevKind.TEMP, p).id] = a } } }
            }, { fail("온도 범위", it) })
        }
        safe("범위: 독서등") {
            light.checkReadingLightSwitchCapabilities({ m: Map<SeatArea, List<LightSwitch>> ->
                onMain { SeatPos.values().forEach { p -> pick(m.keys, p.sdkSeat)?.let { a -> areas[DevKey(DevKind.READING, p).id] = a } ?: run { dev(DevKind.READING, p).supported = false } } }
            }, { fail("독서등 범위", it) })
        }
        safe("범위: 앰비언트") {
            light.checkAmbientLightBrightnessCapabilities({ _, m ->
                onMain {
                    m.entries.firstOrNull()?.let { (a, b) ->
                        listOf(DevKind.AMBIENT_ON, DevKind.AMBIENT_LEVEL, DevKind.AMBIENT_COLOR).forEach { k -> areas[DevKey(k).id] = a }
                        dev(DevKind.AMBIENT_LEVEL).apply { min = b.minValue.toFloat(); max = b.maxValue.toFloat() }
                        engine.log("앰비언트 조명: 영역 ${a.values.size}자리를 묶은 하나 → 공유 설비로 조율")
                    }
                }
            }, { fail("앰비언트 범위", it) })
        }
        safe("범위: 내외기") {
            hvac.checkHvacRecirculationCapabilities({ l: List<SeatArea> ->
                onMain { l.firstOrNull()?.let { a -> areas[DevKey(DevKind.RECIRC).id] = a; engine.log("내·외기: 영역 ${a.values.size}자리를 묶은 하나 → 공유 설비로 조율") } }
            }, { fail("내외기 범위", it) })
        }
        main.postDelayed({ ready = true; readDevices() }, 1500)
    }

    // ── 설비: 읽기 ──────────────────────────────────

    private fun readDevices() {
        if (!ready) return
        engine.devices.values.filter { it.supported && it.state != CmdState.PENDING }.forEach { d -> read(d) { v -> onRead(d, v) } }
    }

    private fun onRead(d: Device, v: Any) {
        val changed = !Cabin.same(d.actual, v)
        d.actual = v
        if (d.baseline == null) d.baseline = v
        // 앱이 붙잡고 있지 않은 설비는 사람이 바꾼 값을 새 원래값으로 삼는다
        if (d.target == null) {
            if (!d.touched) d.baseline = v
            else if (Cabin.same(v, d.baseline)) { d.touched = false; d.state = CmdState.IDLE; d.lastSent = null }
        }
        if (changed) engine.onDeviceRead()
    }

    private fun read(d: Device, ok: (Any) -> Unit) {
        val a = area(d)
        val light = vehicle.getLight(); val seat = vehicle.getSeat(); val hvac = vehicle.getHVAC()
        val err: (Exception) -> Unit = { e -> onMain { if (d.actual == null && d.baseline == null) { d.supported = false; d.message = "읽기 실패: ${e.message}" } } }
        safe("읽기 ${d.key.label}") {
            when (d.key.kind) {
                DevKind.READING -> light.getCurrentReadingLightState(a, { v -> onMain { ok(v == LightState.ON) } }, err)
                DevKind.HEAT -> seat.getHvacSeatTemperature(a, { v -> v?.let { onMain { ok(it) } } }, err)
                DevKind.VENT -> seat.getHvacSeatVentilationState(a, { v -> v?.let { onMain { ok(it) } } }, err)
                DevKind.TEMP -> hvac.getHvacTemperature(a, TemperatureUnit.CELSIUS, { v -> v?.let { onMain { ok(it) } } }, err)
                // 앰비언트는 실제 상태(getAmbientLightState)가 스위치 설정을 따라가지 않아 스위치 값을 읽는다
                DevKind.AMBIENT_ON -> light.getAmbientLightSwitch(a, { v -> onMain { ok(v == LightSwitch.ON) } }, err)
                DevKind.AMBIENT_LEVEL -> light.getAmbientLightBrightness(a, { v -> v?.let { onMain { ok(it) } } }, err)
                DevKind.AMBIENT_COLOR -> light.getAmbientLightBrightnessColor(a, { v -> v?.let { onMain { ok(it and 0xFFFFFF) } } }, err)
                DevKind.RECIRC -> hvac.getHvacRecirculationState(a, { v -> v?.let { onMain { ok(it) } } }, err)
            }
        }
    }

    // ── 설비: 쓰기(Engine이 상태가 바뀔 때마다 부른다) ─────────

    /**
     * 같은 묶음(공조·시트 / 조명)의 명령은 한 번에 하나씩 보낸다.
     * 에뮬레이터에서 공조 값 여러 개를 동시에 쓰면 서로 덮어써 반영되지 않는 경우가 있었다.
     */
    private fun lane(k: DevKind) = when (k) { DevKind.READING, DevKind.AMBIENT_ON, DevKind.AMBIENT_LEVEL, DevKind.AMBIENT_COLOR -> "light"; else -> "climate" }

    private fun sync() {
        if (!ready) return
        val busy = engine.devices.values.filter { it.state == CmdState.PENDING }.map { lane(it.key.kind) }.toMutableSet()
        engine.devices.values.forEach { d ->
            if (!d.supported || d.state == CmdState.PENDING || d.actual == null) return@forEach
            if (lane(d.key.kind) in busy) return@forEach
            if (d.state == CmdState.DEFERRED && engine.driving) return@forEach
            val want = d.target?.value ?: (if (d.touched) d.baseline else null) ?: return@forEach
            if (Cabin.same(want, d.actual)) {
                if (d.touched && d.state != CmdState.VERIFIED && Cabin.same(want, d.lastSent)) d.state = CmdState.VERIFIED
                if (d.state == CmdState.FAILED || d.state == CmdState.BLOCKED || d.state == CmdState.DEFERRED) { d.state = CmdState.IDLE; d.message = "" }
                d.lastSent = want // 목표가 현재값과 같아졌으니, 다음에 다른 값이 오면 다시 보낸다
                return@forEach
            }
            if (Cabin.same(want, d.lastSent)) return@forEach // 같은 값은 다시 보내지 않는다(실패 시 재시도 없음)
            // 조명을 끌 때는 밝기·색을 먼저 맞춘다(꺼진 뒤에는 색 변경이 반영되지 않는 경우가 있었다)
            if (d.key.kind == DevKind.AMBIENT_ON && want == false && pendingAmbientDetail()) return@forEach
            // 지원 범위 밖이면 보내지 않는다
            if (want is Number) {
                val f = want.toFloat()
                if ((d.min != null && f < d.min!!) || (d.max != null && f > d.max!!)) {
                    d.state = CmdState.BLOCKED; d.lastSent = want
                    d.message = "지원 범위(${d.min}~${d.max}) 밖이라 보내지 않음"
                    engine.log("차단: ${d.key.label} ${Cabin.fmt(d.key.kind, want)} — ${d.message}")
                    return@forEach
                }
            }
            busy += lane(d.key.kind)
            send(d, want)
        }
    }

    private fun pendingAmbientDetail() = listOf(DevKind.AMBIENT_LEVEL, DevKind.AMBIENT_COLOR).any { k ->
        val x = dev(k)
        val w = x.target?.value ?: (if (x.touched) x.baseline else null)
        x.supported && w != null && !Cabin.same(w, x.actual) && x.state != CmdState.FAILED
    }

    private fun send(d: Device, want: Any) {
        if (d.lastSent != null && !Cabin.same(want, d.lastSent)) d.retries = 0
        d.state = CmdState.PENDING; d.lastSent = want; d.touched = true; d.message = ""
        val restoring = d.target == null
        val why = d.target?.reason
        val a = area(d)
        val light = vehicle.getLight(); val seat = vehicle.getSeat(); val hvac = vehicle.getHVAC()
        val done: () -> Unit = { onMain { main.postDelayed({ verify(d, want, restoring, why) }, 600) } }
        val err: (Exception) -> Unit = { e ->
            onMain {
                // 에뮬레이터에서 주행 중(D)에는 독서등 제어가 50001로 거부된다(P에서는 됨). 정차하면 다시 보낸다.
                if (engine.driving && d.key.kind == DevKind.READING && e.message?.contains("50001") == true) {
                    d.state = CmdState.DEFERRED; d.lastSent = null
                    d.message = "주행 중에는 독서등을 바꿀 수 없어 정차하면 반영"
                    engine.log("보류: ${d.key.label} ${Cabin.fmt(d.key.kind, want)} — ${d.message} (SDK 50001)")
                    engine.onDeviceRead()
                    return@onMain
                }
                d.state = CmdState.FAILED; d.message = e.message ?: e.javaClass.simpleName
                engine.log("실패: ${d.key.label} ${Cabin.fmt(d.key.kind, want)} — ${d.message} (재시도하지 않음)")
                engine.onDeviceRead()
            }
        }
        try {
            when (d.key.kind) {
                DevKind.READING -> light.setReadingLightSwitch(a, if (want == true) LightSwitch.ON else LightSwitch.OFF, { done() }, err)
                DevKind.HEAT -> seat.setHvacSeatTemperature(a, (want as Number).toInt(), { _, _ -> done() }, err)
                DevKind.VENT -> seat.setHvacSeatVentilation(a, (want as Number).toInt(), { _, _ -> done() }, err)
                DevKind.TEMP -> hvac.setHvacTemperature(a, (want as Number).toFloat(), TemperatureUnit.CELSIUS, { _, _ -> done() }, err)
                DevKind.AMBIENT_ON -> light.setAmbientLightSwitch(a, if (want == true) LightSwitch.ON else LightSwitch.OFF, { _, _ -> done() }, err)
                DevKind.AMBIENT_LEVEL -> light.setAmbientLightBrightness(a, (want as Number).toInt(), { _, _ -> done() }, err)
                DevKind.AMBIENT_COLOR -> light.setAmbientLightBrightnessColor(a, (want as Number).toInt(), { _, _ -> done() }, err)
                DevKind.RECIRC -> hvac.setHvacRecirculation(a, want as Boolean, { _, _ -> done() }, err)
            }
        } catch (e: Exception) { err(e) }
    }

    /** 실행 후 확인: 다시 읽어 바뀐 값이 나오는지 본다 */
    /** 실제 상태가 늦게 따라오는 설비(독서등 등)가 있어 최대 세 번(약 3초) 다시 읽는다 */
    private fun verify(d: Device, want: Any, restoring: Boolean, why: String?, attempt: Int = 0) {
        read(d) { v ->
            d.actual = v
            if (!Cabin.same(v, want) && attempt < 2) {
                main.postDelayed({ verify(d, want, restoring, why, attempt + 1) }, 1200)
                return@read
            }
            if (Cabin.same(v, want)) {
                d.state = CmdState.VERIFIED
                if (restoring) { d.touched = false }
                val now = System.currentTimeMillis()
                val quiet = d.key.kind == DevKind.AMBIENT_LEVEL && now - (lastLog[d.key.id] ?: 0) < 6000
                if (!quiet) {
                    lastLog[d.key.id] = now
                    engine.log("✓ ${d.key.label} ${Cabin.fmt(d.key.kind, want)}" + (if (restoring) " (원래값으로)" else why?.let { " — $it" } ?: "") + " · 다시 읽어 확인")
                }
            } else if (d.retries < 1) {
                // 한 번만 다시 보낸다(공조 값이 동시에 바뀌며 덮어써진 경우)
                d.retries++
                d.state = CmdState.IDLE; d.lastSent = null
                d.message = "다시 읽은 값이 ${Cabin.fmt(d.key.kind, v)} → 한 번 더 보냄"
            } else {
                d.state = CmdState.FAILED
                d.message = "다시 읽은 값이 ${Cabin.fmt(d.key.kind, v)}"
                engine.log("실패: ${d.key.label} ${Cabin.fmt(d.key.kind, want)} 요청했지만 ${d.message} (재시도하지 않음)")
            }
            engine.onDeviceRead()
        }
    }

    // ── 점검용: 앱 판단을 거치지 않고 SDK로 바로 쓰고 다시 읽는다(/api/sdk) ──

    /**
     * kind 설비에 value를 SDK로 보내고, 보내기 전 값·SDK 응답·0.6/1.8/3초 뒤 다시 읽은 값을 돌려준다.
     * 앱이 붙잡고 있지 않은 설비(target 없음)에 쓰면 Engine은 사람이 바꾼 값으로 보고 되돌리지 않는다.
     */
    fun directTest(kind: DevKind, seat: SeatPos?, value: String, cb: (org.json.JSONObject) -> Unit) {
        val d = dev(kind, seat)
        val want: Any = when (kind) {
            DevKind.READING, DevKind.AMBIENT_ON, DevKind.RECIRC -> value.toBoolean()
            DevKind.TEMP -> value.toFloat()
            DevKind.AMBIENT_COLOR -> value.removePrefix("#").toInt(16)
            else -> value.toInt()
        }
        val out = org.json.JSONObject().put("device", d.key.label).put("gear", engine.gear).put("uxRestricted", engine.uxRestricted ?: org.json.JSONObject.NULL).put("driving", engine.driving).put("want", Cabin.fmt(kind, want))
        val a = area(d)
        read(d) { before ->
            out.put("before", Cabin.fmt(kind, before))
            val reads = org.json.JSONArray()
            val readAt = { delays: List<Long> ->
                fun next(i: Int) {
                    if (i >= delays.size) { out.put("reads", reads).put("ok", reads.length() > 0 && reads.getJSONObject(reads.length() - 1).getBoolean("match")); cb(out); return }
                    main.postDelayed({ read(d) { v -> reads.put(org.json.JSONObject().put("ms", delays.take(i + 1).sum()).put("value", Cabin.fmt(kind, v)).put("match", Cabin.same(v, want))); next(i + 1) } }, delays[i])
                }
                next(0)
            }
            val okCb: () -> Unit = { onMain { out.put("sdk", "성공 콜백"); readAt(listOf(600L, 1200L, 1200L)) } }
            val err: (Exception) -> Unit = { e -> onMain { out.put("sdk", "실패: ${e.message}").put("ok", false); readAt(listOf(600L)) } }
            val light = vehicle.getLight(); val st = vehicle.getSeat(); val hvac = vehicle.getHVAC()
            try {
                when (kind) {
                    DevKind.READING -> light.setReadingLightSwitch(a, if (want == true) LightSwitch.ON else LightSwitch.OFF, { okCb() }, err)
                    DevKind.HEAT -> st.setHvacSeatTemperature(a, (want as Number).toInt(), { _, _ -> okCb() }, err)
                    DevKind.VENT -> st.setHvacSeatVentilation(a, (want as Number).toInt(), { _, _ -> okCb() }, err)
                    DevKind.TEMP -> hvac.setHvacTemperature(a, (want as Number).toFloat(), TemperatureUnit.CELSIUS, { _, _ -> okCb() }, err)
                    DevKind.AMBIENT_ON -> light.setAmbientLightSwitch(a, if (want == true) LightSwitch.ON else LightSwitch.OFF, { _, _ -> okCb() }, err)
                    DevKind.AMBIENT_LEVEL -> light.setAmbientLightBrightness(a, (want as Number).toInt(), { _, _ -> okCb() }, err)
                    DevKind.AMBIENT_COLOR -> light.setAmbientLightBrightnessColor(a, (want as Number).toInt(), { _, _ -> okCb() }, err)
                    DevKind.RECIRC -> hvac.setHvacRecirculation(a, want as Boolean, { _, _ -> okCb() }, err)
                }
            } catch (e: Exception) { err(e) }
        }
    }

    private fun safe(name: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { onMain { engine.log("$name 예외: ${e.message}") } }
    }

    private fun fail(name: String, e: Exception) {
        android.util.Log.w("TOGETHER", "$name 실패: ${e.message}")
    }
}
