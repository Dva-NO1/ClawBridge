package com.clawbridge.mobile.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Resilient OkHttp DNS with DoH fallback (AliDNS 223.5.5.5 + Cloudflare 1.1.1.1).
 * Ensures instant resolution on China 5G cellular networks and Wi-Fi alike without VPN.
 */
object DoHDns : Dns {

    private data class CacheEntry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val json = Json { ignoreUnknownKeys = true }

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.let { entry ->
            if (entry.expiresAt > now) return entry.addresses
        }

        try {
            val sys = Dns.SYSTEM.lookup(hostname).filter { addr ->
                val hostAddr = addr.hostAddress ?: ""
                // Filter out Clash/TUN Fake-IP 198.18.x.x if any
                !hostAddr.startsWith("198.18.") && !hostAddr.startsWith("198.19.")
            }
            if (sys.isNotEmpty()) {
                cache[hostname] = CacheEntry(sys, now + 180_000L)
                return sys
            }
        } catch (_: UnknownHostException) {
            // Fall through to DoH
        }

        val dohResult = queryDoh(hostname)
        if (dohResult.isNotEmpty()) {
            cache[hostname] = CacheEntry(dohResult, now + 300_000L)
            return dohResult
        }

        throw UnknownHostException("Unable to resolve $hostname via system DNS or DoH")
    }

    private fun queryDoh(hostname: String): List<InetAddress> {
        val endpoints = listOf(
            "https://223.5.5.5/resolve?name=$hostname&type=1" to "dns.alidns.com",
            "https://1.1.1.1/dns-query?name=$hostname&type=A" to "cloudflare-dns.com",
        )
        for ((urlStr, hostHeader) in endpoints) {
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    requestMethod = "GET"
                    setRequestProperty("Host", hostHeader)
                    setRequestProperty("Accept", "application/dns-json")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = json.parseToJsonElement(body).jsonObject
                    val answers = root["Answer"]?.jsonArray ?: continue
                    val addrs = answers.mapNotNull { el ->
                        val obj = el.jsonObject
                        val type = obj["type"]?.jsonPrimitive?.content?.toIntOrNull()
                        val data = obj["data"]?.jsonPrimitive?.content
                        if (type == 1 && !data.isNullOrBlank()) {
                            runCatching { InetAddress.getByName(data) }.getOrNull()
                        } else null
                    }
                    if (addrs.isNotEmpty()) return addrs
                }
            } catch (_: Throwable) {
                // try next DoH provider
            }
        }
        return emptyList()
    }
}
