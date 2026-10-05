package com.mochame.node.managers

import com.mochame.sync.api.boot.BootState
import com.mochame.sync.api.boot.BootStatusProvider
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.boot.BootStatusUpdater
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.koin.core.annotation.Single
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Single component that binds to both provider and updater interfaces for the
 * boot state of a node. This component simply provides read or write access to the
 * mutable state flow, with no strict enforcing of state transition order. It is down
 * to the caller to manage the transitional lifecycle themselves.
 * If it happens that the components dependent on provider/updater interfaces becomes
 * more complex, it may be necessary to update this component to be more of a strict
 * state machine.
 */
@Single(binds = [BootStatusProvider::class, BootStatusUpdater::class])
class DefaultBootStatusManager(
    initialState: BootState = BootState.Idle,
    private val timeout: Duration = 5.seconds
) : BootStatusProvider, BootStatusUpdater {
    private val _state = MutableStateFlow<BootState>(BootState.Idle)
    override val bootState: StateFlow<BootState> = _state.asStateFlow()

    override fun updateState(newState: BootState) {
        _state.value = newState
    }

    /**
     * Suspends until the boot sequence completes.
     *
     * @throws MochaException.Persistent.BootInitializationError if boot fails or times out.
     */
    override suspend fun awaitReady() {
        try {
            withTimeout(timeout) {
                val state =
                    bootState.first { it !is BootState.Init && it !is BootState.Idle }

                if (state is BootState.Failure) {
                    throw state.cause
                        ?: MochaException.Persistent.BootInitializationError(state.message)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw MochaException.Persistent.BootInitializationError(
                "Boot sequence timed out after $timeout."
            )
        }
    }
}