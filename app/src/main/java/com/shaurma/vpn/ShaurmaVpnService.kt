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
                VpnController.error.tryEmit("не удалось подключиться за 45 секунд.")
                teardown()
            }
        }, "shaurma-timeout").apply { isDaemon = true }.start()
    }

    private fun doConnect() {
        val prefs0 = getSharedPreferences("vpn", MODE_PRIVATE)
        if (prefs0.getString("mode", "all") == "dns") {
            doDns(prefs0)
            return
        }
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
            // IPv6 заворачиваем в туннель, где он отбрасывается: иначе приложения (Telegram в первую очередь)
            // ходят по IPv6 мимо VPN, а так быстро переключаются на IPv4 через прокси
            .addAddress("fd00:198:18::1", 128)
            .addRoute("::", 0)
            .addDnsServer("198.18.0.2") // только DNS через прокси (mapdns в hev): запасной DNS UDP не пропустит
        val prefs = getSharedPreferences("vpn", MODE_PRIVATE)
        var onlyTelegram = prefs.getString("mode", "all") == "tg"
        if (onlyTelegram) {
            val pkgs = listOf(
                "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus",
                "org.thunderdog.challegram", "nekox.messenger", "tw.nekomimi.nekogram", "uz.unnarsx.cherrygram"
            )
            var added = 0
            for (pkg in pkgs) {
                try {
                    builder.addAllowedApplication(pkg)
                    added++
                } catch (_: Exception) {
                }
            }
            VpnController.log("режим «только Telegram»: приложений в VPN — $added")
            if (added == 0) onlyTelegram = false
        }
        if (!onlyTelegram) runCatching { builder.addDisallowedApplication(packageName) }
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
        if (!TProxyService.TProxyStartService(hevCfg.absolutePath, pfd.fd)) {
            throw IOException("не запустился туннель")
        }
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

    // ───────────── режим DNS: через туннель идут только DNS-запросы ─────────────

    private fun dnsEndpoint(prefs: android.content.SharedPreferences): String = when (prefs.getString("dns", "comss")) {
        "comss" -> "https://dns.comss.one/dns-query"
        "cf" -> "https://cloudflare-dns.com/dns-query"
        "google" -> "https://dns.google/dns-query"
        else -> prefs.getString("dns_custom", "")?.trim().orEmpty()
    }

    private fun doDns(prefs: android.content.SharedPreferences) {
        val endpoint = dnsEndpoint(prefs)
        if (endpoint.isEmpty()) throw IOException("DNS-сервер не задан")
        val pfd = Builder()
            .setSession("Шаурма VPN")
            .setMtu(1500)
            .addAddress("198.18.0.1", 32)
            .addRoute("198.18.0.53", 32)
            .addDnsServer("198.18.0.53")
            .apply { runCatching { addDisallowedApplication(packageName) } }
            .establish() ?: throw IOException("не удалось создать VPN-интерфейс")
        synchronized(lock) { tun = pfd }
        if (stopping) {
            runCatching { pfd.close() }
            return
        }
        VpnController.connectedAt = System.currentTimeMillis()
        VpnController.state.value = VpnController.State.ON
        Thread({ dnsLoop(pfd, endpoint) }, "shaurma-dns").start()
    }

    private fun dnsLoop(pfd: ParcelFileDescriptor, endpoint: String) {
        val input = java.io.FileInputStream(pfd.fileDescriptor)
        val output = java.io.FileOutputStream(pfd.fileDescriptor)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val buf = ByteArray(32767)
        try {
            while (!stopping) {
                val n = input.read(buf)
                if (n < 0) break
                if (n < 28 || (buf[0].toInt() shr 4) != 4) continue
                val ihl = (buf[0].toInt() and 15) * 4
                if (buf[9].toInt() != 17 || n < ihl + 8) continue
                val dport = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
                if (dport != 53) continue
                val pkt = buf.copyOf(n)
                pool.execute {
                    try {
                        val ulen = ((pkt[ihl + 4].toInt() and 0xFF) shl 8) or (pkt[ihl + 5].toInt() and 0xFF)
                        val query = pkt.copyOfRange(ihl + 8, minOf(pkt.size, ihl + ulen))
                        val answer = resolveDns(query, endpoint) ?: return@execute
                        val reply = buildUdpReply(pkt, ihl, answer)
                        synchronized(output) { output.write(reply) }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            pool.shutdownNow()
            if (!stopping) {
                VpnController.error.tryEmit("соединение прервано")
                teardown()
            }
        }
    }

    private fun resolveDns(query: ByteArray, endpoint: String): ByteArray? {
        if (endpoint.startsWith("https://")) {
            val c = java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 4000
                c.readTimeout = 4000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/dns-message")
                c.setRequestProperty("Accept", "application/dns-message")
                c.outputStream.use { it.write(query) }
                if (c.responseCode != 200) return null
                return c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }
        val host = endpoint.substringBefore(":")
        val port = endpoint.substringAfter(":", "53").toIntOrNull() ?: 53
        java.net.DatagramSocket().use { s ->
            protect(s)
            s.soTimeout = 3000
            s.send(java.net.DatagramPacket(query, query.size, java.net.InetAddress.getByName(host), port))
            val r = ByteArray(4096)
            val p = java.net.DatagramPacket(r, r.size)
            s.receive(p)
            return r.copyOf(p.length)
        }
    }

    /** Собираем IPv4/UDP-ответ: адреса и порты меняем местами. */
    private fun buildUdpReply(req: ByteArray, ihl: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val o = ByteArray(total)
        o[0] = 0x45
        o[2] = (total shr 8).toByte(); o[3] = total.toByte()
        o[8] = 64; o[9] = 17
        System.arraycopy(req, 16, o, 12, 4) // источник ответа = куда слали запрос
        System.arraycopy(req, 12, o, 16, 4)
        var sum = 0
        for (i in 0 until 20 step 2) sum += ((o[i].toInt() and 0xFF) shl 8) or (o[i + 1].toInt() and 0xFF)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val ck = sum.inv() and 0xFFFF
        o[10] = (ck shr 8).toByte(); o[11] = ck.toByte()
        o[20] = req[ihl + 2]; o[21] = req[ihl + 3] // порт источника = 53
        o[22] = req[ihl]; o[23] = req[ihl + 1]
        val ulen = 8 + payload.size
        o[24] = (ulen shr 8).toByte(); o[25] = ulen.toByte()
        System.arraycopy(payload, 0, o, 28, payload.size)
        return o
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
