package dev.handypvz

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/** The main process owns these preferences; WB reads them through this private provider. */
class UiStateProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        when (method) {
            SET_PANEL -> {
                prefs.edit().putBoolean(PANEL, extras?.getBoolean(PANEL) ?: false).apply()
                context.contentResolver.notifyChange(uri(context), null)
            }
            RECORD_VERSION -> {
                val node = extras?.getString(NODE).orEmpty()
                val code = extras?.getInt(CODE, -1) ?: -1
                if (node.isNotBlank() && code >= 0) {
                    // A delayed old request must not overwrite a versioned response.
                    val knownSameNode = node == prefs.getString(NODE, null) && prefs.getInt(CODE, -1) > 0
                    if (code != 0 || !knownSameNode) {
                        prefs.edit().putString(NODE, node).putInt(CODE, code)
                            .putString(NAME, extras?.getString(NAME).orEmpty())
                            .putLong(TIME, System.currentTimeMillis()).apply()
                        context.contentResolver.notifyChange(uri(context), null)
                    }
                }
            }
        }
        return Bundle().apply {
            putBoolean(PANEL, prefs.getBoolean(PANEL, false))
            putInt(CODE, prefs.getInt(CODE, -1))
            putString(NAME, prefs.getString(NAME, null))
            putLong(TIME, prefs.getLong(TIME, 0))
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val GET = "get"
        const val SET_PANEL = "set_panel"
        const val RECORD_VERSION = "record_version"
        const val PANEL = "panel_collapsed"
        const val NODE = "node"
        const val CODE = "version_code"
        const val NAME = "version_name"
        const val TIME = "checked_at"
        private const val PREFS = "ui_state"

        fun uri(context: Context): Uri = Uri.parse("content://${context.packageName}.ui-state")
        fun read(context: Context): Bundle = context.contentResolver.call(uri(context), GET, null, null) ?: Bundle()
        fun record(context: Context, node: String, code: Int, name: String? = null) {
            val data = Bundle().apply { putString(NODE, node); putInt(CODE, code); putString(NAME, name) }
            context.contentResolver.call(uri(context), RECORD_VERSION, null, data)
        }
    }
}
