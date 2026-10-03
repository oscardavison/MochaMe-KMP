package com.mochame.bio.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.time.Clock

@Entity(
    tableName = "daily_context",
    indices = [
        Index("hlc")
    ]
)
data class DailyContextEntity(
    @PrimaryKey
    val id: Long,
    val hlc: String,
    val sleepHours: Double? = null,
    val readinessScore: Int? = null,
    val isNapped: Boolean? = null,
    val notes: String? = null,
    val isDeleted: Boolean = false,
    val fieldHlcs: ByteArray = ByteArray(0),
    val lastModified: Long,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds()
)