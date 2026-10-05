package com.mochame.sync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query


@Dao
interface QuarantinedPayloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: QuarantinedPayloadEntity)

    @Query("SELECT * FROM quarantined_payload ORDER BY watermark ASC")
    suspend fun getAll(): List<QuarantinedPayloadEntity>

    @Query("SELECT * FROM quarantined_payload WHERE watermark = :watermark LIMIT 1")
    suspend fun getByWatermark(watermark: Long): QuarantinedPayloadEntity?

    @Query("DELETE FROM quarantined_payload WHERE watermark = :watermark")
    suspend fun deleteByWatermark(watermark: Long)

    @Query("DELETE FROM quarantined_payload WHERE receivedAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int
}