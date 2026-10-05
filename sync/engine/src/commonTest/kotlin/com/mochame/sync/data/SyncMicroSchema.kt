package com.mochame.sync.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters


@ConstructedBy(SyncMicroSchemaConstructor::class)
@Database(
    entities = [
        SyncIntentEntity::class,
        QuarantinedPayloadEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(SyncConverters::class)
internal abstract class SyncMicroSchema : RoomDatabase() {
    internal abstract fun syncIntentDao(): SyncIntentDao
    internal abstract fun quarantinedPayloadDao(): QuarantinedPayloadDao

    internal companion object {
        const val NAME = "sync_micro_schema.db"
    }
}

internal expect object SyncMicroSchemaConstructor : RoomDatabaseConstructor<SyncMicroSchema> {
    override fun initialize(): SyncMicroSchema
}