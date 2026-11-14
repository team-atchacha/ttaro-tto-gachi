package com.hyundaiapptest.seatid

import android.content.Context
import android.os.Handler
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 같은 기기 안에서 도는 Virtual Console(127.0.0.1:8088)을 호출한다.
 * Console은 브라우저 세션 heartbeat가 있어야 값을 받으므로 주기적으로 보낸다.
 */
class ConsoleClient(private val onResult: (String) -> Unit) {
    private val exec = Executors.newSingleThreadScheduledExecutor()
    @Volatile var lastError: String? = null

    fun start() {
        exec.scheduleWithFixedDelay({ request("POST", "/browser/session?client=seatid-demo") }, 0, 4, TimeUnit.SECONDS)
    }

    fun stop() = exec.shutdownNow()

    fun put(path: String) = exec.execute {
        request("POST", "/browser/session?client=seatid-demo")
        val code = request("PUT", path)
        if (code !in 200..299) onResult("Virtual Console 호출 실패 ($code): $path")
    }

    private fun request(method: String, path: String): Int = try {
        val c = URL("http://127.0.0.1:8088$path").openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 2000; c.readTimeout = 3000
        val code = c.responseCode
        runCatching { (if (code < 400) c.inputStream else c.errorStream)?.close() }
        c.disconnect()
        lastError = if (code in 200..299) null else "HTTP $code"
        code
    } catch (e: Exception) {
        lastError = e.javaClass.simpleName; -1
    }
}

/**
 * 웹 조작판 서버(기기 안 127.0.0.1:8090). `adb forward tcp:8090 tcp:8090`으로 PC 브라우저에서 연다.
 *  GET  /           조작판 화면
 *  GET  /api/state  현재 판단 상태
 *  POST /api/act    {"type": ..., "seat": "FL", ...}
 */
class PanelServer(
    private val ctx: Context,
    private val engine: Engine,
    private val console: ConsoleClient,
    private val main: Handler,
) {
    private var socket: ServerSocket? = null

    fun start() = thread(name = "panel-server", isDaemon = true) {
        try {
            // 사용자 프로필을 바꾼 직후에는 이전 사용자의 앱이 아직 포트를 잡고 있을 수 있어 잠시 재시도한다
            var ss: ServerSocket? = null
            for (i in 0 until 30) {
                ss = runCatching { ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1")) }.getOrNull()
                if (ss != null) break
                Thread.sleep(1000)
            }
            if (ss == null) error("포트 $PORT 사용 중")
            socket = ss
            while (!ss.isClosed) {
                val s = ss.accept()
                thread(isDaemon = true) { runCatching { handle(s) }; runCatching { s.close() } }
            }
        } catch (e: Exception) {
            main.post { engine.log("웹 조작판 서버 종료: ${e.message}") }
        }
    }

    fun stop() { runCatching { socket?.close() } }

    private fun handle(s: Socket) {
        s.soTimeout = 5000
        val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val requestLine = input.readLine() ?: return
        val (method, target) = requestLine.split(" ").let { it[0] to it.getOrElse(1) { "/" } }
        var length = 0
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(":").trim().toIntOrNull() ?: 0
        }
        val body = if (length > 0) CharArray(length).let { buf -> var n = 0; while (n < length) { val r = input.read(buf, n, length - n); if (r < 0) break; n += r }; String(buf, 0, n) } else ""
        val out = s.getOutputStream()
        val path = target.substringBefore("?")
        when {
            method == "GET" && (path == "/" || path == "/index.html") ->
                send(out, 200, "text/html; charset=utf-8", ctx.assets.open("panel.html").readBytes())
            method == "GET" && path == "/api/state" ->
                send(out, 200, "application/json; charset=utf-8", onMainSync { stateJson() }.toByteArray())
            method == "POST" && path == "/api/act" -> {
                val res = runCatching { act(JSONObject(body)) }.fold({ "{\"ok\":true}" }, { JSONObject().put("ok", false).put("error", it.message).toString() })
                send(out, 200, "application/json; charset=utf-8", res.toByteArray())
            }
            else -> send(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun stateJson() = engine.toJson().apply { put("consoleError", console.lastError ?: JSONObject.NULL) }.toString()

    private fun act(j: JSONObject) {
        val type = j.getString("type")
        val seat = if (j.isNull("seat")) null else SeatPos.valueOf(j.getString("seat"))
        when (type) {
            // 실제 SDK 경로: Virtual Console → 차량 속성 → Vehicle SDK 구독 → Engine
            "door" -> console.put("/vehicle/body?${seat!!.consoleDoor}=${j.getBoolean("value")}")
            "sit" -> console.put("/vehicle/seat?kind=occupied&value=${j.getBoolean("value")}&area=${seat!!.consoleArea}")
            "airbag" -> console.put("/vehicle/telltales/simulator?color=Yellow&state=${if (j.getBoolean("value")) "On" else "Off"}&type=PassengerAirbagOff")
            // 시뮬레이션 경로: 에뮬레이터가 값을 못 주는 항목은 Engine에 직접 넣는다
            "belt" -> main.post { engine.onSimBelt(seat!!, j.getBoolean("value")) }
            // 탑승자 응답(차 화면 대신 웹에서 누를 때)
            "choose" -> main.post { engine.choose(seat!!, j.getString("person")) }
            "reidentify" -> main.post { engine.reidentify(seat!!) }
            "reset" -> main.post { engine.resetAll() }
            else -> error("알 수 없는 type: $type")
        }
    }

    private fun <T> onMainSync(block: () -> T): T {
        var result: T? = null
        val latch = CountDownLatch(1)
        main.post { result = block(); latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun send(out: OutputStream, code: Int, type: String, bytes: ByteArray) {
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray()); out.write(bytes); out.flush()
    }

    companion object { const val PORT = 8090 }
}
