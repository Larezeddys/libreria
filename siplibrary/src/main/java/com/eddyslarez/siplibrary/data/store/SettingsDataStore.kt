package com.eddyslarez.siplibrary.data.store

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore for settings persistence
 * 
 * @author Eddys Larez
 */
class SettingsDataStore(private val application: Application) {
    
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sip_settings")
    
    private val IS_IN_BACKGROUND = booleanPreferencesKey("is_in_background")
    
    suspend fun setInBackgroundValue(value: Boolean) {
        application.dataStore.edit { preferences ->
            preferences[IS_IN_BACKGROUND] = value
        }
    }
    
    fun getInBackgroundFlow(): Flow<Boolean> {
        return application.dataStore.data.map { preferences ->
            preferences[IS_IN_BACKGROUND] ?: false
        }
    }
}