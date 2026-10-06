package com.mochame.platform.di

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import co.touchlab.kermit.Logger
import com.mochame.annotations.AppBackgroundScope
import com.mochame.annotations.DefaultContext
import com.mochame.annotations.IoContext
import com.mochame.annotations.MainContext
import com.mochame.platform.providers.AppBackgroundScopeOwner
import com.mochame.platform.providers.createPlatformDigest
import com.mochame.sync.spi.DigestFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import kotlin.coroutines.CoroutineContext

@Module(
    includes = [
        CommonPlatformModule::class,
        InternalPlatformModule::class,
        PlatformProviderModule::class
    ]
)
class PlatformProductionModule

@Module
class CommonPlatformModule {

    @Single
    fun provideProductionDriver(): SQLiteDriver = BundledSQLiteDriver()

    @Single
    @IoContext
    fun provideIoContext(): CoroutineContext = Dispatchers.IO

    @Single
    @MainContext
    fun provideMainContext(): CoroutineContext = Dispatchers.Main

    @Single
    @DefaultContext
    fun provideDefaultContext(): CoroutineContext = Dispatchers.Default

    @Single(binds = [CoroutineScope::class, AutoCloseable::class])
    @AppBackgroundScope
    fun provideBackgroundAppScope(
        @DefaultContext context: CoroutineContext,
        logger: Logger
    ): AppBackgroundScopeOwner = AppBackgroundScopeOwner(context, logger)
}

@Module
expect class InternalPlatformModule

expect class PlatformContext

@Module
@ComponentScan("com.mochame.platform.providers")
class PlatformProviderModule {

    @Single
    fun provideHasher(logger: Logger): DigestFactory = DigestFactory {
        createPlatformDigest(logger = logger)
    }
}