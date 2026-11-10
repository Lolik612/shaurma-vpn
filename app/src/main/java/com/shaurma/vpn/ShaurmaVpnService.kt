package com.shaurma.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import hev.htproxy.TProxyService
import org.json.JSONObject
import java.io.File
import java.io.IOException
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
        Thread({
            try {
                doConnect()
            } catch (e: Exception) {
                Log.e(TAG, "connect failed", e)
                if (!stopping) VpnController.error.tryEmit(e.message ?: e.javaClass.simpleName)
                teardown()
            }
        }, "shaurma-connect").start()
    }

    private fun doConnect() {
        val raw = getSharedPreferences("vpn", MODE_PRIVATE).getString("key", null)
            ?: throw IOException("Ключ не задан")
        val key = KeyCodec.parse(raw)

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
                proc.inputStream.bufferedReader().forEachLine { Log.d(TAG, "sslocal: $it") }
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
              log-level: warn
            """.trimIndent()
        )
        if (!TProxyService.TProxyStartService(hevCfg.absolutePath, pfd.fd)) {
            throw IOException("не запустился туннель")
        }
        synchronized(lock) { tunnelStarted = true }

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

    private fun isAlive(p: Process): Boolean = try {
        p.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun teardown() {
        synchronized(lock) {
            stopping = true
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
