package com.mochame.sync.spi.infrastructure

interface TransactionProvider {
    suspend fun <R> runImmediateTransaction(block: suspend () -> R): R
}