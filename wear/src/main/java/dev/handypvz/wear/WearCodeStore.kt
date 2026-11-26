package dev.handypvz.wear

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

private val Context.dataStore by preferencesDataStore("handypvz")

data class WearStoredCode(val code: String, val epochDay: Long)
data class WearStoredWbCode(val qrData: String, val expiresAt: Long?)
data class WearSnapshot(
    val ozon: WearStoredCode?,
    val ozonState: String,
    val wb: WearStoredWbCode?,
    val wbState: String,
    val lastPhoneContact: Long?,
    val requiredWearVersionCode: Int?
)
data class IncomingSnapshot(
    val sourceId: String,
    val revision: Long,
    val requestNonce: String?,
    val ozon: WearStoredCode?,
    val ozonState: String,
    val wb: WearStoredWbCode?,
    val wbState: String,
    val requiredWearVersionCode: Int?
)

object WearCodeStore {
    private val KEY_CODE = stringPreferencesKey("code")
    private val KEY_DATE = longPreferencesKey("date")
    private val KEY_OZON_STATE = stringPreferencesKey("ozon_state")
    private val KEY_WB_QR = stringPreferencesKey("wb_qr")
    private val KEY_WB_EXPIRY = longPreferencesKey("wb_expiry")
    private val KEY_WB_STATE = stringPreferencesKey("wb_state")
    private val KEY_SOURCE = stringPreferencesKey("snapshot_source")
    private val KEY_REVISION = longPreferencesKey("snapshot_revision")
    private val KEY_PENDING_NONCE = stringPreferencesKey("snapshot_pending_nonce")
    private val KEY_LAST_CONTACT = longPreferencesKey("last_phone_contact")
    private val KEY_REQUIRED_WEAR = longPreferencesKey("required_wear_version")

    suspend fun get(context: Context): WearStoredCode? {
        val prefs = context.dataStore.data.first()
        val code = prefs[KEY_CODE] ?: return null
        val date = prefs[KEY_DATE] ?: return null
        return WearStoredCode(code, date)
    }

    fun getFlow(context: Context): Flow<WearStoredCode?> =
        context.dataStore.data.map { prefs ->
            val code = prefs[KEY_CODE] ?: return@map null
            val date = prefs[KEY_DATE] ?: return@map null
            WearStoredCode(code, date)
        }

    fun snapshotFlow(context: Context): Flow<WearSnapshot> = context.dataStore.data.map { prefs ->
        val code = prefs[KEY_CODE]
        val date = prefs[KEY_DATE]
        val ozon = if (code != null && date != null && code != "__NO_CODE__") WearStoredCode(code, date) else null
        val ozonState = prefs[KEY_OZON_STATE] ?: when {
            code == "__NO_CODE__" -> "no_order"
            ozon != null -> "ready"
            else -> "unknown"
        }
        val wb = prefs[KEY_WB_QR]?.let { WearStoredWbCode(it, prefs[KEY_WB_EXPIRY]) }
        WearSnapshot(ozon, ozonState, wb, prefs[KEY_WB_STATE] ?: "unknown", prefs[KEY_LAST_CONTACT],
            prefs[KEY_REQUIRED_WEAR]?.toInt())
    }.distinctUntilChanged()

    suspend fun save(context: Context, code: String, epochDay: Long, fromMessage: Boolean = false) {
        context.dataStore.edit {
            // Once v2 has been received, a delayed legacy message must not undo it.
            if (it[KEY_SOURCE] != null) return@edit
            if (fromMessage) it[KEY_LAST_CONTACT] = System.currentTimeMillis()
            it[KEY_CODE] = code
            it[KEY_DATE] = epochDay
            it[KEY_OZON_STATE] = if (code == "__NO_CODE__") "no_order" else "ready"
        }
    }

    suspend fun saveSnapshot(context: Context, snapshot: IncomingSnapshot) {
        context.dataStore.edit { prefs ->
            val currentSource = prefs[KEY_SOURCE]
            val pending = prefs[KEY_PENDING_NONCE]
            // A new sender must answer the watch's current request; an old delayed message
            // cannot change the authoritative phone after reinstall or re-pairing.
            if (currentSource != null && currentSource != snapshot.sourceId &&
                (pending == null || pending != snapshot.requestNonce)) return@edit
            prefs[KEY_LAST_CONTACT] = System.currentTimeMillis()
            if (pending != null && pending == snapshot.requestNonce) prefs.remove(KEY_PENDING_NONCE)
            if (!acceptSnapshot(currentSource, prefs[KEY_REVISION] ?: -1, pending, snapshot.sourceId,
                    snapshot.revision, snapshot.requestNonce)) return@edit
            prefs[KEY_SOURCE] = snapshot.sourceId
            prefs[KEY_REVISION] = snapshot.revision
            if (snapshot.requiredWearVersionCode != null) {
                prefs[KEY_REQUIRED_WEAR] = snapshot.requiredWearVersionCode.toLong()
            } else if (snapshot.sourceId != currentSource) {
                prefs.remove(KEY_REQUIRED_WEAR)
            }
            prefs[KEY_OZON_STATE] = snapshot.ozonState
            if (snapshot.ozon != null) {
                prefs[KEY_CODE] = snapshot.ozon.code
                prefs[KEY_DATE] = snapshot.ozon.epochDay
            } else {
                prefs.remove(KEY_CODE)
                prefs.remove(KEY_DATE)
            }
            prefs[KEY_WB_STATE] = snapshot.wbState
            if (snapshot.wb != null) {
                prefs[KEY_WB_QR] = snapshot.wb.qrData
                if (snapshot.wb.expiresAt != null) prefs[KEY_WB_EXPIRY] = snapshot.wb.expiresAt
                else prefs.remove(KEY_WB_EXPIRY)
            } else {
                prefs.remove(KEY_WB_QR)
                prefs.remove(KEY_WB_EXPIRY)
            }
        }
    }

    suspend fun registerRequest(context: Context, nonce: String) {
        context.dataStore.edit { it[KEY_PENDING_NONCE] = nonce }
    }

    suspend fun recordPhoneContact(context: Context) {
        context.dataStore.edit { it[KEY_LAST_CONTACT] = System.currentTimeMillis() }
    }
}
