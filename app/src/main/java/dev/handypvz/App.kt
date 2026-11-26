package dev.handypvz

import android.app.Application
import android.content.Context
import android.webkit.WebView
import io.appmetrica.analytics.AppMetrica
import io.appmetrica.analytics.AppMetricaConfig

class App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (getProcessName().endsWith(":wb")) {
            // This must run before anything in this process touches WebView or CookieManager.
            WebView.setDataDirectorySuffix("wb")
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (getProcessName().endsWith(":wb")) return
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
