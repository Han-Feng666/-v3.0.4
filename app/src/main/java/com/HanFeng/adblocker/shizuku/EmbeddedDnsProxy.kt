package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.util.Log
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RuleRepository
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

class EmbeddedDnsProxy(
    private val context: Context,
    private val port: Int
) {
    private val tag = "EmbeddedDnsProxy"
    private val running = AtomicBoolean(false)
    private var serverSocket: DatagramSocket? = null
    private var serverThread: Thread? = null
    private var blockedCount: Long = 0

    private val upstreamDnsServers = listOf(
        InetAddress.getByName("8.8.8.8"),
        InetAddress.getByName("1.1.1.1"),
        InetAddress.getByName("223.5.5.5")
    )
    private var currentUpstreamIndex = 0

    private val blockedDomains = mutableSetOf<String>()
    private var lastRuleRefresh = 0L
    private val ruleRefreshInterval = 30_000L
    private val dnsCache = object : LinkedHashMap<String, CacheEntry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>): Boolean = size > 512
    }
    private val dnsCacheLock = Any()
    private val dnsCacheTtlMs = 60_000L

    private data class CacheEntry(
        val addresses: List<ByteArray>,
        val expiresAt: Long
    )

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            serverSocket = DatagramSocket(port, InetAddress.getByName("0.0.0.0"))
            serverSocket?.soTimeout = 5000
            running.set(true)
            refreshBlockedDomains()
            serverThread = Thread({ serverLoop() }, "hf-dns-proxy")
            serverThread?.isDaemon = true
            serverThread?.start()
            LogRepository.append(context, "Embedded DNS proxy started on port $port")
            true
        } catch (e: Exception) {
            LogRepository.append(context, "Embedded DNS proxy failed to start: ${e.message}")
            false
        }
    }

    fun stop() {
        running.set(false)
        serverSocket?.close()
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
        LogRepository.append(context, "Embedded DNS proxy stopped")
    }

    fun isRunning(): Boolean = running.get()

    fun getBlockedCount(): Long = blockedCount

    private fun serverLoop() {
        val buf = ByteArray(4096)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                serverSocket?.receive(packet) ?: break
                if (!running.get()) break
                handleDnsQuery(packet)
            } catch (e: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(tag, "DNS proxy error", e)
                }
            }
        }
    }

    private fun handleDnsQuery(query: DatagramPacket) {
        val data = query.data
        val length = query.length
        if (length < 12) return

        try {
            val domain = parseDnsQuestion(data, length)
            if (domain == null) {
                forwardToUpstream(query, data, length)
                return
            }

            if (isBlockedDomain(domain)) {
                blockedCount++
                val response = buildBlockedResponse(data, length, domain)
                serverSocket?.send(DatagramPacket(response, response.size, query.address, query.port))
            } else {
                forwardToUpstream(query, data, length)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to handle DNS query", e)
        }
    }

    private fun parseDnsQuestion(data: ByteArray, length: Int): String? {
        val id = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if (!isQuery(flags)) return null
        val qdcount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        if (qdcount == 0) return null

        var pos = 12
        val domainParts = mutableListOf<String>()
        while (pos < length) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            if (len > 63) return null
            pos++
            if (pos + len > length) return null
            val part = String(data, pos, len, Charsets.UTF_8)
            domainParts.add(part)
            pos += len
        }
        if (pos + 4 > length) return null
        val qtype = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        if (qtype != 1 && qtype != 28 && qtype != 255) return null
        return domainParts.joinToString(".").lowercase()
    }

    private fun isQuery(flags: Int): Boolean = (flags and 0x8000) == 0

    private fun isBlockedDomain(domain: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRuleRefresh > ruleRefreshInterval) {
            refreshBlockedDomains()
        }
        val lowerDomain = domain.lowercase()
        if (RuleRepository.isWhitelistedDomain(lowerDomain)) return false
        if (RuleRepository.isSensitiveAuthDomain(lowerDomain)) return false
        if (RuleRepository.shouldProtectMediaTraffic(lowerDomain) || RuleRepository.shouldProtectBusinessTraffic(lowerDomain)) return false
        if (RuleRepository.isGameCoreDomain(lowerDomain) || RuleRepository.isSocialCoreDomain(lowerDomain)) return false
        val parts = lowerDomain.split(".")
        for (i in parts.indices) {
            val candidate = parts.drop(i).joinToString(".")
            if (blockedDomains.contains(candidate)) return true
            if (candidate.startsWith("*.")) {
                val wildcard = candidate.removePrefix("*.")
                if (blockedDomains.contains(wildcard)) return true
            }
        }
        return false
    }

    private fun refreshBlockedDomains() {
        val rules = RuleRepository.getRules(context)
        blockedDomains.clear()
        for (rule in rules) {
            val d = rule.domain.trim()
            if (d.isNotBlank() && !d.startsWith("#") && !d.startsWith("@@")) {
                blockedDomains.add(d.lowercase())
            }
        }
        lastRuleRefresh = System.currentTimeMillis()
    }

    private fun buildBlockedResponse(query: ByteArray, length: Int, domain: String): ByteArray {
        val qtype = if (length >= 4) {
            var p = 12
            while (p < length) {
                val l = query[p].toInt() and 0xFF
                if (l == 0) { p++; break }
                if (l > 63) break
                p += l + 1
            }
            if (p + 4 <= length) ((query[p].toInt() and 0xFF) shl 8) or (query[p + 1].toInt() and 0xFF) else 1
        } else 1
        val isIpv6 = qtype == 28
        val os = ByteArrayOutputStream()
        os.write(query, 0, 2)
        os.write(0x81)
        os.write(0x80)
        os.write(query, 4, 2)
        os.write(0x00)
        os.write(0x01)
        os.write(query, 8, 4)
        var pos = 12
        while (pos < length) {
            val len = query[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            pos++
            pos += len
        }
        if (pos + 4 <= length) {
            os.write(query, 12, pos - 12)
        }
        os.write(0xC0)
        os.write(0x0C)
        if (isIpv6) {
            os.write(0x00)
            os.write(0x1C)
            os.write(0x00)
            os.write(0x01)
            os.write(0x00)
            os.write(0x00)
            os.write(0x00)
            os.write(0x3C)
            os.write(0x00)
            os.write(0x10)
            for (i in 0 until 16) os.write(0x00)
        } else {
            os.write(0x00)
            os.write(0x01)
            os.write(0x00)
            os.write(0x01)
            os.write(0x00)
            os.write(0x00)
            os.write(0x00)
            os.write(0x3C)
            os.write(0x00)
            os.write(0x04)
            os.write(0x00)
            os.write(0x00)
            os.write(0x00)
            os.write(0x00)
        }
        return os.toByteArray()
    }

    private fun forwardToUpstream(query: DatagramPacket, data: ByteArray, length: Int) {
        val domain = parseDnsQuestion(data, length)
        if (domain != null) {
            val cached = getCachedResponse(domain, data)
            if (cached != null) {
                cached[0] = data[0]
                cached[1] = data[1]
                serverSocket?.send(DatagramPacket(cached, cached.size, query.address, query.port))
                return
            }
        }

        var attempts = 0
        val maxAttempts = upstreamDnsServers.size
        while (attempts < maxAttempts) {
            val server = upstreamDnsServers[(currentUpstreamIndex + attempts) % maxAttempts]
            try {
                val upstreamSocket = DatagramSocket()
                upstreamSocket.soTimeout = 3000
                val upstreamPacket = DatagramPacket(data, length, server, 53)
                upstreamSocket.send(upstreamPacket)
                val replyBuf = ByteArray(4096)
                val replyPacket = DatagramPacket(replyBuf, replyBuf.size)
                upstreamSocket.receive(replyPacket)
                val reply = replyPacket.data.copyOf(replyPacket.length)
                reply[0] = data[0]
                reply[1] = data[1]
                serverSocket?.send(DatagramPacket(reply, reply.size, query.address, query.port))
                upstreamSocket.close()
                currentUpstreamIndex = (currentUpstreamIndex + attempts) % maxAttempts
                if (domain != null) {
                    cacheResponse(domain, reply)
                }
                return
            } catch (e: Exception) {
                attempts++
            }
        }
    }

    private fun getCachedResponse(domain: String, query: ByteArray): ByteArray? {
        synchronized(dnsCacheLock) {
            val entry = dnsCache[domain] ?: return null
            if (System.currentTimeMillis() > entry.expiresAt) {
                dnsCache.remove(domain)
                return null
            }
            val qtype = ((query[query.size - 4].toInt() and 0xFF) shl 8) or (query[query.size - 3].toInt() and 0xFF)
            val qclass = ((query[query.size - 2].toInt() and 0xFF) shl 8) or (query[query.size - 1].toInt() and 0xFF)
            val response = buildResponseFromCache(query, query.size, domain, entry.addresses)
            response[0] = query[0]
            response[1] = query[1]
            return response
        }
    }

    private fun cacheResponse(domain: String, response: ByteArray) {
        val addresses = mutableListOf<ByteArray>()
        var pos = 12
        while (pos < response.size - 4) {
            val len = response[pos].toInt() and 0xFF
            if (len == 0) { pos++; break }
            if (len > 63) return
            pos++
            pos += len
        }
        pos += 4
        val ancount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        for (i in 0 until ancount) {
            if (pos + 2 > response.size) return
            if ((response[pos].toInt() and 0xFF) == 0xC0) {
                pos += 2
            } else {
                while (pos < response.size) {
                    val l = response[pos].toInt() and 0xFF
                    if (l == 0) { pos++; break }
                    if (l > 63) return
                    pos += l + 1
                }
            }
            if (pos + 10 > response.size) return
            val atype = ((response[pos].toInt() and 0xFF) shl 8) or (response[pos + 1].toInt() and 0xFF)
            val aclass = ((response[pos + 2].toInt() and 0xFF) shl 8) or (response[pos + 3].toInt() and 0xFF)
            val rdlength = ((response[pos + 8].toInt() and 0xFF) shl 8) or (response[pos + 9].toInt() and 0xFF)
            if (atype == 1 && aclass == 1) {
                if (pos + 10 + rdlength > response.size) return
                val addr = response.copyOfRange(pos + 10, pos + 10 + rdlength)
                addresses.add(addr)
            }
            pos += 10 + rdlength
        }
        if (addresses.isNotEmpty()) {
            synchronized(dnsCacheLock) {
                dnsCache[domain] = CacheEntry(addresses, System.currentTimeMillis() + dnsCacheTtlMs)
            }
        }
    }

    private fun buildResponseFromCache(query: ByteArray, length: Int, domain: String, addresses: List<ByteArray>): ByteArray {
        val os = ByteArrayOutputStream()
        os.write(query, 0, 2)
        os.write(0x81)
        os.write(0x80)
        os.write(query, 4, 2)
        os.write(0x00)
        os.write(addresses.size.coerceAtMost(0xFF))
        os.write(query, 8, 4)
        var pos = 12
        while (pos < length) {
            val len = query[pos].toInt() and 0xFF
            if (len == 0) { pos++; break }
            if (len > 63) return buildBlockedResponse(query, length, domain)
            pos++
            pos += len
        }
        os.write(query, 12, pos - 12)
        for (addr in addresses) {
            os.write(0xC0)
            os.write(0x0C)
            os.write(0x00)
            os.write(0x01)
            os.write(0x00)
            os.write(0x01)
            os.write(((System.currentTimeMillis() / 1000) shr 24).toInt() and 0xFF)
            os.write(((System.currentTimeMillis() / 1000) shr 16).toInt() and 0xFF)
            os.write(((System.currentTimeMillis() / 1000) shr 8).toInt() and 0xFF)
            os.write((System.currentTimeMillis() / 1000).toInt() and 0xFF)
            os.write(0x00)
            os.write(0x04)
            os.write(addr)
        }
        return os.toByteArray()
    }
}