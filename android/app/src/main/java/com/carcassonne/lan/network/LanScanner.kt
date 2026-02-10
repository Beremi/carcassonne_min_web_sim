package com.carcassonne.lan.network

import com.carcassonne.lan.model.PingResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

class LanScanner {
    data class DiscoveredHost(
        val address: String,
        val port: Int,
        val ping: PingResponse,
        val isSelf: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val fastClient = OkHttpClient.Builder()
        .connectTimeout(180, TimeUnit.MILLISECONDS)
        .readTimeout(220, TimeUnit.MILLISECONDS)
        .writeTimeout(220, TimeUnit.MILLISECONDS)
        .build()

    fun localIPv4Addresses(): Set<String> = queryLocalIPv4Addresses()

    suspend fun scan(port: Int): List<DiscoveredHost> = withContext(Dispatchers.IO) {
        val selfIps = queryLocalIPv4Addresses()
        val prefixes = selfIps
            .mapNotNull { ip ->
                val parts = ip.split('.')
                if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}" else null
            }
            .distinct()
            .take(3)

        val candidates = linkedSetOf<String>()
        for (prefix in prefixes) {
            for (last in 1..254) {
                candidates += "$prefix.$last"
            }
        }

        if (candidates.isEmpty()) {
            candidates += "127.0.0.1"
        } else {
            selfIps.forEach { candidates += it }
            candidates += "127.0.0.1"
        }

        val semaphore = Semaphore(64)
        coroutineScope {
            candidates.map { ip ->
                async {
                    semaphore.withPermit {
                        pingHost(ip, port, ip in selfIps || ip == "127.0.0.1")
                    }
                }
            }.awaitAll()
                .filterNotNull()
                .distinctBy { it.address }
                .sortedWith(compareBy<DiscoveredHost> { !it.isSelf }.thenBy { it.address })
        }
    }

    private fun pingHost(address: String, port: Int, isSelf: Boolean): DiscoveredHost? {
        val req = Request.Builder()
            .url("http://$address:$port/api/ping")
            .get()
            .build()

        return runCatching {
            fastClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val body = res.body?.string() ?: return@use null
                val ping = json.decodeFromString(PingResponse.serializer(), body)
                DiscoveredHost(address = address, port = port, ping = ping, isSelf = isSelf)
            }
        }.getOrNull()
    }

    private fun queryLocalIPv4Addresses(): Set<String> {
        val out = linkedSetOf<String>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }
            .getOrDefault(emptyList())
        for (iface in interfaces) {
            val isLoopback = runCatching { iface.isLoopback }.getOrDefault(false)
            if (isLoopback) continue
            val addrs = runCatching { iface.inetAddresses.toList() }.getOrDefault(emptyList())
            for (addr in addrs) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    out += addr.hostAddress ?: continue
                }
            }
        }
        return out
    }
}
