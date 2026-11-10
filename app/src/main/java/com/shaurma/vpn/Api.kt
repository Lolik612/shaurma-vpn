package com.shaurma.vpn

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Запросы к серверу Шаурма VPN (аккаунты, подписка, конфиг подключения). */
object Api {
    fun post(path: String, body: JSONObject): JSONObject {
        val base = BuildConfig.API_URL.trim().trimEnd('/')
        if (base.isEmpty()) return JSONObject().put("ok", false).put("error", "no_server")
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
            return JSONObject(stream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }
}
