package com.mochame.sync.api.boot

interface BootStatusUpdater : BootStatusProvider {
    fun updateState(newState: BootState)
}