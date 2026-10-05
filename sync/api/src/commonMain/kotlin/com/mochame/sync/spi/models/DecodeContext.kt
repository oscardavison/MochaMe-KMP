package com.mochame.sync.spi.models

import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.models.HLC

data class DecodeContext(
    val featureSchemaVersion: Int,
    val candidateKey: Long,
    val hlc: HLC,
    val op: MutationOp,
    val overflowBlobId: String? = null,
    val changedMask: Long
)
