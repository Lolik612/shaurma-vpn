package com.shaurma.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import hev.htproxy.TProxyService
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Цепочка: приложения → TUN → hev-socks5-tunnel → локальный sslocal (127.0.0.1) → Shadowsocks-сервер.
 * Само приложение исключено из VPN, поэтому sslocal ходит к серверу напрямую, без петли.
 */
class ShaurmaVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.shaurma.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.shaurma.vpn.DISCONNECT"
        private const val SOCKS_PORT = 10808
        private const val TAG = "ShaurmaVPN"
    }

    private val lock = Any()
    private var tun: ParcelFileDescriptor? = null
    private var sslocal: Process? = null
    private var tunnelStarted = false

    @Volatile
    private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startConnect()
            ACTION_DISCONNECT -> {
                teardown()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        teardown()
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun startConnect() {
        if (VpnController.state.value != VpnController.State.OFF) return
        stopping = false
        VpnController.state.value = VpnController.State.CONNECTING
        VpnController.log("нажато подключение")
        Thread({
            try {
                doConnect()
            } catch (e: Throwable) { // в т.ч. UnsatisfiedLinkError: иначе кнопка зависла бы на «Подключаюсь»
                Log.e(TAG, "connect failed", e)
                VpnController.log("ошибка: ${e.javaClass.simpleName}: ${e.message}")
                if (!stopping) VpnController.error.tryEmit(e.message ?: e.javaClass.simpleName)
                teardown()
            }
        }, "shaurma-connect").start()
        // Страховка: если подключение зависло, не висим вечно
        Thread({
            Thread.sleep(45000)
            if (!stopping && VpnController.state.value == VpnController.State.CONNECTING) {
                VpnController.log("таймаут подключения 45 с")
                VpnController.error.tryEmit("не удалось подключиться за 45 секунд. Открой «Диагностику» в настройках")
                teardown()
            }
        }, "shaurma-timeout").apply { isDaemon = true }.start()
    }

    private fun doConnect() {
        val raw = getSharedPreferences("vpn", MODE_PRIVATE).getString("key", null)
            ?: throw IOException("Ключ не задан")
        val key = KeyCodec.parse(raw)
        VpnController.log("старт: метод ${key.method}")
        VpnController.log("проверяю сервер…")

        // 1. Сервер вообще отвечает?
        try {
            Socket().use { it.connect(InetSocketAddress(key.host, key.port), 6000) }
        } catch (e: IOException) {
            throw IOException("сервер недоступен (${key.host}:${key.port})")
        }
        if (stopping) return

        // 2. Локальный Shadowsocks-клиент
        val cfgFile = File(filesDir, "sslocal.json")
        cfgFile.writeText(
            JSONObject()
                .put("server", key.host)
                .put("server_port", key.port)
                .put("password", key.password)
                .put("method", key.method)
                .put("local_address", "127.0.0.1")
                .put("local_port", SOCKS_PORT)
                .put("mode", "tcp_only") // без UDP: меньше нагрузки на сервер, DNS идёт через TCP
                .toString()
        )
        val bin = File(applicationInfo.nativeLibraryDir, "libsslocal.so")
        if (!bin.exists()) throw IOException("в сборке нет libsslocal.so")
        val proc = ProcessBuilder(bin.absolutePath, "-c", cfgFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        synchronized(lock) { sslocal = proc }
        Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { Log.d(TAG, "sslocal: $it"); VpnController.log("sslocal: $it") }
            } catch (_: IOException) {
            }
        }.apply { isDaemon = true }.start()

        val deadline = System.currentTimeMillis() + 4000
        var ready = false
        while (!ready && !stopping && System.currentTimeMillis() < deadline) {
            if (!isAlive(proc)) throw IOException("клиент Shadowsocks не запустился")
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), 200) }
                ready = true
            } catch (_: IOException) {
                Thread.sleep(100)
            }
        }
        if (stopping) return
        if (!ready) throw IOException("клиент Shadowsocks не отвечает")

        selfTest()
        if (stopping) return

        // 3. TUN-интерфейс (наше приложение в него не попадает)
        val builder = Builder()
            .setSession("Шаурма VPN")
            .setMtu(1500)
            .addAddress("198.18.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("198.18.0.2") // DNS через прокси (mapdns в hev)
            .addDnsServer("1.1.1.1")
        runCatching { builder.addDisallowedApplication(packageName) }
        val pfd = builder.establish() ?: throw IOException("не удалось создать VPN-интерфейс")
        synchronized(lock) { tun = pfd }
        if (stopping) {
            runCatching { pfd.close() }
            return
        }

        // 4. Туннель TUN → SOCKS5
        val hevCfg = File(filesDir, "hev.yml")
        hevCfg.writeText(
            """
            tunnel:
              mtu: 1500
              ipv4: 198.18.0.1
            socks5:
              port: $SOCKS_PORT
              address: 127.0.0.1
              udp: 'udp'
            mapdns:
              address: 198.18.0.2
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000
            misc:
              task-stack-size: 81920
              log-file: ${File(filesDir, "hev.log").absolutePath}
              log-level: info
            """.trimIndent()
        )
        TProxyService.TProxyStartService(hevCfg.absolutePath, pfd.fd)
        synchronized(lock) { tunnelStarted = true }
        VpnController.statsFn = { stats() }
        VpnController.log("туннель запущен")
        Thread({
            Thread.sleep(8000)
            if (!stopping) VpnController.log("через 8 с: " + stats())
        }, "shaurma-stats").apply { isDaemon = true }.start()

        // Если sslocal упадёт — честно отключаемся
        Thread({
            try {
                proc.waitFor()
            } catch (_: InterruptedException) {
            }
            if (!stopping) {
                VpnController.error.tryEmit("соединение с прокси оборвалось")
                teardown()
            }
        }, "shaurma-watch").apply { isDaemon = true }.start()

        VpnController.connectedAt = System.currentTimeMillis()
        VpnController.state.value = VpnController.State.ON
    }

    private fun stats(): String = try {
        val a = TProxyService.TProxyGetStats()
        "пакеты туда ${a[0]} (${a[1]} Б), обратно ${a[2]} (${a[3]} Б)"
    } catch (e: Throwable) {
        "статистика недоступна"
    }

    /** Проверяем путь «клиент → сервер → интернет» до запуска туннеля. */
    private fun selfTest() {
        VpnController.log("самотест: запрос через прокси…")
        var last = "?"
        for (host in listOf("example.com", "cloudflare.com")) {
            try {
                Socket().use { s ->
                    s.soTimeout = 10000
                    s.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), 2000)
                    val o = s.getOutputStream()
                    val i = s.getInputStream()
                    o.write(byteArrayOf(5, 1, 0)); o.flush()
                    val h = ByteArray(2); readFully(i, h)
                    if (h[1].toInt() != 0) throw IOException("SOCKS: метод не принят")
                    val hb = host.toByteArray()
                    o.write(byteArrayOf(5, 1, 0, 3, hb.size.toByte()) + hb + byteArrayOf(0, 80)); o.flush()
                    val r = ByteArray(4); readFully(i, r)
                    if (r[1].toInt() != 0) throw IOException("прокси отказал, код ${r[1]}")
                    val rest = when (r[3].toInt()) { 1 -> 6; 4 -> 18; else -> i.read() + 2 }
                    readFully(i, ByteArray(rest))
                    o.write("HEAD / HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray()); o.flush()
                    val resp = ByteArray(5); readFully(i, resp)
                    if (String(resp) != "HTTP/") throw IOException("странный ответ")
                }
                VpnController.log("самотест: ок ($host)")
                return
            } catch (e: IOException) {
                last = e.message ?: e.javaClass.simpleName
                VpnController.log("самотест $host: $last")
            }
        }
        throw IOException("сервер не пропускает трафик ($last). Проверь дату и время на телефоне — они должны быть автоматическими")
    }

    private fun readFully(i: InputStream, b: ByteArray) {
        var n = 0
        while (n < b.size) {
            val r = i.read(b, n, b.size - n)
            if (r < 0) throw IOException("соединение закрыто")
            n += r
        }
    }

    private fun isAlive(p: Process): Boolean = try {
        p.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun teardown() {
        synchronized(lock) {
            stopping = true
            VpnController.statsFn = null
            if (tunnelStarted) {
                runCatching { TProxyService.TProxyStopService() }
                tunnelStarted = false
            }
            sslocal?.let { runCatching { it.destroy() } }
            sslocal = null
            tun?.let { runCatching { it.close() } }
            tun = null
        }
        VpnController.state.value = VpnController.State.OFF
    }
}
