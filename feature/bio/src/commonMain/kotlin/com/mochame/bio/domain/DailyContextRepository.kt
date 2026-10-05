package com.mochame.bio.domain

import kotlinx.coroutines.flow.Flow

interface DailyContextRepository {

    fun observeContext(epochDay: Long): Flow<DailyContext?>
    suspend fun upsertContext(context: DailyContext) : Long
    suspend fun getActiveContextById(epochDay: Long): DailyContext?
    suspend fun softDeleteContext(epochDay: Long): Long
    suspend fun hardDeleteContexts(cutoff: Long)
    suspend fun countSoftDeleted(): Int

    //    fun getHistory(): Flow<List<DailyContext>>

}