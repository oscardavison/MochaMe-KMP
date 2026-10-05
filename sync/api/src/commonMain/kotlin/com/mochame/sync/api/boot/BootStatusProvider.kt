package com.mochame.sync.api.boot

import kotlinx.coroutines.flow.StateFlow

interface BootStatusProvider {
    val bootState: StateFlow<BootState>

    suspend fun awaitReady()
}