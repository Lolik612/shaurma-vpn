package com.shaurma.vpn

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Общее состояние между сервисом и экраном. */
object VpnController {
    enum class State { OFF, CONNECTING, ON }

    val state = MutableStateFlow(State.OFF)
    val error = MutableSharedFlow<String>(extraBufferCapacity = 4)

    @Volatile
    var connectedAt: Long = 0L
}
