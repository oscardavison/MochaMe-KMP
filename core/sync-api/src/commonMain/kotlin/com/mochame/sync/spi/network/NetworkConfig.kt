package com.mochame.sync.spi.network

import org.koin.core.annotation.Single

@Single
data class NetworkConfig(
    val host: String = "relay.mochame.me",
    val port: Int = 443,
    val isSecure: Boolean = true,
    val groupId: String = "frappe"
)