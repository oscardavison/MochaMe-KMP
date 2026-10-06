package com.mochame.sync.api.network

import org.koin.core.annotation.Single

/**
 * Connection settings for the network client.
 *
 * @property host The remote endpoint hostname.
 * @property port The network port to connect to.
 * @property isSecure Whether WS/WSS routing is utilized.
 * @property groupId The group to synchronize data with.
 */
@Single
data class NetworkConfig(
    val host: String = "relay.mochame.me",
    val port: Int = 443,
    val isSecure: Boolean = true,
    val groupId: String = "frappe"
)
