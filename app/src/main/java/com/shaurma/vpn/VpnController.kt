package com.shaurma.vpn

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Общее состояние между сервисом и экраном + журнал для диагностики. */
object VpnController {
    enum class State { OFF, CONNECTING, ON }

    val state = MutableStateFlow(State.OFF)
    val error = MutableSharedFlow<String>(extraBufferCapacity = 4)

    @Volatile
    var connectedAt: Long = 0L

    @Volatile
    var statsFn: (() -> String)? = null

    private val lines = ArrayDeque<String>()

    fun log(msg: String) {
        synchronized(lines) {
            lines.addLast(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + msg)
            while (lines.size > 300) lines.removeFirst()
        }
    }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }
}
