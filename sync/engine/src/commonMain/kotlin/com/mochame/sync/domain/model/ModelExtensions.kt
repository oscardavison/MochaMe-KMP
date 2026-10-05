package com.mochame.sync.domain.model

import com.mochame.sync.spi.models.DecodeContext

internal fun SyncIntent.deriveContext() = DecodeContext(
    featureSchemaVersion,
    candidateKey,
    hlc,
    operation,
    overflowBlobId,
    changedMask
)