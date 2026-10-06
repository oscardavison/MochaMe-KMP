package com.mochame.app.assembly.di

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteDriver
import com.mochame.annotations.IoContext
import com.mochame.app.assembly.MochaMeDatabase
import com.mochame.app.assembly.MochaMeDatabaseConstructor
import com.mochame.bio.data.DailyContextDao
import com.mochame.sync.data.NodeContextDao
import com.mochame.platform.di.PlatformContext
import com.mochame.platform.providers.DatabaseLocation
import com.mochame.platform.providers.RoomImmediateTransProvider
import com.mochame.platform.providers.platformBuilder
import com.mochame.resonance.data.ResonanceDao
import com.mochame.sync.data.QuarantinedPayloadDao
import com.mochame.sync.data.SyncIntentDao
import com.mochame.sync.spi.TransactionProvider
import com.mochame.telemetry.data.TelemetryDao
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import kotlin.coroutines.CoroutineContext


@Module
class MochaSchemaModule {

    @Single(binds = [MochaMeDatabase::class, RoomDatabase::class])
    fun provideDatabase(
        context: PlatformContext,
        @IoContext ioContext: CoroutineContext,
        driver: SQLiteDriver,
        location: DatabaseLocation
    ): MochaMeDatabase = platformBuilder<MochaMeDatabase>(
        context = context,
        queryContext = ioContext,
        isTest = false,
        location = location,
        driver = driver,
        factory = { MochaMeDatabaseConstructor.initialize() }
    ).build()


    @Single
    fun provideNodeContextDao(database: MochaMeDatabase): NodeContextDao =
        database.nodeContextDao()

    @Single
    fun provideSyncIntentDao(database: MochaMeDatabase): SyncIntentDao =
        database.syncIntentDao()

    @Single
    fun provideMalformedPayloadDao(database: MochaMeDatabase): QuarantinedPayloadDao =
        database.quarantinedPayloadDao()

    @Single
    fun provideDailyContextDao(database: MochaMeDatabase): DailyContextDao =
        database.dailyContextDao()

    @Single
    fun provideTelemetryDao(database: MochaMeDatabase): TelemetryDao =
        database.telemetryDao()

    @Single
    fun provideResonanceDao(database: MochaMeDatabase): ResonanceDao =
        database.resonanceDao()

    @Single(binds = [TransactionProvider::class])
    fun provideTransactionProvider(database: MochaMeDatabase): TransactionProvider =
        RoomImmediateTransProvider(database)

}

