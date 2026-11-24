package dev.handypvz.wear

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("handypvz")

data class WearStoredCode(val code: String, val epochDay: Long)

object WearCodeStore {
    private val KEY_CODE = stringPreferencesKey("code")
    private val KEY_DATE = longPreferencesKey("date")

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

    suspend fun save(context: Context, code: String, epochDay: Long) {
        context.dataStore.edit {
            it[KEY_CODE] = code
            it[KEY_DATE] = epochDay
        }
    }
}
