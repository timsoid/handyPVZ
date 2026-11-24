package dev.handypvz

import android.app.Application
import io.appmetrica.analytics.AppMetrica
import io.appmetrica.analytics.AppMetricaConfig

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannel(this)
        // The key comes from local.properties at build time; forks built without it send nothing
        if (BuildConfig.APPMETRICA_API_KEY.isNotBlank()) {
            AppMetrica.activate(
                this,
                AppMetricaConfig.newConfigBuilder(BuildConfig.APPMETRICA_API_KEY).build()
            )
        }
    }
}
