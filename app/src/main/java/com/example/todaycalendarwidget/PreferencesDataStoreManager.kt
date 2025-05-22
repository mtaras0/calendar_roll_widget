package com.example.todaycalendarwidget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesDataStoreManager(private val context: Context) {

    private object PreferencesKeys {
        val SELECTED_CALENDAR_IDS = stringSetPreferencesKey("selected_calendar_ids")
    }

    val selectedCalendarIdsFlow: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.SELECTED_CALENDAR_IDS] ?: emptySet()
        }

    suspend fun saveSelectedCalendarIds(calendarIds: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SELECTED_CALENDAR_IDS] = calendarIds
        }
    }
}
