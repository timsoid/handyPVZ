package dev.handypvz

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private val Context.dataStore by preferencesDataStore("handypvz")

data class StoredCode(val code: String, val epochDay: Long)
data class StoredWbCode(val qrData: String, val expiresAt: Long?)

data class CodeSnapshot(
    val sourceId: String,
    val revision: Long,
    val ozon: StoredCode?,
    val ozonHasOrder: Boolean,
    val ozonState: String,
    val wb: StoredWbCode?,
    val wbState: String
)

object CodeStore {
    private val KEY_CODE = stringPreferencesKey("code")
    private val KEY_DATE = longPreferencesKey("date")
    private val KEY_HAS_ORDER = booleanPreferencesKey("has_order")
    private val KEY_OZON_STATE = stringPreferencesKey("ozon_state")
    private val KEY_OZON_NO_ORDER_MIGRATED = booleanPreferencesKey("ozon_no_order_migrated")
    private val KEY_WB_QR = stringPreferencesKey("wb_qr")
    private val KEY_WB_EXPIRES_AT = longPreferencesKey("wb_expires_at")
    private val KEY_WB_STATE = stringPreferencesKey("wb_state")
    private val KEY_REVISION = longPreferencesKey("revision")
    private val KEY_SOURCE_ID = stringPreferencesKey("source_id")
    private val KEY_REQUIRED_WEAR = longPreferencesKey("required_wear_version")
    private val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")

    /** One atomic read; the existing Ozon keys are retained as the migration. */
    suspend fun snapshot(context: Context): CodeSnapshot {
        val prefs = context.dataStore.edit {
            if (it[KEY_SOURCE_ID] == null) it[KEY_SOURCE_ID] = UUID.randomUUID().toString()
            if (it[KEY_OZON_NO_ORDER_MIGRATED] != true) {
                // Older versions inferred "no order" from a missing code, which was ambiguous.
                if (it[KEY_OZON_STATE] == "no_order" ||
                    (it[KEY_OZON_STATE] == null && it[KEY_HAS_ORDER] == false)) {
                    it[KEY_OZON_STATE] = "unknown"
                    it[KEY_REVISION] = (it[KEY_REVISION] ?: 0) + 1
                }
                it[KEY_OZON_NO_ORDER_MIGRATED] = true
            }
            if (it[KEY_REQUIRED_WEAR] != BuildConfig.REQUIRED_WEAR_VERSION_CODE.toLong()) {
                it[KEY_REQUIRED_WEAR] = BuildConfig.REQUIRED_WEAR_VERSION_CODE.toLong()
                it[KEY_REVISION] = (it[KEY_REVISION] ?: 0) + 1
            }
        }
        val code = prefs[KEY_CODE]
        val date = prefs[KEY_DATE]
        val wbQr = prefs[KEY_WB_QR]
        return CodeSnapshot(
            sourceId = requireNotNull(prefs[KEY_SOURCE_ID]),
            revision = prefs[KEY_REVISION] ?: 0,
            ozon = if (code != null && date != null) StoredCode(code, date) else null,
            ozonHasOrder = prefs[KEY_HAS_ORDER] ?: false,
            ozonState = prefs[KEY_OZON_STATE] ?: when {
                prefs[KEY_HAS_ORDER] == true && code != null && date != null -> "ready"
                prefs.contains(KEY_HAS_ORDER) -> "no_order"
                else -> "unknown"
            },
            wb = wbQr?.let { StoredWbCode(it, prefs[KEY_WB_EXPIRES_AT]) },
            wbState = prefs[KEY_WB_STATE] ?: "unknown"
        )
    }

    suspend fun get(context: Context): StoredCode? {
        val prefs = context.dataStore.data.first()
        val code = prefs[KEY_CODE] ?: return null
        val date = prefs[KEY_DATE] ?: return null
        return StoredCode(code, date)
    }

    suspend fun getHasOrder(context: Context): Boolean {
        return context.dataStore.data.first()[KEY_HAS_ORDER] ?: false
    }

    /** Returns true once per day (MSK) to gate the update check; marks today as checked. */
    suspend fun shouldCheckToday(context: Context): Boolean {
        val today = LocalDate.now(ZoneId.of("Europe/Moscow")).toEpochDay()
        var due = false
        context.dataStore.edit {
            if ((it[KEY_LAST_UPDATE_CHECK] ?: 0) != today) {
                it[KEY_LAST_UPDATE_CHECK] = today
                due = true
            }
        }
        return due
    }

    suspend fun saveCode(context: Context, code: String, epochDay: Long) {
        context.dataStore.edit {
            it[KEY_CODE] = code
            it[KEY_DATE] = epochDay
            it[KEY_HAS_ORDER] = true
            it[KEY_OZON_STATE] = "ready"
            it[KEY_REVISION] = (it[KEY_REVISION] ?: 0) + 1
        }
    }

    suspend fun clearOrder(context: Context) {
        context.dataStore.edit {
            it[KEY_HAS_ORDER] = false
            it[KEY_OZON_STATE] = "no_order"
            it[KEY_REVISION] = (it[KEY_REVISION] ?: 0) + 1
        }
    }

    /** A missing code is not proof that no order exists. Keep any previously saved code. */
    suspend fun markOzonUnavailable(context: Context): Boolean {
        var changed = false
        context.dataStore.edit {
            val wasNoOrder = it[KEY_OZON_STATE] == "no_order" ||
                (it[KEY_OZON_STATE] == null && it[KEY_HAS_ORDER] == false)
            if (wasNoOrder) {
                it[KEY_OZON_STATE] = "unknown"
                it[KEY_REVISION] = (it[KEY_REVISION] ?: 0) + 1
                changed = true
            }
        }
        return changed
    }

    suspend fun saveWb(context: Context, qrData: String, expiresAt: Long? = null) {
        require(qrData.isNotEmpty())
        context.dataStore.edit {
            it[KEY_WB_QR] = qrData
            if (expiresAt == null) it.remove(KEY_WB_EXPIRES_AT) else it[KEY_WB_EXPIRES_AT] = expiresAt
            it[KEY_WB_STATE] = "ready"
            it[KEY_REVISION] = (it[KEY_REVISION] ?: 0) + 1
        }
    }

    /** Call only for a confirmed absence or logout, never for an ambiguous/network error. */
    suspend fun clearWb(context: Context, state: String) {
        require(state == "no_order" || state == "login_required")
        context.dataStore.edit {
            it.remove(KEY_WB_QR)
            it.remove(KEY_WB_EXPIRES_AT)
            it[KEY_WB_STATE] = state
            it[KEY_REVISION] = (it[KEY_REVISION] ?: 0) + 1
        }
    }
}
