package com.mochame.sync.domain.config

import org.koin.core.annotation.Single
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Single
class JanitorMaintenanceConfig(
    /** Default: [DEFAULT_MAINTENANCE_DELAY] **/
    val maintenanceInterval: Duration = DEFAULT_MAINTENANCE_DELAY,
    /** Default: [STARTUP_TIMEOUT] **/
    val startupTimeout: Duration = STARTUP_TIMEOUT,
    /** Default: [DEFAULT_STALE_THRESHOLD] **/
    val staleThreshold: Duration = DEFAULT_STALE_THRESHOLD,
    /** Default: [DEFAULT_RETRY_THRESHOLD] **/
    val retryThreshold: Int = DEFAULT_RETRY_THRESHOLD
) {
    companion object {
        val DEFAULT_MAINTENANCE_DELAY = 30.seconds
        val DEFAULT_STALE_THRESHOLD = 5.minutes
        val STARTUP_TIMEOUT = 10.seconds
        const val DEFAULT_RETRY_THRESHOLD = 5
    }
}