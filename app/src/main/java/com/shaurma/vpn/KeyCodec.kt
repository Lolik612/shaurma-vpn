package com.shaurma.vpn

import android.util.Base64
import java.net.URLDecoder

data class SsKey(val method: String, val password: String, val host: String, val port: Int)

/** Разбор ключа доступа — стандартная ссылка Shadowsocks: ss://base64(метод:пароль)@хост:порт#имя */
object KeyCodec {

    fun parse(input: String): SsKey {
        val raw = input.trim()
        require(raw.startsWith("ss://", ignoreCase = true)) { "not an ss:// key" }
        val body = raw.substring(5).substringBefore('#').substringBefore('?').trimEnd('/')

        val creds: String
        val hostPort: String
        val at = body.lastIndexOf('@')
        if (at >= 0) {
            val userInfo = body.substring(0, at)
            hostPort = body.substring(at + 1)
            creds = if (userInfo.contains(':')) URLDecoder.decode(userInfo, "UTF-8") else b64(userInfo)
        } else {
            val decoded = b64(body)
            val i = decoded.lastIndexOf('@')
            require(i >= 0) { "bad key" }
            creds = decoded.substring(0, i)
            hostPort = decoded.substring(i + 1)
        }

        val c = creds.indexOf(':')
        require(c > 0) { "bad credentials" }
        val p = hostPort.trimEnd('/').lastIndexOf(':')
        require(p > 0) { "bad host" }
        val port = hostPort.substring(p + 1).trimEnd('/').toInt()
        require(port in 1..65535) { "bad port" }

        return SsKey(
            method = creds.substring(0, c),
            password = creds.substring(c + 1),
            host = hostPort.substring(0, p).trim('[', ']'),
            port = port
        )
    }

    private fun b64(s: String): String {
        val n = s.filter { !it.isWhitespace() }.replace('-', '+').replace('_', '/')
        val padded = n + "=".repeat((4 - n.length % 4) % 4)
        return String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
    }
}
