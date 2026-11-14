package com.hyundaiapptest.together

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler

/**
 * NaviHelper SDK: 경로 안내를 시작하고 남은 시간을 받는다.
 * 남은 시간은 도착 15분 전 깨우기와 도착 판단에 쓴다.
 */
class NaviBridge(ctx: Context, private val engine: Engine, private val main: Handler) {

    private val navi = NaviHelper(ctx)
    private var ok = false
    private var waitingStart: Runnable? = null
    private var destName = ""
    private var startedAt = 0L
    /** 경로가 잡히면 내비 화면이 앞으로 온다. 시연에서는 앱 화면으로 다시 돌아온다. */
    var onStarted: () -> Unit = {}

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(p0: RouteStartInfo) {
            main.post {
                waitingStart?.let { main.removeCallbacks(it) }; waitingStart = null
                startedAt = System.currentTimeMillis()
                engine.onRouteStarted(destName.ifEmpty { p0.name }, p0.duration, p0.distance, "NaviHelper")
                onStarted()
                navi.getDestinationInfo()
            }
        }
        override fun onDestinationInfo(p0: DestinationInfo) { main.post { engine.onDestinationInfo(p0.remainTime, p0.remainDistance) } }
        override fun onRouteCancelled() { main.post { engine.onRouteEnded("내비에서 취소") } }
        // 경로를 새로 잡으면 이전 안내가 끝났다는 이벤트가 바로 뒤따라 온다. 시작 직후의 종료는 무시한다.
        override fun onRouteEnded() { main.post { if (System.currentTimeMillis() - startedAt > 15000) engine.onRouteEnded("내비 안내 끝") } }
        override fun onDestinationArrived(p0: DestinationArrivedInfo) { main.post { engine.onRouteEnded("도착 이벤트") } }
        override fun onError(p0: NaviErrorCode) { main.post { engine.log("내비 오류: $p0") } }
    }

    fun start() {
        try {
            navi.initialize({ main.post { ok = true; navi.addListener(listener); engine.log("NaviHelper 연결") } },
                { t -> main.post { engine.log("NaviHelper 연결 실패: ${t.message} → 시연용 경로로 대체") } })
        } catch (t: Throwable) {
            engine.log("NaviHelper 초기화 예외: ${t.message}")
        }
        // 안내 중에는 남은 시간을 주기적으로 받는다
        main.postDelayed(object : Runnable {
            override fun run() {
                if (ok && engine.route?.source == "NaviHelper") runCatching { navi.getDestinationInfo() }
                main.postDelayed(this, 5000)
            }
        }, 5000)
    }

    fun stop() { runCatching { navi.removeListener(listener); navi.release() } }

    /** 집으로 경로 안내. 내비가 응답하지 않으면 시연용 경로(90분)로 대체한다. */
    fun routeHome() {
        destName = HOME_NAME
        val fallback = Runnable { waitingStart = null; if (engine.route == null) engine.startSimRoute() }
        waitingStart = fallback
        main.postDelayed(fallback, 20000)
        if (!ok) { main.removeCallbacks(fallback); fallback.run(); return }
        try {
            navi.requestRoute(RouteInfo(HOME_LAT, HOME_LON, null, null, HOME_NAME, "", HOME_ADDR, "", RouteOption.RECOMMENDED, "", false))
            engine.log("경로 요청: $HOME_NAME (NaviHelper requestRoute)")
        } catch (t: Throwable) {
            engine.log("경로 요청 실패: ${t.message}")
        }
    }

    fun cancel() {
        if (ok && engine.route?.source == "NaviHelper") runCatching { navi.cancelRoute() }
        engine.onRouteEnded("안내 종료")
    }

    companion object {
        const val HOME_NAME = "집"
        const val HOME_ADDR = "강원특별자치도 춘천시 중앙로 1"
        const val HOME_LAT = 37.8813
        const val HOME_LON = 127.7298
    }
}
