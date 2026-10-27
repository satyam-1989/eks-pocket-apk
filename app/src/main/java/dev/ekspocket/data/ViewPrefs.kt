package dev.ekspocket.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

enum class ViewMode { COMPACT, DETAILED, GRID }

/**
 * Remembers the chosen list view per resource kind (key like "apps/statefulsets").
 * Not sensitive, so it lives in plain SharedPreferences, separate from the encrypted profiles.
 */
class ViewPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ekspocket_ui", Context.MODE_PRIVATE)
    private val chosen = mutableStateMapOf<String, ViewMode>()

    fun get(kindKey: String): ViewMode = chosen[kindKey] ?: stored(kindKey)

    fun set(kindKey: String, mode: ViewMode) {
        chosen[kindKey] = mode
        prefs.edit().putString("view:$kindKey", mode.name).apply()
    }

    private fun stored(kindKey: String): ViewMode =
        prefs.getString("view:$kindKey", null)
            ?.let { name -> ViewMode.entries.firstOrNull { it.name == name } }
            ?: ViewMode.DETAILED
}
