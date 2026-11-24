package dev.handypvz.adb

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

/** Pairs with the watch, connects over TLS and installs the embedded `wear_app.apk`. */
class WatchInstaller(context: Context) {

    private val app = context.applicationContext
    private val discovery = AdbDiscovery(app)

    sealed interface Step {
        data object Pairing : Step
        data object Connecting : Step
        data class Installing(val percent: Int) : Step
        data object Done : Step
    }

    class InstallException(message: String) : Exception(message)

    /**
     * @param host watch IP address
     * @param pairingPort pairing port, or null to skip pairing (device already paired)
     * @param code 6-digit pairing code (ignored when [pairingPort] is null)
     * @param connectPort connect port; when null it is discovered via mDNS
     */
    suspend fun install(
        host: String,
        pairingPort: Int?,
        code: String?,
        connectPort: Int?,
        onStep: (Step) -> Unit
    ) = withContext(Dispatchers.IO) {
        val manager = WatchAdbManager.get(app)
        try {
            if (pairingPort != null && !code.isNullOrBlank()) {
                onStep(Step.Pairing)
                try {
                    manager.pair(host, pairingPort, code)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.d(TAG, "pair failed", e)
                    throw InstallException(PAIR_FAILED)
                }
                // The pairing service disappears right after success, give adbd a moment
                kotlinx.coroutines.delay(800)
            }

            onStep(Step.Connecting)
            connect(manager, host, connectPort)

            onStep(Step.Installing(0))
            installApk(manager, onStep)
            onStep(Step.Done)
        } finally {
            runCatching { manager.disconnect() }
        }
    }

    private suspend fun connect(manager: WatchAdbManager, host: String, manualPort: Int?) {
        if (manualPort != null) {
            if (!tryConnect(manager, host, manualPort)) throw InstallException(CONNECT_FAILED_MANUAL)
            return
        }
        val ports = Channel<Int>(Channel.UNLIMITED)
        val handle = discovery.watch(AdbDiscovery.CONNECT) { h, p ->
            if (h == host) ports.trySend(p)
        }
        try {
            val connected = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                var ok = false
                for (port in ports) {
                    if (tryConnect(manager, host, port)) {
                        ok = true
                        break
                    }
                }
                ok
            } ?: false
            if (!connected) throw InstallException(CONNECT_PORT_NOT_FOUND)
        } finally {
            handle.stop()
            ports.close()
        }
    }

    private suspend fun tryConnect(manager: WatchAdbManager, host: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                manager.connect(host, port)
                manager.isConnected
            } catch (e: AdbPairingRequiredException) {
                throw InstallException(PAIRING_REQUIRED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "connect $host:$port failed: $e")
                false
            }
        }

    private suspend fun installApk(manager: WatchAdbManager, onStep: (Step) -> Unit) {
        val apk = File(app.cacheDir, "wear_app.apk")
        try {
            app.assets.open(ASSET).use { input -> apk.outputStream().use { input.copyTo(it) } }
        } catch (e: IOException) {
            throw InstallException(NO_APK)
        }
        val size = apk.length()

        withTimeout(INSTALL_TIMEOUT_MS) {
            val stream = manager.openStream("exec:cmd package install -t -r -S $size")
            val reply: String
            try {
                val out = stream.openOutputStream()
                val buf = ByteArray(32 * 1024)
                var sent = 0L
                var lastPercent = -1
                apk.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        val percent = (sent * 100 / size).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onStep(Step.Installing(percent))
                        }
                    }
                }
                out.flush()
                reply = stream.openInputStream().readBytes().toString(Charsets.UTF_8)
            } finally {
                runCatching { stream.close() }
                apk.delete()
            }
            Log.d(TAG, "install reply: $reply")
            if (!reply.contains("Success")) {
                val reason = reply.trim().lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
                throw InstallException("$INSTALL_FAILED $reason".trim())
            }
        }
    }

    companion object {
        private const val TAG = "WatchInstaller"
        private const val ASSET = "wear_app.apk"
        private const val DISCOVERY_TIMEOUT_MS = 15_000L
        private const val INSTALL_TIMEOUT_MS = 180_000L

        // User-facing messages (Russian UI)
        const val PAIR_FAILED =
            "Не удалось выполнить сопряжение. Проверьте IP, порт и код: они действуют, пока на часах открыт экран сопряжения."
        const val PAIRING_REQUIRED =
            "Часы не доверяют этому телефону. Введите код сопряжения и нажмите «Установить»."
        const val CONNECT_PORT_NOT_FOUND =
            "Не удалось автоматически найти порт подключения. Укажите его вручную в поле «Порт подключения» (он показан на экране «Отладка по Wi-Fi» / «Беспроводная отладка»)."
        const val CONNECT_FAILED_MANUAL =
            "Не удалось подключиться к часам по указанному порту. Проверьте порт на экране «Отладка по Wi-Fi» / «Беспроводная отладка»."
        const val NO_APK = "В этой сборке нет APK для часов."
        const val INSTALL_FAILED = "Установка не удалась:"
    }
}
