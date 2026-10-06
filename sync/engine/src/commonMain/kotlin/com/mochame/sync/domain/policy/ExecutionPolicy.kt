package com.mochame.sync.domain.policy

/**
 * Defines an execution strategy for wrapping operations with cross-cutting concerns
 * such as retries, timeouts, or tracing.
 */
interface ExecutionPolicy {
    /**
     * Executes [block] under this policy.
     *
     * @param R The return type of the operation.
     * @param operationTag An identifier used for logging, metrics, or tracing.
     * @param block The suspendable action to execute.
     */
    suspend fun <R> execute(
        operationTag: String,
        block: suspend () -> R
    ): R
}
