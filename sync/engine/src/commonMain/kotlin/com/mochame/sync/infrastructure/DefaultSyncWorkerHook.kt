package com.mochame.sync.infrastructure

import com.mochame.sync.domain.infrastructure.SyncWorkerHook
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.koin.core.annotation.Single

/**
 * Could be replaced with Channel?
 */
@Single(binds = [SyncWorkerHook::class])
class DefaultSyncWorkerHook : SyncWorkerHook {
    private val _signals = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val signals: Flow<Unit> = _signals.asSharedFlow()

    /**
     * The shared flow is configured to automatically drop the oldest pending
     * emission, replacing it with the latest emission. Consequently, the boolean return value
     * from [MutableSharedFlow.tryEmit] should always return true, though branching logic based on that
     * is not necessary here.
     * The UNIT singleton performs no additional boxing of values, and I think is just
     * a performative object necessary for an emission.
     */
    override fun invalidate() {
        _signals.tryEmit(Unit)
    }
}