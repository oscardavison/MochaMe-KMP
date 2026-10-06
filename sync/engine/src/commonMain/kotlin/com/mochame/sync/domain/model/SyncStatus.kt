package com.mochame.sync.domain.model

enum class SyncStatus(val id: Int) {
    PENDING(1),
    SYNCING(2),
    SUCCESS(3),
    FAILED(4),
    RECEIVED(5),
    QUARANTINED(6);

    companion object {
        fun fromId(id: Int) = entries.find { it.id == id } ?: PENDING
    }
}