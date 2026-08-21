package com.HanFeng.core.network

import android.system.OsConstants
import com.HanFeng.data.RuleRepository
import com.HanFeng.model.PacketInfo
import java.net.InetAddress

object EncryptedDnsBlocker {
    enum class Reason {
        DOT_PORT,
        DOQ_PORT,
        KNOWN_DNS_IP,
        KNOWN_DNS_SNI,
        HTTPDNS_HOST,
        DNS_LIKE_QUIC
    }

    data class ClientHelloMetadata(
        val sniHost: String?,
        val alpnProtocols: List<String>
    )

    data class Decision(
        val blocked: Boolean,
        val reason: Reason?,
        val matchedHost: String? = null
    )

    private data class IpNetwork(val address: ByteArray, val prefixLength: Int)

    private val knownEncryptedDnsHosts = setOf(
        "dns.google",
        "dns.google.com",
        "cloudflare-dns.com",
        "one.one.one.one",
        "1dot1dot1dot1.cloudflare-dns.com",
        "mozilla.cloudflare-dns.com",
        "chrome.cloudflare-dns.com",
        "security.cloudflare-dns.com",
        "dns.quad9.net",
        "dns10.quad9.net",
        "dns11.quad9.net",
        "dns.adguard-dns.com",
        "dns.adguard.com",
        "unfiltered.adguard-dns.com",
        "family.adguard-dns.com",
        "dns.nextdns.io",
        "doh.pub",
        "dot.pub",
        "doq.pub",
        "dns.alidns.com",
        "httpdns.aliyun.com",
        "httpdns.alicdn.com",
        "httpdns-sc.aliyuncs.com",
        "httpdns-cn.aliyuncs.com",
        "httpdns-api.aliyuncs.com",
        "httpdns.m.aliyuncs.com",
        "dns.weixin.qq.com",
        "dns.weixin.qq.com.cn",
        "httpdns.weixin.qq.com",
        "httpdns.qq.com",
        "httpdns.tencentyun.com",
        "httpdns.tencent-cloud.com",
        "httpdns.baidu.com",
        "doh.baidu.com",
        "doh.controld.com",
        "freedns.controld.com",
        "doh.mullvad.net",
        "adblock.doh.mullvad.net",
        "doh.libredns.gr",
        "doh.blahdns.com",
        "doh.cira.ca",
        "private.canadianshield.cira.ca",
        "doh.dns.sb",
        "doh.dnssb.net",
        "dot.dns.sb",
        "doh.opendns.com",
        "doh.umbrella.com",
        "doh.cleanbrowsing.org",
        "security-filter-dns.cleanbrowsing.org",
        "doh.dnswarden.com",
        "dns.dnswarden.com",
        "doh.appliedprivacy.net",
        "doh.ffmuc.net",
        "dot.ffmuc.net",
        "dns0.eu",
        "zero.dns0.eu",
        "doh.tiar.app",
        "dot.tiar.app",
        "jp.tiar.app",
        "doh.crypto.sx",
        "doh.apad.pro",
        "dot.apad.pro",
        "sky.rethinkdns.com",
        "max.rethinkdns.com",
        "dns.digitale-gesellschaft.ch",
        "doh.digitale-gesellschaft.ch",
        "anycast.censurfridns.dk",
        "unicast.censurfridns.dk",
        "dnsforge.de",
        "doh.securedns.eu",
        "dot.securedns.eu",
        "doh.flix.is",
        "doh.snopyta.org",
        "doh.dns.neutopia.org",
        "doh.ibk.ru",
        "doh.centraleu.pi-dns.com",
        "doh.li",
        "doh.360.cn",
        "doh.360safe.com",
        "doh.qq.com",
        "doh.tencent.com",
        "doh.aliyun.com",
        "doh.taobao.com",
        "doh.huawei.com",
        "doh.hicloud.com",
        "doh.xiaomi.com",
        "doh.mi.com",
        "doh.oppo.com",
        "doh.heytap.com",
        "doh.vivo.com",
        "doh.vivo.com.cn",
        "doh.honor.com",
        "doh.samsung.com",
        "doh.apple.com",
        "doh.icloud.com",
        "doh.mask.icloud.com",
        "doh.mask-h2.icloud.com",
        "doh.apple-dns.net",
        "dns.ess.apple.com",
        "doh.cellular.apple.com",
        "doh.public.dns.iij.jp",
        "dns.controld.com",
        "resolver-b.privatelink.apple-dns.net",
        "httpdns.micloud.xiaomi.net",
        "httpdns.mi.com",
        "httpdns.miui.com",
        "httpdns.oppomobile.com",
        "httpdns.heytap.com",
        "httpdns.vivo.com.cn",
        "httpdns.vivoglobal.com",
        "httpdns.hicloud.com",
        "httpdns.honor.com",
        "base.dns.mullvad.net",
        "adblock.dns.mullvad.net",
        "dns.mullvad.net",
        "doh.dns.google",
        "dot.dns.google",
        "doh.alidns.com",
        "dot.alidns.com",
        "doh.quad9.net",
        "dot.quad9.net",
        "doh.dns.adguard.com",
        "doh.family.adguard.com",
        "doh.unfiltered.adguard.com",
        "doh.family.adguard-dns.com",
        "doh.unfiltered.adguard-dns.com",
        "doh.familyshield.opendns.com",
        "doh.cleanbrowsing.org",
        "dot.cleanbrowsing.org",
        "family-filter-dns.cleanbrowsing.org",
        "adult-filter-dns.cleanbrowsing.org",
        "dns.cleanbrowsing.org",
        "doh.dns.sb",
        "dns.sb",
        "dns.dns.sb"
    )

