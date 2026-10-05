package com.mochame.sync.spi.orchestration

import kotlinx.coroutines.Job

/**
 * Contract for boot-time maintenance and recurring sync metadata maintenance.
 */
interface SyncJanitor {
    /**
     * Executes initial database, metadata, HLC, and blob reconciliation checks during boot.
     */
    fun startupChecks(): Job

    /**
     * Spawns a background worker to continuously monitor stale leases and prune intents.
     */
    fun startRuntimeMaintenance(): Job
}