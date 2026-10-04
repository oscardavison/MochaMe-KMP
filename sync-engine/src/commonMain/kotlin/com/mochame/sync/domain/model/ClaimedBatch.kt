package com.mochame.sync.domain.model

data class ClaimedBatch(
    val batchId: Long,
    val intents: List<SyncIntent>
)