    private val knownEncryptedDnsNetworks = listOf(
        "1.1.1.1/32",
        "1.0.0.1/32",
        "8.8.8.8/32",
        "8.8.4.4/32",
        "9.9.9.9/32",
        "149.112.112.112/32",
        "94.140.14.14/32",
        "94.140.15.15/32",
        "45.90.28.0/24",
        "45.90.30.0/24",
        "223.5.5.5/32",
        "223.6.6.6/32",
        "119.29.29.29/32",
        "180.76.76.76/32",
        "208.67.222.222/32",
        "208.67.220.220/32",
        "185.228.168.168/32",
        "185.228.169.168/32",
        "76.76.2.0/24",
        "76.76.10.0/24",
        "116.203.28.177/32",
        "116.203.70.156/32",
        "78.46.244.143/32",
        "193.110.157.135/32",
        "185.222.222.222/32",
        "185.184.222.222/32",
        "45.67.219.204/32",
        "146.185.176.36/32",
        "46.227.200.0/24",
        "46.227.204.0/24",
        "2400:3200::1/128",
        "2400:3200:baba::1/128",
        "2606:4700:4700::1111/128",
        "2606:4700:4700::1001/128",
        "2001:4860:4860::8888/128",
        "2001:4860:4860::8844/128",
        "2620:fe::fe/128",
        "2620:fe::9/128",
        "2620:119:35::35/128",
        "2620:119:53::53/128",
        "2a0d:2a00:1::2/128",
        "2a0d:2a00:2::2/128",
        "2a07:a8c1::1337/128",
        "2a07:a8c0::1337/128",
        "2a09::0/128",
        "2a09::1/128",
        "2a01:4f8:c2c:123f::1/128",
        "2a01:4f9:c010:43ce::1/128",
        "2001:67c:28a4::1337/128",
        "2001:67c:28a4::1338/128",
        "101.101.101.101/32",
        "101.102.103.104/32",
        "77.88.8.8/32",
        "77.88.8.1/32",
        "84.200.69.80/32",
        "84.200.70.40/32",
        "37.235.1.174/32",
        "37.235.1.177/32",
        "91.239.100.100/32",
        "89.233.43.71/32",
        "74.82.42.42/32",
        "109.69.8.51/32",
        "156.154.70.1/32",
        "156.154.71.1/32",
        "205.171.3.65/32",
        "205.171.2.65/32",
        "2001:1608:10:25::1c04:b12f/128",
        "2001:1620:2779:1::10/128",
        "2001:1620:2779:2::10/128",
        "2a02:2970::1/128",
        "2a02:2970::2/128",
        "2a00:5a60::ad1:0ff/128",
        "2a00:5a60::ad2:0ff/128",
        "2001:470:20::2/128",
        "2a01:3a8:53::1/128",
        "2a01:3a8:53::2/128",
        "2001:678:4::1/128",
        "2001:678:4::2/128",
        "2a02:2990:4000::1/128",
        "2a02:2990:4000::2/128",
        "2a02:2990:4000::3/128",
        "2a02:2990:4000::4/128",
        "2a0d:2a00:1::2/128",
        "2a0d:2a00:2::2/128",
        "2a07:a8c1::1337/128",
        "2a07:a8c0::1337/128",
        "2a09::0/128",
        "2a09::1/128",
        "2a01:4f8:c2c:123f::1/128",
        "2a01:4f9:c010:43ce::1/128",
        "2001:67c:28a4::1337/128",
        "2001:67c:28a4::1338/128"
    ).mapNotNull(::parseNetwork)

