package com.carcassonne.lan

import com.carcassonne.lan.core.CarcassonneEngine
import com.carcassonne.lan.model.TileDef
import com.carcassonne.lan.model.TileEdge
import com.carcassonne.lan.model.TileFeature
import com.carcassonne.lan.model.TilesetPayload
import com.carcassonne.lan.network.HostGameManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HostGameManagerTest {
    private fun simpleTileset(): TilesetPayload {
        return TilesetPayload(
            tileCounts = mapOf("A" to 8),
            tiles = listOf(
                TileDef(
                    id = "A",
                    isStartTileType = true,
                    edges = mapOf(
                        "N" to TileEdge(primary = "field"),
                        "E" to TileEdge(primary = "field"),
                        "S" to TileEdge(primary = "field"),
                        "W" to TileEdge(primary = "field"),
                    ),
                    features = listOf(
                        TileFeature(
                            id = "field1",
                            type = "field",
                            ports = listOf("Nw", "Ne", "En", "Es", "Se", "Sw", "Ws", "Wn"),
                        )
                    ),
                )
            ),
        )
    }

    @Test
    fun joinThenReconnectBySameNameKeepsPlayerSlot() {
        val engine = CarcassonneEngine(simpleTileset())
        val manager = HostGameManager(engine = engine, random = Random(7), nowMs = { 1000L })

        manager.configureHostPlayer("Host1000")

        val blockedJoin = manager.joinOrReconnect("Remote2000")
        assertFalse(blockedJoin.ok)
        assertTrue(blockedJoin.error.orEmpty().contains("Invite required"))

        val invite = manager.sendInvite("Remote2000")
        assertTrue(invite.ok)
        val inviteId = invite.inviteId.orEmpty()
        assertTrue(inviteId.isNotBlank())

        val accepted = manager.respondInvite(inviteId, "accept")
        assertTrue(accepted.ok)
        assertEquals("accepted", accepted.status)

        val firstJoin = manager.joinOrReconnect("Remote2000")
        assertTrue(firstJoin.ok)
        assertEquals(2, firstJoin.player)
        val firstToken = firstJoin.token
        assertNotNull(firstToken)

        val reconnect = manager.joinOrReconnect("Remote2000")
        assertTrue(reconnect.ok)
        assertEquals(2, reconnect.player)
        assertNotEquals(firstToken, reconnect.token)

        val poll = manager.poll(reconnect.token.orEmpty())
        assertTrue(poll.ok)
        assertEquals("Remote2000", poll.match?.players?.get(2)?.name)
    }
}
