package com.mochame.sync.utils

import com.mochame.sync.domain.model.DecodeContext
import com.mochame.sync.domain.model.SyncIntent

internal fun SyncIntent.deriveContext() = DecodeContext(
    featureSchemaVersion,
    candidateKey,
    hlc,
    operation,
    overflowBlobId,
    changedMask
)