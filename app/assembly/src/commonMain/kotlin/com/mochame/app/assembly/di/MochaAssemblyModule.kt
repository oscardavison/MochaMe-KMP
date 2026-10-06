package com.mochame.app.assembly.di

import com.mochame.annotations.AppBackgroundScope
import com.mochame.bio.di.BioProductionModule
import com.mochame.logger.LoggerModule
import com.mochame.platform.di.PlatformProductionModule
import com.mochame.platform.providers.AppBackgroundScopeOwner
import com.mochame.sync.di.SyncProductionModule
import com.mochame.utils.di.UtilsModule
import org.koin.core.KoinApplication
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.qualifier.named

@Module(
    includes = [
        LoggerModule::class,
        UtilsModule::class,
        PlatformProductionModule::class,

        MochaSchemaModule::class,

        BioProductionModule::class,

        SyncProductionModule::class
    ]
)
@ComponentScan("com.mochame.app.assembly")
class MochaAssemblyModule

val KoinApplication.backgroundScope: AppBackgroundScopeOwner?
    get() = koin.getOrNull<AppBackgroundScopeOwner>(qualifier = named<AppBackgroundScope>())
