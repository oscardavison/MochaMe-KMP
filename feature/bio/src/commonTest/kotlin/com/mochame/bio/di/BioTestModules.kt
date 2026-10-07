package com.mochame.bio.di

import com.mochame.bio.data.BioMicroSchema
import com.mochame.bio.data.DailyContextDao
import com.mochame.bio.data.DefaultDailyContextRepository
import com.mochame.bio.data.toDomain
import com.mochame.bio.data.toEntity
import com.mochame.bio.domain.DailyContext
import com.mochame.platform.fixtures.di.FixturesPlatformModule
import com.mochame.support.TestSupportModule
import com.mochame.sync.api.SyncAdaptor
import com.mochame.utils.fixtures.MochaFakeTimeUtils
import com.mochame.utils.fixtures.di.FakeMochaTimeProviderModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module(
    includes = [
        FixturesPlatformModule::class,
        FakeMochaTimeProviderModule::class,
        TestSupportModule::class,
    ]
)
@ComponentScan("com.mochame.bio.di", "com.mochame.bio.data")
internal class BioInfraTestModule {

    @Single
    fun provideDailyContextDao(db: BioMicroSchema): DailyContextDao = db.bioDao()

    @Single
    fun provideDailyContextRepository(
        dao: DailyContextDao,
        sync: SyncAdaptor<DailyContext>
    ): DefaultDailyContextRepository = DefaultDailyContextRepository(dao, sync)

    @Single
    fun provideTestDailyContextAdaptor(dao: DailyContextDao): SyncAdaptor<DailyContext> =
        object : SyncAdaptor<DailyContext> {
            override suspend fun upsert(
                candidateKey: Long,
                computeChange: suspend (DailyContext?) -> DailyContext
            ): Long {
                val existing = dao.getContextById(candidateKey)?.toDomain()
                val changed = computeChange(existing)
                if (existing != null && changed == existing) return 0L
                return dao.upsert(changed.toEntity())
            }

            override suspend fun delete(
                candidateKey: Long,
                computeChange: (suspend (DailyContext?) -> DailyContext)?
            ): Long {
                val existing = dao.getContextById(candidateKey)?.toDomain() ?: return 0L
                val changed = computeChange?.invoke(existing) ?: existing.copy(isDeleted = true)
                return dao.upsert(changed.toEntity())
            }
        }
}

@Factory
internal class BioTestEnv(
    val contextRepo: DefaultDailyContextRepository,
    val contextDao: DailyContextDao,
    val fakeClock: MochaFakeTimeUtils,
)