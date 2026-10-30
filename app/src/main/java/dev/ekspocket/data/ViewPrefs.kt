package dev.ekspocket.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

enum class ViewMode { COMPACT, DETAILED, GRID }

/**
 * Small UI preferences: the chosen list view per resource kind, and the last namespace per
 * cluster. Remembers the chosen list view per resource kind (key like "apps/statefulsets").
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

    /** Last namespace picked on a cluster. Null means "all namespaces". Defaults to "default". */
    fun lastNamespace(clusterId: String): String? =
        when (val v = prefs.getString("ns:$clusterId", null)) {
            null -> "default"
            ALL_NAMESPACES -> null
            else -> v
        }

    fun setLastNamespace(clusterId: String, namespace: String?) {
        prefs.edit().putString("ns:$clusterId", namespace ?: ALL_NAMESPACES).apply()
    }

    private fun stored(kindKey: String): ViewMode =
        prefs.getString("view:$kindKey", null)
            ?.let { name -> ViewMode.entries.firstOrNull { it.name == name } }
            ?: ViewMode.DETAILED

    private companion object {
        const val ALL_NAMESPACES = "*"
    }
}
