package com.mochame.server.utils

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.mochame.logger.MochaLogWriter

object ServerLogger {
    val base = Logger(
        config = StaticConfig(
            minSeverity = Severity.Verbose,
            logWriterList = listOf(MochaLogWriter(minSeverity = Severity.Verbose))
        )
    )
}