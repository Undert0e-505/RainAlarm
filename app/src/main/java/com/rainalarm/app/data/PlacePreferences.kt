package com.rainalarm.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.placeDataStore by preferencesDataStore(name = "places")

class PlacePreferences(private val context: Context) {
    private object Keys {
        val collection = stringPreferencesKey("place_collection_v2")
        val name = stringPreferencesKey("selected_name")
        val latitude = doublePreferencesKey("selected_latitude")
        val longitude = doublePreferencesKey("selected_longitude")
        val isCurrent = booleanPreferencesKey("selected_is_current")
        val defaultStartupId = stringPreferencesKey("default_startup_place_id")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val collection: Flow<PlaceCollection> = context.placeDataStore.data.map(::decode)
    val selected: Flow<SavedPlace> = collection.map { it.selected }
    val defaultStartupId: Flow<String> = context.placeDataStore.data.map { preferences ->
        val collection = decode(preferences)
        PlaceCollectionRules.resolveDefaultId(collection, preferences[Keys.defaultStartupId] ?: collection.selectedId)
    }

    suspend fun applyStartupDefault() {
        context.placeDataStore.edit { preferences ->
            val collection = decode(preferences)
            val id = PlaceCollectionRules.resolveDefaultId(collection, preferences[Keys.defaultStartupId] ?: collection.selectedId)
            preferences[Keys.collection] = json.encodeToString(PlaceCollectionRules.select(collection, id))
            preferences[Keys.defaultStartupId] = id
        }
    }

    suspend fun setDefaultStartupId(id: String) {
        context.placeDataStore.edit { preferences ->
            preferences[Keys.defaultStartupId] = PlaceCollectionRules.resolveDefaultId(decode(preferences), id)
        }
    }

    /** Returns true only for a store with neither a collection nor any legacy selection/default. */
    suspend fun ensureMigrated(): Boolean {
        var freshInstall = false
        context.placeDataStore.edit { preferences ->
            freshInstall = PlaceCollectionRules.isFreshStore(
                hasCollection = preferences[Keys.collection] != null,
                hasDefault = preferences[Keys.defaultStartupId] != null,
                hasLegacySelection = preferences[Keys.name] != null || preferences[Keys.latitude] != null ||
                    preferences[Keys.longitude] != null || preferences[Keys.isCurrent] != null,
            )
            val collection = if (freshInstall) PlaceCollectionRules.freshInstall() else decode(preferences)
            val normalized = json.encodeToString(collection)
            if (preferences[Keys.collection] != normalized) preferences[Keys.collection] = normalized
            if (freshInstall) preferences[Keys.defaultStartupId] = CURRENT_LOCATION_ID
            preferences.remove(Keys.name)
            preferences.remove(Keys.latitude)
            preferences.remove(Keys.longitude)
            preferences.remove(Keys.isCurrent)
        }
        return freshInstall
    }

    suspend fun select(place: SavedPlace) {
        upsert(place, select = true)
    }

    suspend fun select(id: String) {
        mutate { PlaceCollectionRules.select(it, id) }
    }

    suspend fun upsert(place: SavedPlace, select: Boolean = true) {
        mutate { PlaceCollectionRules.upsert(it, place, select) }
    }

    suspend fun delete(id: String) {
        mutate { PlaceCollectionRules.delete(it, id) }
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        mutate { PlaceCollectionRules.setPinned(it, id, pinned) }
    }

    private suspend fun mutate(transform: (PlaceCollection) -> PlaceCollection) {
        context.placeDataStore.edit { preferences ->
            val next = transform(decode(preferences))
            preferences[Keys.collection] = json.encodeToString(next)
            preferences[Keys.defaultStartupId]?.let { requested ->
                preferences[Keys.defaultStartupId] = PlaceCollectionRules.resolveDefaultId(next, requested)
            }
            preferences.remove(Keys.name)
            preferences.remove(Keys.latitude)
            preferences.remove(Keys.longitude)
            preferences.remove(Keys.isCurrent)
        }
    }

    private fun decode(preferences: Preferences): PlaceCollection {
        val stored = preferences[Keys.collection]
        if (stored != null) {
            return runCatching {
                PlaceCollectionRules.normalize(json.decodeFromString<PlaceCollection>(stored))
            }.getOrDefault(PlaceCollection())
        }
        if (preferences[Keys.name] == null && preferences[Keys.latitude] == null &&
            preferences[Keys.longitude] == null && preferences[Keys.isCurrent] == null &&
            preferences[Keys.defaultStartupId] == null) return PlaceCollectionRules.freshInstall()
        return PlaceCollectionRules.migrateLegacy(
            name = preferences[Keys.name],
            latitude = preferences[Keys.latitude],
            longitude = preferences[Keys.longitude],
            isCurrent = preferences[Keys.isCurrent],
        )
    }
}
