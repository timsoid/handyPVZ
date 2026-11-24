package dev.handypvz

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.ZoneId

class CodeSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = sync()

    private suspend fun sync(): Result {
        val today = LocalDate.now(MSK).toEpochDay()
        val stored = CodeStore.get(applicationContext)
        val hasOrder = CodeStore.getHasOrder(applicationContext)
        if (hasOrder && stored != null && stored.epochDay == today) {
            Log.d(TAG, "skip: active order with today's code")
            return Result.success()
        }

        return when (val result = OzonFetcher(applicationContext).fetch()) {
            is FetchResult.Success -> {
                val appeared = !hasOrder
                CodeStore.saveCode(applicationContext, result.code, today)
                WearSync.push(applicationContext, result.code, today)
                if (appeared) Notifier.orderReady(applicationContext)
                Log.d(TAG, "fetched code: ${result.code} appeared=$appeared")
                Result.success()
            }
            is FetchResult.SessionExpired -> {
                Log.d(TAG, "session expired")
                Notifier.sessionExpired(applicationContext)
                Result.failure()
            }
            is FetchResult.NetworkError -> {
                Log.d(TAG, "network error")
                Notifier.fetchFailed(applicationContext)
                Result.failure()
            }
            is FetchResult.NotFound -> {
                Log.d(TAG, "no code — no orders for pickup")
                CodeStore.clearOrder(applicationContext)
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "CodeSyncWorker"
        private val MSK: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
