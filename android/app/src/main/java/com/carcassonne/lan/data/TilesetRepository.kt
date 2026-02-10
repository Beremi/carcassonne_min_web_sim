package com.carcassonne.lan.data

import android.content.Context
import com.carcassonne.lan.model.TilesetPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class TilesetRepository(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun loadTileset(): TilesetPayload = withContext(Dispatchers.IO) {
        context.assets.open("data/carcassonne_base_A-X.json").use { stream ->
            val text = stream.bufferedReader().use { it.readText() }
            json.decodeFromString<TilesetPayload>(text)
        }
    }
}
