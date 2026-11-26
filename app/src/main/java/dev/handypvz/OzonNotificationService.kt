package dev.handypvz

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class OzonNotificationService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastFetchMs = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isOzonPackage(sbn.packageName)) return
        Log.d(TAG, "ozon notification from ${sbn.packageName}")
        if (System.currentTimeMillis() - lastFetchMs < DEBOUNCE_MS) {
            Log.d(TAG, "debounced")
            return
        }
        lastFetchMs = System.currentTimeMillis()
        scope.launch { fetchAndPush() }
    }

    private suspend fun fetchAndPush() {
        val today = LocalDate.now(MSK).toEpochDay()
        when (val result = OzonFetcher(applicationContext).fetch()) {
            is FetchResult.Success -> {
                val appeared = !CodeStore.getHasOrder(applicationContext)
                CodeStore.saveCode(applicationContext, result.code, today)
                WearSync.push(applicationContext, result.code, today)
                if (appeared) Notifier.orderReady(applicationContext)
                Log.d(TAG, "fetched code on notification")
            }
            is FetchResult.CodeUnavailable -> {
                if (CodeStore.markOzonUnavailable(applicationContext)) WearSync.pushSnapshot(applicationContext)
                Log.d(TAG, "code unavailable on notification")
            }
            is FetchResult.SessionExpired -> {
                Notifier.sessionExpired(applicationContext)
                Log.d(TAG, "session expired on notification")
            }
            is FetchResult.NetworkError -> {
                Log.d(TAG, "network error on notification")
            }
        }
    }

    private fun isOzonPackage(pkg: String): Boolean =
        pkg == OZON_APP || pkg == OZON_FRESH

    companion object {
        private const val TAG = "OzonNotification"
        private const val OZON_APP = "ru.ozon.app.android"
        private const val OZON_FRESH = "ru.ozon.fresh"
        private const val DEBOUNCE_MS = 60_000L
        private val MSK: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
