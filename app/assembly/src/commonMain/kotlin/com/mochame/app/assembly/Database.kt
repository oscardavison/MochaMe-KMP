package com.mochame.app.assembly

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import com.mochame.bio.data.DailyContextDao
import com.mochame.bio.data.DailyContextEntity
import com.mochame.node.data.NodeContextDao
import com.mochame.node.data.NodeContextEntity
import com.mochame.resonance.data.AuthorEntity
import com.mochame.resonance.data.BookEntity
import com.mochame.resonance.data.QuoteEntity
import com.mochame.resonance.data.ResonanceDao
import com.mochame.sync.data.QuarantinedPayloadDao
import com.mochame.sync.data.QuarantinedPayloadEntity
import com.mochame.sync.data.SyncConverters
import com.mochame.sync.data.SyncIntentDao
import com.mochame.sync.data.SyncIntentEntity
import com.mochame.telemetry.data.DomainEntity
import com.mochame.telemetry.data.MomentAttachmentEntity
import com.mochame.telemetry.data.MomentEntity
import com.mochame.telemetry.data.SpaceEntity
import com.mochame.telemetry.data.TelemetryDao
import com.mochame.telemetry.data.TopicEntity

@ConstructedBy(MochaMeDatabaseConstructor::class)
@Database(
    entities = [
        NodeContextEntity::class,
        SyncIntentEntity::class,
        QuarantinedPayloadEntity::class,

        DailyContextEntity::class,

        MomentEntity::class,
        MomentAttachmentEntity::class,
        DomainEntity::class,
        TopicEntity::class,
        SpaceEntity::class,

        AuthorEntity::class,
        BookEntity::class,
        QuoteEntity::class,
    ],
    version = 6,
    exportSchema = false
)
@TypeConverters(MochaConverters::class, SyncConverters::class)
abstract class MochaMeDatabase : RoomDatabase() {
    abstract fun nodeContextDao(): NodeContextDao
    abstract fun syncIntentDao(): SyncIntentDao
    abstract fun quarantinedPayloadDao(): QuarantinedPayloadDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun resonanceDao(): ResonanceDao
    abstract fun dailyContextDao(): DailyContextDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object MochaMeDatabaseConstructor : RoomDatabaseConstructor<MochaMeDatabase> {
    override fun initialize(): MochaMeDatabase
}