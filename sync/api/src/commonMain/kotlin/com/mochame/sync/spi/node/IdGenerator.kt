package com.mochame.sync.spi.node

interface IdGenerator {
    fun nextId(): String
}