package com.mochame.support

import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.mochame.annotations.IoContext
import com.mochame.platform.providers.DatabaseLocation
import com.mochame.platform.providers.platformBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.koin.core.KoinApplication
import org.koin.core.qualifier.qualifier
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.coroutines.CoroutineContext


/**
 * A wrapper method that calls [platformBuilder] which handles establishing a
 * database for production, but this method overrides platform dependencies in :core:platform
 * via an interception through Koin that links test dependencies. isTest is set
 * to true for the database builder, ensuring an inMemory database is built. Koin DSL is
 * used to provide a hybrid approach to dependency injection, allowing the
 * runtime isolated [TestScope] to be fetched and wired into the
 * [androidx.room.RoomDatabase.Builder.setQueryCoroutineContext] of the
 * database builder, and any components requiring a [CoroutineContext] or [CoroutineScope]
 * themselves. This is handled by the nested [TestScope.bindAsKoinModule] method utilizing
 * Koin DSL.
 *
 * By calling this method, any scopes and coroutine contexts will be aligned
 * with the current [TestScope], the inMemory database will be established,
 * and the TestScope provided to the caller for manipulating the virtual
 * clock. The provided environment will be the scope you are now in.
 *
 * Inline usage - all calls up to the call to runTest, so the final size for each test method will
 * include the test code, the environment, and this persistence wrapper. This is then stored
 * on the heap and passed as a single lambda to the testBody of [runTest].
 *
 * In terms of database lifecycle and isolation assurance per test, each call to this wrapper
 * enacts a fresh build call with a null database name handled internally by
 * Room, which should mean that the in-memory database instance is connection-scoped by default.
 * Each call to build creates a new connection, and a new connection to an unnamed in-memory
 * database is a new database. This wrapper then wraps the execution of [block] in a try - finally
 * catch, ensuring the database is closed before the Koin application itself.
 * If test flakiness starts occurring when using the same Database schemas, come here.
 *
 * @param T The Type for the specific Room Database object used for construction.
 * @param E The receiver type that the caller defines for the scope of their test block.
 * @param constructor Micro schema constructor which is passed to a builder.
 * @param koinSetup Expects a nested inclusion of the SUT koin application, its modules then attached to a test koin application.
 * @param block The actual test block to be run once the test environment is set up.
 */
inline fun <reified T : RoomDatabase, reified E : Any> runDatabaseEnvironment(
    bindTestScope: Boolean = true,
    constructor: RoomDatabaseConstructor<T>,
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

    val database = platformBuilder<T>(
        context = koin.get(),
        queryContext = koin.get(qualifier<IoContext>()),
        isTest = true,
        location = DatabaseLocation.InMemory,
        driver = koin.get(),
        factory = { constructor.initialize() }
    ).build()

    koin.loadModules(listOf(module { single<T> { database } }))
    var environment: E? = null

    try {
        koin.get<E>().also { environment = it }.block(this)
    } catch (e: Exception) {
        e.reportAndThrowFailure()
    } finally {
        performTestTeardown(environment, koin) {
            database.close()
            koinApp.close()
        }
    }
}



