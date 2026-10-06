package com.mochame.sync.spi

interface TransactionProvider {
    suspend fun <R> runImmediateTransaction(block: suspend () -> R): R
}