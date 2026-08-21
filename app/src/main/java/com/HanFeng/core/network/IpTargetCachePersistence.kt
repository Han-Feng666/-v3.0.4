package com.HanFeng.core.network

import android.content.Context
import android.content.SharedPreferences
import com.HanFeng.service.AdIpTarget
import com.HanFeng.service.HttpDecryptTarget
import com.HanFeng.service.HttpsDecryptTarget

object IpTargetCachePersistence {

    private const val PREFS_NAME = "ip_target_cache"
    private const val KEY_AD_IP = "ad_ip"
    private const val KEY_HTTP_DECRYPT = "http_decrypt"
    private const val KEY_HTTPS_DECRYPT = "https_decrypt"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun loadAdIpCache(): Map<String, AdIpTarget> {
        val prefs = prefs ?: return emptyMap()
        val data = prefs.getString(KEY_AD_IP, null) ?: return emptyMap()
        val now = System.currentTimeMillis()
        val result = linkedMapOf<String, AdIpTarget>()
        if (data.isNotBlank()) {
            data.split("|").forEach { entry ->
                val parts = entry.split(",", limit = 6)
                if (parts.size == 6) {
                    val expiresAt = parts[5].toLongOrNull() ?: return@forEach
                    if (expiresAt > now) {
                        result[parts[0]] = AdIpTarget(
                            domain = parts[1],
                            vendor = parts[2],
                            appName = parts[3],
                            source = parts[4],
                            expiresAt = expiresAt
                        )
                    }
                }
            }
        }
        return result
    }

    fun loadHttpDecryptCache(): Map<String, HttpDecryptTarget> {
        val prefs = prefs ?: return emptyMap()
        val data = prefs.getString(KEY_HTTP_DECRYPT, null) ?: return emptyMap()
        val now = System.currentTimeMillis()
        val result = linkedMapOf<String, HttpDecryptTarget>()
        if (data.isNotBlank()) {
            data.split("|").forEach { entry ->
                val parts = entry.split(",", limit = 6)
                if (parts.size == 6) {
                    val expiresAt = parts[5].toLongOrNull() ?: return@forEach
                    if (expiresAt > now) {
                        result[parts[0]] = HttpDecryptTarget(
                            domain = parts[1],
                            vendor = parts[2],
                            appName = parts[3],
                            source = parts[4],
                            expiresAt = expiresAt
                        )
                    }
                }
            }
        }
        return result
    }

    fun loadHttpsDecryptCache(): Map<String, HttpsDecryptTarget> {
        val prefs = prefs ?: return emptyMap()
        val data = prefs.getString(KEY_HTTPS_DECRYPT, null) ?: return emptyMap()
        val now = System.currentTimeMillis()
        val result = linkedMapOf<String, HttpsDecryptTarget>()
        if (data.isNotBlank()) {
            data.split("|").forEach { entry ->
                val parts = entry.split(",", limit = 6)
                if (parts.size == 6) {
                    val expiresAt = parts[5].toLongOrNull() ?: return@forEach
                    if (expiresAt > now) {
                        result[parts[0]] = HttpsDecryptTarget(
                            domain = parts[1],
                            vendor = parts[2],
                            appName = parts[3],
                            source = parts[4],
                            expiresAt = expiresAt
                        )
                    }
                }
            }
        }
        return result
    }

    fun save(
        adIpCache: Map<String, AdIpTarget>,
        httpDecryptCache: Map<String, HttpDecryptTarget>,
        httpsDecryptCache: Map<String, HttpsDecryptTarget>
    ) {
        val editor = prefs?.edit() ?: return
        val now = System.currentTimeMillis()
        val adIpData = adIpCache.entries
            .filter { it.value.expiresAt > now }
            .joinToString("|") { "${it.key},${it.value.domain},${it.value.vendor},${it.value.appName},${it.value.source},${it.value.expiresAt}" }
        editor.putString(KEY_AD_IP, adIpData)
        val httpData = httpDecryptCache.entries
            .filter { it.value.expiresAt > now }
            .joinToString("|") { "${it.key},${it.value.domain},${it.value.vendor},${it.value.appName},${it.value.source},${it.value.expiresAt}" }
        editor.putString(KEY_HTTP_DECRYPT, httpData)
        val httpsData = httpsDecryptCache.entries
            .filter { it.value.expiresAt > now }
            .joinToString("|") { "${it.key},${it.value.domain},${it.value.vendor},${it.value.appName},${it.value.source},${it.value.expiresAt}" }
        editor.putString(KEY_HTTPS_DECRYPT, httpsData)
        editor.apply()
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }
}