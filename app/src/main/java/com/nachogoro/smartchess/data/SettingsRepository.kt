package com.nachogoro.smartchess.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "smart_chess_settings")

/**
 * Persisted user settings.
 *
 * [moduleSettings] holds one saved form per engine or remote platform, keyed by module name, so
 * that values the user has already supplied - an API token, a difficulty level - are pre-filled
 * the next time they start a game with that module instead of being retyped.
 */
data class AppSettings(
    val playerName: String = "",
    val moduleSettings: Map<String, Map<String, String>> = emptyMap(),
    /** Whether the opponent's moves are read out loud as well as shown. */
    val announceOpponentMoves: Boolean = true,
) {
    fun settingsFor(moduleName: String): Map<String, String> = moduleSettings[moduleName].orEmpty()

    fun withModuleSettings(moduleName: String, values: Map<String, String>): AppSettings =
        copy(moduleSettings = moduleSettings + (moduleName to values))
}

class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            playerName = prefs[KEY_PLAYER_NAME].orEmpty(),
            moduleSettings = decodeModuleSettings(prefs[KEY_MODULE_SETTINGS]),
            announceOpponentMoves = prefs[KEY_ANNOUNCE_OPPONENT_MOVES] ?: true,
        )
    }

    suspend fun setPlayerName(name: String) {
        dataStore.edit { it[KEY_PLAYER_NAME] = name }
    }

    suspend fun setAnnounceOpponentMoves(enabled: Boolean) {
        dataStore.edit { it[KEY_ANNOUNCE_OPPONENT_MOVES] = enabled }
    }

    /**
     * Stores the values a user entered for one module's setup form.
     *
     * Sensitive fields are included: they are needed to avoid re-prompting, and DataStore lives in
     * the app's private storage. They are never written to the game history.
     */
    suspend fun saveModuleSettings(moduleName: String, values: Map<String, String>) {
        dataStore.edit { prefs ->
            val current = decodeModuleSettings(prefs[KEY_MODULE_SETTINGS]).toMutableMap()
            current[moduleName] = values
            prefs[KEY_MODULE_SETTINGS] = encodeModuleSettings(current)
        }
    }

    private fun decodeModuleSettings(raw: String?): Map<String, Map<String, String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            root.keys().asSequence().associateWith { module ->
                val inner = root.getJSONObject(module)
                inner.keys().asSequence().associateWith { key -> inner.getString(key) }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encodeModuleSettings(value: Map<String, Map<String, String>>): String {
        val root = JSONObject()
        for ((module, values) in value) {
            root.put(module, JSONObject(values as Map<*, *>))
        }
        return root.toString()
    }

    private companion object {
        val KEY_PLAYER_NAME = stringPreferencesKey("player_name")
        val KEY_MODULE_SETTINGS = stringPreferencesKey("module_settings")
        val KEY_ANNOUNCE_OPPONENT_MOVES = booleanPreferencesKey("announce_opponent_moves")
    }
}
