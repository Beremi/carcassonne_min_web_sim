package com.carcassonne.lan.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

class LanPresenceBeacon {
    data class PresenceSignal(
        val address: String,
        val port: Int,
        val hostName: String,
    )

    @Serializable
    private data class PresencePacket(
        val app: String = APP_ID,
        val protocolVersion: Int = 1,
        val hostName: String,
        val port: Int,
        val tsEpochMs: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun send(port: Int, hostName: String) {
        val safeName = hostName.trim().ifBlank { return }
        val payload = PresencePacket(
            hostName = safeName,
            port = port,
            tsEpochMs = System.currentTimeMillis(),
        )
        val bytes = json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8)

        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (target in broadcastTargets()) {
                runCatching {
                    val packet = DatagramPacket(bytes, bytes.size, target, port)
                    socket.send(packet)
                }
            }
        }
    }

    fun openListener(port: Int): DatagramSocket {
        return DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress("0.0.0.0", port))
        }
    }

    fun receive(socket: DatagramSocket, timeoutMs: Int): PresenceSignal? {
        val buffer = ByteArray(512)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.soTimeout = timeoutMs
            socket.receive(packet)
            val body = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
            val parsed = json.decodeFromString<PresencePacket>(body)
            if (parsed.app != APP_ID || parsed.port !in 1..65535) return null
            PresenceSignal(
                address = packet.address?.hostAddress ?: return null,
                port = parsed.port,
                hostName = parsed.hostName.trim(),
            )
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun broadcastTargets(): Set<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList()).forEach ifaceLoop@{ iface ->
            val up = runCatching { iface.isUp }.getOrDefault(false)
            val loopback = runCatching { iface.isLoopback }.getOrDefault(false)
            if (!up || loopback) return@ifaceLoop
            runCatching { iface.interfaceAddresses.orEmpty() }
                .getOrDefault(emptyList())
                .forEach addrLoop@{ ia ->
                    val addr = ia.address
                    if (addr !is Inet4Address || addr.isLoopbackAddress) return@addrLoop
                    val broadcast = ia.broadcast
                    if (broadcast is Inet4Address) {
                        targets += broadcast
                    }
                }
        }
        runCatching { InetAddress.getByName("255.255.255.255") }
            .getOrNull()
            ?.let { targets += it }
        return targets
    }

    companion object {
        const val APP_ID = "carcassonne-lan-android"
    }
}
