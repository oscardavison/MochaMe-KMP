package com.mochame.app.entry.android

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mochame.annotations.AppMainScope
import com.mochame.sync.api.network.SyncTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

@Single
class AndroidAppLifecycleObserver(
    private val transport: SyncTransport,
    @AppMainScope private val coroutineScope: CoroutineScope
) : DefaultLifecycleObserver {

    override fun onStop(owner: LifecycleOwner) {
        coroutineScope.launch {
            transport.pause()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        coroutineScope.launch {
            transport.resume()
        }
    }
}