package com.hyundaiapptest.seatid

import android.content.Context
import android.graphics.Color

/** 가족 프로필과 좌석별 탑승 횟수를 기기에 저장한다. */
class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("seatid", Context.MODE_PRIVATE)

    fun loadProfiles(): List<Profile> {
        if (!prefs.getBoolean("seeded", false)) seed()
        return defaults()
    }

    /**
     * 이 차량 사용자 프로필의 주인(가족 프로필 id).
     * 앱 데이터는 차량 사용자 프로필마다 따로 저장되므로, 프로필마다 한 번만 정하면 된다.
     */
    var owner: String?
        get() = prefs.getString("owner", null)
        set(v) { prefs.edit().putString("owner", v).apply() }

    fun count(pos: SeatPos, id: String) = prefs.getInt("h_${pos.name}_$id", 0)
    fun inc(pos: SeatPos, id: String) = prefs.edit().putInt("h_${pos.name}_$id", count(pos, id) + 1).apply()

    /** 예시 데이터로 되돌린다(시연 초기화). */
    fun seed() {
        val keepOwner = owner // 프로필 주인은 시연 초기화와 무관하게 유지한다
        prefs.edit().clear()
            .putString("owner", keepOwner)
            .putBoolean("seeded", true)
            // 평소 탑승 기록: 조수석은 엄마, 뒷좌석 왼쪽은 서연, 오른쪽은 지민
            .putInt("h_FR_mom", 6)
            .putInt("h_RL_seoyeon", 5).putInt("h_RL_jimin", 1)
            .putInt("h_RR_jimin", 4).putInt("h_RR_seoyeon", 1)
            .apply()
    }

    private fun defaults() = listOf(
        Profile("dad", "아빠", "보호자", false, Color.parseColor("#3B82F6")),
        Profile("mom", "엄마", "보호자", false, Color.parseColor("#EC4899")),
        Profile("jimin", "지민", "아들 · 10살", true, Color.parseColor("#22C55E")),
        Profile("seoyeon", "서연", "딸 · 8살", true, Color.parseColor("#F59E0B")),
    )
}
