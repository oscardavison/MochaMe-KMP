package com.mochame.support

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.koin.core.KoinApplication
import org.koin.core.annotation.KoinInternalApi
import org.koin.dsl.koinApplication

/**
 * Establishes a pure logic environment, and handles Koin App lifecycle.
 * Ties all CoroutineContexts to the virtual clock of runTest.
 */
@OptIn(KoinInternalApi::class)
inline fun <reified E : Any> runUnitEnvironment(
    bindTestScope: Boolean = true,
    crossinline koinSetup: KoinApplication.() -> Unit = {},
    crossinline block: suspend E.(TestScope) -> Unit
) = runTest {
    val koinApp = koinApplication(createEagerInstances = false) {
        allowOverride(true)
        koinSetup()
        if (bindTestScope) {
            modules(this@runTest.bindAsKoinModule())
        }
    }

    val koin = koinApp.koin
    var environment: E? = null

    try {
        koin.get<E>().also { environment = it }.block(this)
    } catch (e: Exception) {
        e.reportAndThrowFailure()
    } finally {
        performTestTeardown(environment, koin) {
            koinApp.close()
        }
    }
}