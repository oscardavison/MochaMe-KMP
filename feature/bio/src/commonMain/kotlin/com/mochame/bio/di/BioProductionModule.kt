package com.mochame.bio.di

import com.mochame.bio.data.DailyContextDao
import com.mochame.bio.data.toDomain
import com.mochame.bio.data.toEntity
import com.mochame.bio.domain.DailyContext
import com.mochame.bio.infrastructure.DailyContextCodecResolver
import com.mochame.sync.api.SyncAdaptor
import com.mochame.sync.api.SyncAdaptorFactory
import com.mochame.sync.api.metadata.FeatureContext
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module(includes = [BioSyncModule::class])
@ComponentScan("com.mochame.bio")
class BioProductionModule

@Module
@ComponentScan("com.mochame.bio.data")
class BioDataModule

@Module
class BioSyncModule {

    @Single(createdAtStart = true)
    fun provideDailyContextAdaptor(
        factory: SyncAdaptorFactory,
        dao: DailyContextDao,
        codec: DailyContextCodecResolver
    ): SyncAdaptor<DailyContext> = factory(
        featureContext = FeatureContext.BIO_DAILY_CONTEXT,
        codec = codec,
        fetchById = { dao.getContextById(it)?.toDomain() },
        save = { dao.upsert(it.toEntity()) }
    )
}