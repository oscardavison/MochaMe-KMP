package com.mochame.sync.spi.network

import org.koin.core.annotation.Single

@Single
data class NetworkConfig(
    val serverHost: String = "192.168.12.1", //"127.0.0.1"
    val serverPort: Int = 8080,
    val syncGroupId: String = "dev1"
)