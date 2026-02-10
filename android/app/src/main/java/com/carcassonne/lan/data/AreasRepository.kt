package com.carcassonne.lan.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AreaFeature(
    val type: String = "",
    val polygons: List<List<List<Double>>> = emptyList(),
)

@Serializable
data class AreaTile(
    val features: Map<String, AreaFeature> = emptyMap(),
)

@Serializable
data class AreaPayload(
    @SerialName("tiles")
    val tiles: Map<String, AreaTile> = emptyMap(),
)

class AreasRepository(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun loadAreas(): AreaPayload = withContext(Dispatchers.IO) {
        context.assets.open("data/carcassonne_base_A-X_areas.json").use { stream ->
            val text = stream.bufferedReader().use { it.readText() }
            json.decodeFromString<AreaPayload>(text)
        }
    }
}
