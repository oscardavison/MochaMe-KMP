package com.mochame.node.managers

import com.mochame.sync.spi.node.IdGenerator
import org.koin.core.annotation.Single
import kotlin.uuid.Uuid

@Single(binds = [IdGenerator::class])
class DefaultIdGenerator : IdGenerator {
    override fun nextId(): String = Uuid.random().toString()
}