    fun isKnownEncryptedDnsHost(host: String?): Boolean {
        val normalized = host?.trim()?.lowercase()?.trimEnd('.') ?: return false
        if (normalized.isBlank()) return false
        return knownEncryptedDnsHosts.any { normalized == it || normalized.endsWith(".$it") } ||
            RuleRepository.isBypassProtectionDomain(normalized)
    }

    fun shouldBlockDirectFlow(
        packet: PacketInfo,
        destinationIp: String,
        appName: String?,
        forceEncryptedDnsFallback: Boolean,
        protectedApp: Boolean,
        trackedTargetDomain: String? = null
    ): Decision {
        if (!forceEncryptedDnsFallback || protectedApp) return Decision(false, null)
        if (packet.protocol == OsConstants.IPPROTO_TCP && packet.destinationPort == 853) {
            return Decision(true, Reason.DOT_PORT)
        }
        if (packet.protocol == OsConstants.IPPROTO_UDP && (packet.destinationPort == 784 || packet.destinationPort == 8853)) {
            return Decision(true, Reason.DOQ_PORT)
        }
        if (isKnownEncryptedDnsHost(trackedTargetDomain)) {
            return Decision(true, Reason.KNOWN_DNS_SNI, trackedTargetDomain)
        }
        if (matchesKnownEncryptedDnsIp(destinationIp) && isDnsTransportPort(packet.destinationPort)) {
            return Decision(true, Reason.KNOWN_DNS_IP)
        }
        if (packet.protocol == OsConstants.IPPROTO_UDP && TlsPortSet.isQuicUdpPort(packet.destinationPort) && looksLikeDnsOverQuic(packet.payload, destinationIp)) {
            return Decision(true, Reason.DNS_LIKE_QUIC)
        }
        return Decision(false, null)
    }

    fun shouldBlockClientHello(
        destinationIp: String,
        metadata: ClientHelloMetadata,
        appName: String?,
        forceEncryptedDnsFallback: Boolean,
        protectedApp: Boolean
    ): Decision {
        if (!forceEncryptedDnsFallback || protectedApp) return Decision(false, null)
        val sni = metadata.sniHost?.trim()?.lowercase()
        val offersH2OrH3 = metadata.alpnProtocols.any {
            it.equals("h2", ignoreCase = true) || it.equals("h3", ignoreCase = true) || it.startsWith("h3-", ignoreCase = true)
        }
        if (isKnownEncryptedDnsHost(sni) && offersH2OrH3) {
            return Decision(true, Reason.KNOWN_DNS_SNI, sni)
        }
        if (matchesKnownEncryptedDnsIp(destinationIp) && offersH2OrH3) {
            return Decision(true, Reason.KNOWN_DNS_IP, sni)
        }
        return Decision(false, null)
    }

    fun looksLikeHttpDnsRequest(host: String?, path: String?): Boolean {
        val normalizedHost = host?.trim()?.lowercase().orEmpty()
        val normalizedPath = path?.trim()?.lowercase().orEmpty()
        if (normalizedHost.contains("httpdns") || isKnownEncryptedDnsHost(normalizedHost)) return true
        if (!listOf("/resolve", "/resolver", "/httpdns", "/dns", "/query").any(normalizedPath::contains)) return false
        return listOf("host=", "hosts=", "domain=", "domains=", "qname=", "name=").any(normalizedPath::contains)
    }

    fun matchesKnownEncryptedDnsIp(ip: String): Boolean {
        val address = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return false
        return knownEncryptedDnsNetworks.any { network -> matchesPrefix(address, network.address, network.prefixLength) }
    }

    private fun isDnsTransportPort(port: Int): Boolean = port == 443 || port == 853 || port == 784 || port == 8853

    private fun looksLikeDnsOverQuic(payload: ByteArray, destinationIp: String): Boolean {
        if (payload.size !in 36..1500) return false
        val first = payload.firstOrNull()?.toInt()?.and(0xFF) ?: return false
        val quicHeader = (first and 0x80) != 0 || (first and 0x40) != 0
        return quicHeader && matchesKnownEncryptedDnsIp(destinationIp)
    }

    private fun parseNetwork(raw: String): IpNetwork? {
        val addressPart = raw.substringBefore('/').trim()
        val prefixPart = raw.substringAfter('/', "").trim()
        val address = runCatching { InetAddress.getByName(addressPart).address }.getOrNull() ?: return null
        val maxPrefix = address.size * 8
        val prefix = prefixPart.toIntOrNull() ?: maxPrefix
        if (prefix !in 0..maxPrefix) return null
        return IpNetwork(address, prefix)
    }

    private fun matchesPrefix(address: ByteArray, network: ByteArray, prefixLength: Int): Boolean {
        if (address.size != network.size) return false
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        for (index in 0 until fullBytes) {
            if (address[index] != network[index]) return false
        }
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (address[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
    }
}
