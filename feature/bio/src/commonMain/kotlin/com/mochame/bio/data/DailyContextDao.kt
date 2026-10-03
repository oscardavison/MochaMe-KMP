package com.mochame.bio.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyContextDao {

    /**
     * The Standard Write: Persists local changes.
     * Used by the [BaseRepository] block.
     */
    @Upsert
    suspend fun upsert(context: DailyContextEntity): Long

    /**
     * Physically removes tombstones that have been synced to the cloud
     * and aged out of the 30-day "Dissemination Window."
     * * @param cutoff The physical timestamp (System.now() - 30.days)
     */
    @Query("""
        DELETE FROM daily_context 
        WHERE isDeleted = 1 
        AND lastModified < :cutoff
    """)
    suspend fun hardDeletePruning(cutoff: Long)

    /**
     * Integrity Check:
     * Returns the count of tombstones currently being held.
     */
    @Query("SELECT COUNT(*) FROM daily_context WHERE isDeleted = 1")
    suspend fun countSoftDeleted(): Int


    // --- LOOKUPS ---

    @Query("SELECT * FROM daily_context WHERE id = :id AND isDeleted = 0 LIMIT 1")
    suspend fun getActiveContextById(id: Long): DailyContextEntity?

    @Query("SELECT * FROM daily_context WHERE id = :id LIMIT 1")
    suspend fun getAnyContextById(id: Long): DailyContextEntity?

    @Query("SELECT * FROM daily_context WHERE isDeleted = 0 ORDER BY id DESC")
    suspend fun getAllContexts(): List<DailyContextEntity>

    @Query("SELECT * FROM daily_context WHERE isNapped = 1 AND isDeleted = 0")
    suspend fun getAllNappedContexts(): List<DailyContextEntity>

    @Query("SELECT * FROM daily_context WHERE isNapped = 0 AND isDeleted = 0")
    suspend fun getAllNonNappedContexts(): List<DailyContextEntity>

    // --- UI OBSERVABLES (Filtered) ---

    /**
     * The UI Anchor:
     * Filters out tombstones so the user doesn't see "deleted" days.
     */
    @Query("SELECT * FROM daily_context WHERE id = :epochDay AND isDeleted = 0 LIMIT 1")
    fun observeContext(epochDay: Long): Flow<DailyContextEntity?>

    @Query("SELECT * FROM daily_context WHERE isDeleted = 0 ORDER BY id DESC")
    fun observeAllContexts(): Flow<List<DailyContextEntity>>

    // --- NAP LOGIC (Filtered) ---

    @Query("SELECT * FROM daily_context WHERE isNapped = 1 AND isDeleted = 0")
    fun observeAllNappedContexts(): Flow<List<DailyContextEntity>>

    @Query("SELECT * FROM daily_context WHERE isNapped = 0 AND isDeleted = 0")
    fun observeAllNonNappedContexts(): Flow<List<DailyContextEntity>>
}