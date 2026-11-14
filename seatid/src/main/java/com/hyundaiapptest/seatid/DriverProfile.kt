package com.hyundaiapptest.seatid

import android.content.Context
import android.os.Process
import android.os.UserManager

/**
 * 지금 차 화면에 로그인된 사용자 프로필 = 운전자.
 *
 * 차량용 안드로이드는 운전자마다 사용자 프로필을 따로 두고, 차 화면 상단의 프로필 아이콘에서 바꾼다.
 * 앱은 그 프로필 안에서 실행되고 앱 데이터도 프로필마다 따로 저장된다.
 * 그래서 프로필마다 "이 프로필의 주인"을 한 번 기억해 두면, 로그인된 프로필로 운전자를 알 수 있다.
 */
data class DriverProfile(val userId: Int, val name: String?) {
    companion object {
        fun read(ctx: Context): DriverProfile {
            val id = Process.myUserHandle().hashCode() // UserHandle.hashCode()는 사용자 id다
            // 프로필 이름은 시스템 앱만 읽을 수 있다(일반 앱은 SecurityException). 읽히면 표시용으로만 쓴다.
            val name = try {
                ctx.getSystemService(UserManager::class.java).userName
            } catch (e: SecurityException) {
                null
            }
            return DriverProfile(id, name)
        }
    }
}
