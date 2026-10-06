package com.mochame.bio.di

import com.mochame.bio.data.BioMicroSchema
import com.mochame.bio.data.DailyContextDao
import com.mochame.bio.data.DefaultDailyContextRepository
import com.mochame.platform.fixtures.di.FixturesPlatformModule
import com.mochame.support.TestSupportModule
import com.mochame.utils.fixtures.MochaFakeTimeUtils
import com.mochame.utils.fixtures.di.FakeMochaTimeProviderModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module(
    includes = [
        BioProductionModule::class,
        FixturesPlatformModule::class,
        FakeMochaTimeProviderModule::class,
        TestSupportModule::class,
    ]
)
@ComponentScan("com.mochame.bio.di")
internal class BioInfraTestModule {
    @Single
    fun provideDailyContextDao(db: BioMicroSchema): DailyContextDao = db.bioDao()
}

@Factory
internal class BioTestEnv(
    val contextRepo: DefaultDailyContextRepository,
    val contextDao: DailyContextDao,
    val fakeClock: MochaFakeTimeUtils,
)