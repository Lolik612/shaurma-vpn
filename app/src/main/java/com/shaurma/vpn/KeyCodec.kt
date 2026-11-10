package com.shaurma.vpn

import android.util.Base64

/** Ключ доступа: `shaurma://<base64url(wg-конфиг)>`. Для удобства принимается и «сырой» конфиг. */
object KeyCodec {
    private const val PREFIX = "shaurma://"

    fun toConfig(input: String): String {
        val text = input.trim()
        if (!text.startsWith(PREFIX, ignoreCase = true)) return text
        val b64 = text.substring(PREFIX.length).replace(Regex("\\s"), "")
        val bytes = Base64.decode(b64, Base64.URL_SAFE)
        return String(bytes, Charsets.UTF_8)
    }
}
