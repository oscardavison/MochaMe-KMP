package com.mochame.platform.providers

import androidx.room.RoomDatabase
import androidx.room.Transactor
import androidx.room.useWriterConnection
import com.mochame.sync.spi.TransactionProvider

/**
 * This was initially added to reduce boilerplate and specifically to abstract Room from this
 * applications design. However, especially to prevent the extra boilerplate of abstracting DAOs and
 * entities, the sync-engine has now been coupled to Room by design. Keeping this method here
 * for now purely as a boilerplate reduction (may not be best practice?)
 */
class RoomImmediateTransProvider(private val db: RoomDatabase) : TransactionProvider {
    override suspend fun <R> runImmediateTransaction(block: suspend () -> R): R {
        return db.useWriterConnection { conn ->
            conn.withTransaction(type = Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
        }
    }
}