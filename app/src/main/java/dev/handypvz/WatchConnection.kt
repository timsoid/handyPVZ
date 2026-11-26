package dev.handypvz

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WatchConnection {

    /** True when a Wear OS device is currently reachable through the Wearable network. */
    suspend fun isConnected(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            Tasks.await(Wearable.getNodeClient(context.applicationContext).connectedNodes).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }
}
