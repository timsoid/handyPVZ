package dev.handypvz

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore("handypvz")

data class StoredCode(val code: String, val epochDay: Long)

object CodeStore {
    private val KEY_CODE = stringPreferencesKey("code")
    private val KEY_DATE = longPreferencesKey("date")
    private val KEY_HAS_ORDER = booleanPreferencesKey("has_order")

    suspend fun get(context: Context): StoredCode? {
        val prefs = context.dataStore.data.first()
        val code = prefs[KEY_CODE] ?: return null
        val date = prefs[KEY_DATE] ?: return null
        return StoredCode(code, date)
    }

    suspend fun getHasOrder(context: Context): Boolean {
        return context.dataStore.data.first()[KEY_HAS_ORDER] ?: false
    }

    suspend fun saveCode(context: Context, code: String, epochDay: Long) {
        context.dataStore.edit {
            it[KEY_CODE] = code
            it[KEY_DATE] = epochDay
            it[KEY_HAS_ORDER] = true
        }
    }

    suspend fun clearOrder(context: Context) {
        context.dataStore.edit {
            it[KEY_HAS_ORDER] = false
        }
    }
}
