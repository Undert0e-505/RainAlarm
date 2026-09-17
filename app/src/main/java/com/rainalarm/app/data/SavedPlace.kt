package com.rainalarm.app.data

import kotlinx.serialization.Serializable
import java.util.Locale

const val CURRENT_LOCATION_ID = "current-location"
const val LONDON_DEFAULT_ID = "london-default"
const val PLACE_STORE_VERSION = 3

fun stablePlaceId(latitude: Double, longitude: Double, current: Boolean = false): String {
    if (current) return CURRENT_LOCATION_ID
    return "place-" + String.format(
        Locale.ROOT,
        "%.5f-%.5f",
        latitude,
        longitude,
    ).replace("-", "m").replace(".", "p")
}

fun forecastSelectionKey(place: SavedPlace): String =
    "${place.id}:${place.latitude}:${place.longitude}"

@Serializable
data class SavedPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val isCurrentLocation: Boolean = false,
    val pinned: Boolean = false,
    val id: String = stablePlaceId(latitude, longitude, isCurrentLocation),
) {
    init {
        require(id.isNotBlank()) { "Place ID must not be blank" }
        require(name.isNotBlank()) { "Place name must not be blank" }
        require(latitude.isFinite() && latitude in -85.05112878..85.05112878) {
            "Latitude is out of Web Mercator range"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude is out of range"
        }
    }
}

val DEFAULT_PLACE = SavedPlace(
    name = "London",
    latitude = 51.5074,
    longitude = -0.1278,
    pinned = true,
    id = LONDON_DEFAULT_ID,
)

/** UI-only selection token; its placeholder coordinates must never be used for a forecast. */
val CURRENT_LOCATION_SELECTION = SavedPlace(
    name = "Current location", latitude = 0.0, longitude = 0.0,
    isCurrentLocation = true, id = CURRENT_LOCATION_ID,
)

@Serializable
data class PlaceCollection(
    val version: Int = PLACE_STORE_VERSION,
    val places: List<SavedPlace> = listOf(DEFAULT_PLACE),
    val selectedId: String = DEFAULT_PLACE.id,
) {
    val selected: SavedPlace
        get() = if (selectedId == CURRENT_LOCATION_ID) CURRENT_LOCATION_SELECTION
            else places.firstOrNull { it.id == selectedId } ?: places.firstOrNull() ?: DEFAULT_PLACE
}

object PlaceCollectionRules {
    fun freshInstall(): PlaceCollection = PlaceCollection(selectedId = CURRENT_LOCATION_ID)
    fun isFreshStore(hasCollection: Boolean, hasDefault: Boolean, hasLegacySelection: Boolean): Boolean =
        !hasCollection && !hasDefault && !hasLegacySelection

    fun resolveDefaultId(collection: PlaceCollection, requestedId: String?): String {
        val normalized = normalize(collection)
        return when {
            requestedId == CURRENT_LOCATION_ID -> CURRENT_LOCATION_ID
            requestedId != null && normalized.places.any { it.id == requestedId } -> requestedId
            normalized.places.any { it.id == DEFAULT_PLACE.id } -> DEFAULT_PLACE.id
            else -> normalized.places.first().id
        }
    }

    fun normalize(collection: PlaceCollection): PlaceCollection {
        val unique = LinkedHashMap<String, SavedPlace>()
        collection.places.filterNot { it.isCurrentLocation || it.id == CURRENT_LOCATION_ID }
            .forEach { place -> unique.putIfAbsent(place.id, place) }
        val places = unique.values.toList().ifEmpty { listOf(DEFAULT_PLACE) }
        val selected = collection.selectedId.takeIf { id -> id == CURRENT_LOCATION_ID || places.any { it.id == id } }
            ?: places.firstOrNull { it.pinned }?.id
            ?: places.first().id
        return PlaceCollection(
            version = PLACE_STORE_VERSION,
            places = places.sortedByDescending { it.pinned },
            selectedId = selected,
        )
    }

    fun migrateLegacy(
        name: String?,
        latitude: Double?,
        longitude: Double?,
        isCurrent: Boolean?,
    ): PlaceCollection {
        val legacy = runCatching {
            if (latitude == null || longitude == null) return@runCatching null
            SavedPlace(
                name = name?.takeIf { it.isNotBlank() } ?: "Saved place",
                latitude = latitude,
                longitude = longitude,
                isCurrentLocation = isCurrent == true,
            )
        }.getOrNull()
        return normalize(when {
            legacy == null -> PlaceCollection()
            legacy.isCurrentLocation -> PlaceCollection(selectedId = CURRENT_LOCATION_ID)
            else -> PlaceCollection(places = listOf(legacy), selectedId = legacy.id)
        })
    }

    fun select(collection: PlaceCollection, id: String): PlaceCollection {
        val normalized = normalize(collection)
        return if (id == CURRENT_LOCATION_ID || normalized.places.any { it.id == id }) normalized.copy(selectedId = id)
        else normalized
    }

    fun upsert(
        collection: PlaceCollection,
        incoming: SavedPlace,
        select: Boolean = true,
    ): PlaceCollection {
        val normalized = normalize(collection)
        if (incoming.isCurrentLocation || incoming.id == CURRENT_LOCATION_ID) {
            return if (select) normalized.copy(selectedId = CURRENT_LOCATION_ID) else normalized
        }
        val duplicate = normalized.places.firstOrNull {
            it.id == incoming.id ||
                (!incoming.isCurrentLocation &&
                    kotlin.math.abs(it.latitude - incoming.latitude) < 0.00001 &&
                    kotlin.math.abs(it.longitude - incoming.longitude) < 0.00001)
        }
        val place = if (duplicate == null) incoming else incoming.copy(
            id = duplicate.id,
            pinned = incoming.pinned || duplicate.pinned,
        )
        val next = normalized.places.filterNot {
            it.id == place.id || (place.isCurrentLocation && it.isCurrentLocation)
        } + place
        return normalize(
            PlaceCollection(
                places = next,
                selectedId = if (select) place.id else normalized.selectedId,
            ),
        )
    }

    fun delete(collection: PlaceCollection, id: String): PlaceCollection {
        val normalized = normalize(collection)
        if (id == CURRENT_LOCATION_ID) return normalize(normalized.copy(selectedId = DEFAULT_PLACE.id))
        val remaining = normalized.places.filterNot { it.id == id }
        if (remaining.isEmpty()) return PlaceCollection()
        val selected = if (normalized.selectedId == id) {
            remaining.firstOrNull { it.pinned }?.id ?: remaining.first().id
        } else normalized.selectedId
        return normalize(PlaceCollection(places = remaining, selectedId = selected))
    }

    fun setPinned(collection: PlaceCollection, id: String, pinned: Boolean): PlaceCollection {
        val normalized = normalize(collection)
        return normalize(
            normalized.copy(
                places = normalized.places.map {
                    if (it.id == id) it.copy(pinned = pinned) else it
                },
            ),
        )
    }
}
