package com.mochame.app.entry.android

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ProcessLifecycleOwner
import com.mochame.annotations.AppMainScope
import com.mochame.app.ui.MochaComposeAppShell
import com.mochame.app.ui.di.initKoinCompose
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.plugin.module.dsl.modules

@Module
@ComponentScan("com.mochame.app.entry.android")
class AndroidLifecycleModule {

    @Single
    @AppMainScope
    fun provideAppMainScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineName("AppMainScope"))
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MochaComposeAppShell()
        }
    }
}

class MochaAndroidApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val koinApp = initKoinCompose {
            androidContext(this@MochaAndroidApp)
            modules(AndroidLifecycleModule::class)
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            koinApp.koin.get<AndroidAppLifecycleObserver>()
        )
    }
}