package com.hyundaiapptest.together

import android.content.Context
import android.graphics.Color
import android.os.Process
import android.os.UserManager

/** 가족 프로필, 좌석별 탑승 기록, 가족 설정을 차 안(앱 데이터)에만 저장한다. */
class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("together", Context.MODE_PRIVATE)

    init { if (!prefs.getBoolean("seeded", false)) seed() }

    val profiles = listOf(
        Profile("dad", "아빠", "보호자", false, Color.parseColor("#3B82F6")),
        Profile("mom", "엄마", "보호자", false, Color.parseColor("#EC4899")),
        Profile("jimin", "지민", "딸 · 11살", true, Color.parseColor("#F59E0B")),
        Profile("junho", "준호", "아들 · 8살", true, Color.parseColor("#22C55E")),
    )

    /**
     * 이 차량 사용자 프로필의 주인(가족 프로필 id).
     * 앱 데이터는 차량 사용자 프로필마다 따로 저장되므로, 프로필마다 한 번만 정하면 된다.
     */
    var owner: String?
        get() = prefs.getString("owner", null)
        set(v) { prefs.edit().putString("owner", v).apply() }

    /** 공유 설비 우선순위. "safety" = 운전석→조수석→뒷자리, "kids" = 뒷자리→조수석→운전석 */
    var priority: String
        get() = prefs.getString("priority", "safety")!!
        set(v) { prefs.edit().putString("priority", v).apply() }

    /** 운전 집중 모드를 켜는 연속 운전 시간(분). 가족 설정에서 바꿀 수 있다. */
    var focusAfterMin: Int
        get() = prefs.getInt("focusAfterMin", 60)
        set(v) { prefs.edit().putInt("focusAfterMin", v).apply() }

    fun count(pos: SeatPos, id: String) = prefs.getInt("h_${pos.name}_$id", 0)
    fun inc(pos: SeatPos, id: String) = prefs.edit().putInt("h_${pos.name}_$id", count(pos, id) + 1).apply()

    /** 자리별 모드 사용 기록(다음 제안에 쓴다) */
    fun modeCount(id: String, m: Mode) = prefs.getInt("m_${id}_${m.name}", 0)
    fun incMode(id: String, m: Mode) = prefs.edit().putInt("m_${id}_${m.name}", modeCount(id, m) + 1).apply()

    /** 예시 데이터로 되돌린다(시연 초기화). 프로필 주인은 유지한다. */
    fun seed() {
        val keepOwner = owner
        prefs.edit().clear()
            .putString("owner", keepOwner)
            .putBoolean("seeded", true)
            .putInt("focusAfterMin", 60)
            // 지난 주말들의 탑승 기록: 조수석 엄마, 뒷자리 왼쪽 지민, 오른쪽 준호
            .putInt("h_FR_mom", 6)
            .putInt("h_RL_jimin", 5).putInt("h_RL_junho", 1)
            .putInt("h_RR_junho", 4).putInt("h_RR_jimin", 1)
            .putInt("m_jimin_READ", 3).putInt("m_junho_VIDEO", 4).putInt("m_mom_SLEEP", 2)
            .apply()
    }
}

/** 지금 차 화면에 로그인된 사용자 프로필 = 운전자. */
data class DriverProfile(val userId: Int, val name: String?) {
    val label get() = name?.let { "'$it'" } ?: "사용자 $userId"

    companion object {
        fun read(ctx: Context): DriverProfile {
            val id = Process.myUserHandle().hashCode() // UserHandle.hashCode()는 사용자 id다
            // 프로필 이름은 시스템 앱만 읽을 수 있다(일반 앱은 SecurityException). 읽히면 표시용으로만 쓴다.
            val name = try { ctx.getSystemService(UserManager::class.java).userName } catch (e: SecurityException) { null }
            return DriverProfile(id, name)
        }
    }
}
