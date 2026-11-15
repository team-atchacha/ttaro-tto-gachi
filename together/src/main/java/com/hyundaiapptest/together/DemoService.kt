package com.hyundaiapptest.together

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 판단 엔진·SDK 연결·웹 조작판을 품는 포그라운드 서비스.
 * 내비가 앞에 떠서 화면이 뒤로 가도 탑승자 판단과 설비 조율은 계속돼야 하기 때문이다.
 */
class DemoService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("together", "따로 또 같이", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "together")
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle("따로 또 같이")
            .setContentText("탑승자에 맞춰 자리별 설비를 조율하는 중")
            .setContentIntent(open)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Hub.start(this)
        registerReceiver(userSwitch, IntentFilter(Intent.ACTION_USER_BACKGROUND))
    }

    // 차 화면에서 사용자 프로필을 바꾸면 이 사용자는 뒤로 물러난다. 조작판 포트를 새 사용자 앱에 넘기고 끝낸다.
    private val userSwitch = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { stopSelf() }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(userSwitch) }
        Hub.stop()
        super.onDestroy()
    }
}

/** 프로세스 하나에 하나만 있는 데모 상태. 서비스가 만들고 화면은 구경만 한다. */
object Hub {
    val main = Handler(Looper.getMainLooper())
    var engine: Engine? = null
        private set
    private var bridge: VehicleBridge? = null
    private var navi: NaviBridge? = null
    private var console: ConsoleClient? = null
    private var server: PanelServer? = null
    private val listeners = mutableSetOf<() -> Unit>()
    private var renderQueued = false
    private val ticker = object : Runnable {
        override fun run() { engine?.tick(); main.postDelayed(this, 1000) }
    }

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }

    private fun notifyChange() {
        if (renderQueued) return
        renderQueued = true
        main.post { renderQueued = false; listeners.toList().forEach { it() } }
    }

    fun start(ctx: Context) {
        if (engine != null) return
        val e = Engine(Store(ctx), DriverProfile.read(ctx)) { notifyChange() }
        engine = e
        val c = ConsoleClient { msg -> main.post { e.log(msg) } }
        val n = NaviBridge(ctx, e, main)
        n.onStarted = {
            main.postDelayed({
                runCatching { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            }, 1500)
        }
        console = c; navi = n
        bridge = VehicleBridge(ctx, e, main)
        val b = bridge!!
        server = PanelServer(ctx, e, c, n, main) { k, s, v, cb -> b.directTest(k, s, v, cb) }
        e.log("앱 시작 · 로그인된 차량 프로필 ${e.driverLabel}" +
            (e.driverProfile?.let { " → 주인 ${it.name}" } ?: " → 주인 미등록 (처음 운전석에 앉을 때 물어봄)"))
        c.start(); server!!.start(); bridge!!.start(); n.start()
        main.post(ticker)
    }

    fun routeHome() = navi?.routeHome()

    fun stop() {
        main.removeCallbacks(ticker)
        bridge?.stop(); navi?.stop(); server?.stop(); console?.stop()
        engine = null; bridge = null; navi = null; server = null; console = null
    }
}
