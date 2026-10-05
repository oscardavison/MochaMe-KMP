package com.mochame.bio.data

import com.mochame.bio.domain.DailyContext
import com.mochame.sync.api.models.HLC
import kotlin.time.Instant

fun DailyContextEntity.toDomain() = DailyContext(
    id = id,
    hlc = HLC.parse(hlc),
    sleepHours = sleepHours,
    readinessScore = readinessScore,
    isNapped = isNapped,
    notes = notes,
    isDeleted = isDeleted,
    lastModified = lastModified,
    createdAt = Instant.fromEpochMilliseconds(createdAt),
    fieldHlcs = fieldHlcs
)

fun DailyContext.toEntity() = DailyContextEntity(
    id = id,
    hlc = hlc.toString(),
    sleepHours = sleepHours,
    readinessScore = readinessScore,
    isNapped = isNapped,
    notes = notes,
    isDeleted = isDeleted,
    lastModified = lastModified,
    createdAt = createdAt.toEpochMilliseconds(),
    fieldHlcs = fieldHlcs
)