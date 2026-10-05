package com.mochame.sync.api.boot


sealed interface BootState {
    object Idle : BootState {
        override fun toString() = "Idle"
    }

    object Init : BootState {
        override fun toString() = "Init"
    }

    object Ready : BootState {
        override fun toString() = "Ready"
    }

    sealed interface Failure : BootState {
        val message: String
        val cause: Exception?
    }

    data class TransientFailure(
        override val message: String,
        override val cause: Exception? = null
    ) : Failure

    data class LockOut(
        override val message: String,
        override val cause: Exception? = null
    ) : Failure
}