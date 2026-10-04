package com.mochame.sync.domain.infrastructure

import kotlinx.coroutines.flow.Flow

/**
 * A simple bridge between modules to allow components to trigger/collect emissions
 * without needing Rooms invalidation trackers.
 */
interface SyncWorkerHook {
    /**
     * This component will produce emissions related to changes in the state of
     * synchronization work.
     */
    val signals: Flow<Unit>

    /**
     * Triggers the flow to communicate work is to be done for synchronizing data.
     */
    fun invalidate()
}