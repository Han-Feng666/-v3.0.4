package com.HanFeng.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.HanFeng.model.PendingFeedbackRule
import com.HanFeng.model.VolumeKeyAction
import com.HanFeng.model.WeakNetworkParams

object FeatureSettingsRepository {
    private const val PREFS = "feature_settings"
    private const val KEY_AD_BLOCK_ENABLED = "ad_block_enabled"
    private const val KEY_HTTP_DECRYPT_ENABLED = "http_decrypt_enabled"
    private const val KEY_VPN_REVOKED_BY_OTHER_VPN = "vpn_revoked_by_other_vpn"
    private const val KEY_FORCE_ENCRYPTED_DNS_FALLBACK = "force_encrypted_dns_fallback"
    private const val KEY_MITM_LEARNING_MODE = "mitm_learning_mode"
    private const val KEY_PROC_NET_OWNER_FALLBACK = "proc_net_owner_fallback"
    private const val KEY_STEALTH_MODE = "stealth_mode"
    private const val KEY_STEALTH_STRIP_TRACKING_PARAMS = "stealth_strip_tracking_params"
    private const val KEY_STEALTH_HIDE_REFERER = "stealth_hide_referer"
    private const val KEY_STEALTH_REMOVE_FINGERPRINT_HEADERS = "stealth_remove_fingerprint_headers"
    private const val KEY_CUSTOM_TRACKING_PARAMS = "custom_tracking_params"
    private const val KEY_CUSTOM_TRACKING_HEADERS = "custom_tracking_headers"
    private const val KEY_PENDING_FEEDBACK_RULES = "pending_feedback_rules"
    private const val KEY_HOTSPOT_BLOCK_ENABLED = "hotspot_block_enabled"
    private const val KEY_HOTSPOT_BLOCK_MODE = "hotspot_block_mode"
    private const val KEY_HOTSPOT_BLOCKED_COUNT = "hotspot_blocked_count"
    private const val KEY_HOTSPOT_DEVICE_COUNT = "hotspot_device_count"
    private const val KEY_HOTSPOT_START_TIME = "hotspot_start_time"
    private const val KEY_AUTO_INSTALL_SYSTEM_CERT = "auto_install_system_cert"
    private const val KEY_CUSTOM_BACKGROUND_PATH = "custom_background_path"
    private const val KEY_CUSTOM_BACKGROUND_LIST = "custom_background_list_v2"
    private const val KEY_CUSTOM_BACKGROUND_INDEX = "custom_background_index"
    private const val KEY_IDLE_SHUTDOWN_ENABLED = "idle_shutdown_enabled"
    private const val KEY_IDLE_SHUTDOWN_THRESHOLD = "idle_shutdown_threshold"
    private const val KEY_NOTIFICATION_AD_BLOCK_ENABLED = "notification_ad_block_enabled"
    private const val KEY_NOTIFICATION_AD_BLOCK_KEYWORDS = "notification_ad_block_keywords"
    private const val MAX_PENDING_FEEDBACK_RULES = 50
    private val gson = Gson()
    @Volatile private var cachedAdBlockEnabled: Boolean? = null
    @Volatile private var cachedHttpDecryptEnabled: Boolean? = null
    @Volatile private var cachedVpnRevokedByOtherVpn: Boolean? = null
    @Volatile private var cachedForceEncryptedDnsFallback: Boolean? = null
    @Volatile private var cachedMitmLearningMode: Boolean? = null
    @Volatile private var cachedProcNetOwnerFallback: Boolean? = null
    @Volatile private var cachedStealthMode: Boolean? = null
    @Volatile private var cachedStealthStripTrackingParams: Boolean? = null

    @Volatile private var cachedStealthHideReferer: Boolean? = null
    @Volatile private var cachedStealthRemoveFingerprintHeaders: Boolean? = null
    @Volatile private var cachedHotspotBlockEnabled: Boolean? = null
    @Volatile private var cachedHotspotBlockMode: String? = null
    @Volatile private var cachedAutoInstallSystemCert: Boolean? = null

    fun isAdBlockEnabled(context: Context): Boolean {
        cachedAdBlockEnabled?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AD_BLOCK_ENABLED, false)
            .also { cachedAdBlockEnabled = it }
    }

    fun setAdBlockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AD_BLOCK_ENABLED, enabled)
            .apply()
        cachedAdBlockEnabled = enabled
    }

    fun isHttpDecryptEnabled(context: Context): Boolean {
        cachedHttpDecryptEnabled?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HTTP_DECRYPT_ENABLED, false)
            .also { cachedHttpDecryptEnabled = it }
    }

    fun setHttpDecryptEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HTTP_DECRYPT_ENABLED, enabled)
            .apply()
        cachedHttpDecryptEnabled = enabled
    }

    fun isVpnRevokedByOtherVpn(context: Context): Boolean {
        cachedVpnRevokedByOtherVpn?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VPN_REVOKED_BY_OTHER_VPN, false)
            .also { cachedVpnRevokedByOtherVpn = it }
    }

    fun setVpnRevokedByOtherVpn(context: Context, revoked: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VPN_REVOKED_BY_OTHER_VPN, revoked)
            .apply()
        cachedVpnRevokedByOtherVpn = revoked
    }

    fun isForceEncryptedDnsFallbackEnabled(context: Context): Boolean {
        cachedForceEncryptedDnsFallback?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FORCE_ENCRYPTED_DNS_FALLBACK, true)
            .also { cachedForceEncryptedDnsFallback = it }
    }

    fun setForceEncryptedDnsFallbackEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FORCE_ENCRYPTED_DNS_FALLBACK, enabled)
            .apply()
        cachedForceEncryptedDnsFallback = enabled
    }

    fun isMitmLearningModeEnabled(context: Context): Boolean {
        cachedMitmLearningMode?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MITM_LEARNING_MODE, false)
            .also { cachedMitmLearningMode = it }
    }

    fun setMitmLearningModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MITM_LEARNING_MODE, enabled)
            .apply()
        cachedMitmLearningMode = enabled
    }

    fun isProcNetOwnerFallbackEnabled(context: Context): Boolean {
        cachedProcNetOwnerFallback?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROC_NET_OWNER_FALLBACK, false)
            .also { cachedProcNetOwnerFallback = it }
    }

    fun setProcNetOwnerFallbackEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROC_NET_OWNER_FALLBACK, enabled)
            .apply()
        cachedProcNetOwnerFallback = enabled
    }

    fun isStealthModeEnabled(context: Context): Boolean {
        cachedStealthMode?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_STEALTH_MODE, false)
            .also { cachedStealthMode = it }
    }

    fun setStealthModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STEALTH_MODE, enabled)
            .apply()
        cachedStealthMode = enabled
    }

    fun isStealthStripTrackingParamsEnabled(context: Context): Boolean {
        cachedStealthStripTrackingParams?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_STEALTH_STRIP_TRACKING_PARAMS, true)
            .also { cachedStealthStripTrackingParams = it }
    }

    fun setStealthStripTrackingParamsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STEALTH_STRIP_TRACKING_PARAMS, enabled)
            .apply()
        cachedStealthStripTrackingParams = enabled
    }

    fun isStealthHideRefererEnabled(context: Context): Boolean {
        cachedStealthHideReferer?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_STEALTH_HIDE_REFERER, true)
            .also { cachedStealthHideReferer = it }
    }

    fun setStealthHideRefererEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STEALTH_HIDE_REFERER, enabled)
            .apply()
        cachedStealthHideReferer = enabled
    }

    fun isStealthRemoveFingerprintHeadersEnabled(context: Context): Boolean {
        cachedStealthRemoveFingerprintHeaders?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_STEALTH_REMOVE_FINGERPRINT_HEADERS, true)
            .also { cachedStealthRemoveFingerprintHeaders = it }
    }

    fun setStealthRemoveFingerprintHeadersEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STEALTH_REMOVE_FINGERPRINT_HEADERS, enabled)
            .apply()
        cachedStealthRemoveFingerprintHeaders = enabled
    }

    fun getCustomTrackingParams(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_TRACKING_PARAMS, "") ?: ""
        return raw.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    }

    fun setCustomTrackingParams(context: Context, params: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_TRACKING_PARAMS, params.joinToString(",") { it.trim().lowercase() })
            .apply()
    }

    fun addCustomTrackingParam(context: Context, param: String) {
        val current = getCustomTrackingParams(context).toMutableSet()
        current += param.trim().lowercase()
        setCustomTrackingParams(context, current)
    }

    fun removeCustomTrackingParam(context: Context, param: String) {
        val current = getCustomTrackingParams(context).toMutableSet()
        current -= param.trim().lowercase()
        setCustomTrackingParams(context, current)
    }

    fun getCustomTrackingHeaders(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_TRACKING_HEADERS, "") ?: ""
        return raw.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    }

    fun setCustomTrackingHeaders(context: Context, headers: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_TRACKING_HEADERS, headers.joinToString(",") { it.trim().lowercase() })
            .apply()
    }

    fun addCustomTrackingHeader(context: Context, header: String) {
        val current = getCustomTrackingHeaders(context).toMutableSet()
        current += header.trim().lowercase()
        setCustomTrackingHeaders(context, current)
    }

    fun removeCustomTrackingHeader(context: Context, header: String) {
        val current = getCustomTrackingHeaders(context).toMutableSet()
        current -= header.trim().lowercase()
        setCustomTrackingHeaders(context, current)
    }

    fun getPendingFeedbackRules(context: Context): List<PendingFeedbackRule> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_FEEDBACK_RULES, null)
            ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<PendingFeedbackRule>>() {}.type
            gson.fromJson<List<PendingFeedbackRule>>(json, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun addPendingFeedbackRule(context: Context, rule: PendingFeedbackRule) {
        val current = getPendingFeedbackRules(context)
            .filterNot { it.ruleText == rule.ruleText }
        savePendingFeedbackRules(context, (listOf(rule) + current).take(MAX_PENDING_FEEDBACK_RULES))
    }

    fun removePendingFeedbackRule(context: Context, id: String) {
        savePendingFeedbackRules(context, getPendingFeedbackRules(context).filterNot { it.id == id })
    }

    fun isHotspotBlockEnabled(context: Context): Boolean {
        cachedHotspotBlockEnabled?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HOTSPOT_BLOCK_ENABLED, false)
            .also { cachedHotspotBlockEnabled = it }
    }

    fun setHotspotBlockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HOTSPOT_BLOCK_ENABLED, enabled)
            .apply()
        cachedHotspotBlockEnabled = enabled
    }

    fun getHotspotBlockMode(context: Context): String {
        cachedHotspotBlockMode?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOTSPOT_BLOCK_MODE, "vpn")
            ?.also { cachedHotspotBlockMode = it } ?: "vpn"
    }

    fun setHotspotBlockMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOTSPOT_BLOCK_MODE, mode)
            .apply()
        cachedHotspotBlockMode = mode
    }

    fun incrementHotspotBlockedCount(context: Context, count: Int = 1) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getLong(KEY_HOTSPOT_BLOCKED_COUNT, 0)
        prefs.edit()
            .putLong(KEY_HOTSPOT_BLOCKED_COUNT, current + count)
            .apply()
    }

    fun getHotspotBlockedCount(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_HOTSPOT_BLOCKED_COUNT, 0)
    }

    fun updateHotspotDeviceCount(context: Context, count: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_HOTSPOT_DEVICE_COUNT, count)
            .apply()
    }

    fun getHotspotDeviceCount(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_HOTSPOT_DEVICE_COUNT, 0)
    }

    fun setHotspotStartTime(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_HOTSPOT_START_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getHotspotStartTime(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_HOTSPOT_START_TIME, 0)
    }

    fun resetHotspotStats(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                remove(KEY_HOTSPOT_BLOCKED_COUNT)
                remove(KEY_HOTSPOT_DEVICE_COUNT)
                remove(KEY_HOTSPOT_START_TIME)
            }
            .apply()
    }

    fun isAutoInstallSystemCertEnabled(context: Context): Boolean {
        cachedAutoInstallSystemCert?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_INSTALL_SYSTEM_CERT, false)
            .also { cachedAutoInstallSystemCert = it }
    }

    fun setAutoInstallSystemCertEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_INSTALL_SYSTEM_CERT, enabled)
            .apply()
        cachedAutoInstallSystemCert = enabled
    }

    fun getCustomBackgroundPath(context: Context): String? {
        // 优先用多背景图逻辑的当前激活路径；迁移后会落在 list 中
        migrateLegacyBackgroundToV2(context)
        val list = getCustomBackgroundPaths(context)
        val idx = getActiveBackgroundIndex(context)
        return if (list.isEmpty() || idx < 0 || idx >= list.size) null else list[idx]
    }

    fun setCustomBackgroundPath(context: Context, path: String?) {
        // 旧 API 仍可用：直接 append 到 list 中并选为当前激活
        if (path == null) {
            // 清空所有
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CUSTOM_BACKGROUND_LIST)
                .remove(KEY_CUSTOM_BACKGROUND_PATH)
                .remove(KEY_CUSTOM_BACKGROUND_INDEX)
                .apply()
            invalidateBackgroundCache()
        } else {
            appendCustomBackgroundPath(context, path)
        }
    }

    /**
     * 单项内存缓存：onResume 频繁调用时避免每次都做 Gson 反序列化 + 多个 File.exists() stat。
     * 缓存失效条件：append/remove/replace/切换 active index 等所有写入路径均调用 invalidateBackgroundCache()
     */
    @Volatile private var cachedBackgroundPaths: List<String>? = null

    private fun invalidateBackgroundCache() {
        cachedBackgroundPaths = null
    }

    /**
     * 多背景图：返回用户已上传的路径列表，去掉文件已不存在的项
     */
    fun getCustomBackgroundPaths(context: Context): List<String> {
        cachedBackgroundPaths?.let { if (it.isNotEmpty()) return it }
        migrateLegacyBackgroundToV2(context)
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_BACKGROUND_LIST, null)
            ?: run {
                cachedBackgroundPaths = emptyList()
                return emptyList()
            }
        val type = object : TypeToken<List<String>>() {}.type
        val list: List<String> = runCatching<List<String>> { gson.fromJson(raw, type) }.getOrNull() ?: emptyList()
        // 过滤已不存在的文件，避免点击时显示黑屏
        val filtered = list.filter { java.io.File(it).exists() }
        if (filtered.size != list.size) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CUSTOM_BACKGROUND_LIST, gson.toJson(filtered))
                .apply()
            // 索引可能越界，校正（列表为空时直接置 0，避免 coerceIn 空区间抛异常）
            val idx = if (filtered.isEmpty()) 0
            else getActiveBackgroundIndex(context).coerceIn(0, filtered.size - 1)
            setActiveBackgroundIndex(context, idx)
        }
        cachedBackgroundPaths = filtered
        return filtered
    }

    fun appendCustomBackgroundPath(context: Context, path: String): Int {
        migrateLegacyBackgroundToV2(context)
        val list = getCustomBackgroundPaths(context).toMutableList()
        // 去重：相同 path 不重复加入
        if (!list.contains(path)) {
            list.add(path)
        }
        val newIdx = list.indexOf(path)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_BACKGROUND_LIST, gson.toJson(list))
            .putInt(KEY_CUSTOM_BACKGROUND_INDEX, newIdx)
            .apply()
        invalidateBackgroundCache()
        return newIdx
    }

    fun removeCustomBackgroundPath(context: Context, path: String) {
        migrateLegacyBackgroundToV2(context)
        val list = getCustomBackgroundPaths(context).toMutableList()
        val removedIdx = list.indexOf(path)
        if (removedIdx < 0) return
        list.removeAt(removedIdx)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_BACKGROUND_LIST, gson.toJson(list))
            .apply()
        invalidateBackgroundCache()
        // 校正索引（列表为空时直接置 0，避免 coerceIn 空区间抛异常）
        if (list.isEmpty()) {
            setActiveBackgroundIndex(context, 0)
            return
        }
        val prevIdx = getActiveBackgroundIndex(context)
        val newIdx = when {
            removedIdx < prevIdx -> prevIdx - 1
            removedIdx == prevIdx -> prevIdx.coerceAtMost(list.size - 1)
            else -> prevIdx
        }
        setActiveBackgroundIndex(context, newIdx.coerceIn(0, list.size - 1))
    }

    fun getActiveBackgroundIndex(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CUSTOM_BACKGROUND_INDEX, 0)
    }

    fun setActiveBackgroundIndex(context: Context, index: Int) {
        val list = getCustomBackgroundPaths(context)
        val safe = if (list.isEmpty()) 0 else index.coerceIn(0, list.size - 1)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CUSTOM_BACKGROUND_INDEX, safe)
            .apply()
    }

    /**
     * 旧的 KEY_CUSTOM_BACKGROUND_PATH 自动迁移到 list，仅在新版首启执行一次
     */
    @Volatile private var backgroundV2Migrated = false
    private fun migrateLegacyBackgroundToV2(context: Context) {
        if (backgroundV2Migrated) return
        synchronized(this) {
            if (backgroundV2Migrated) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val legacyPath = prefs.getString(KEY_CUSTOM_BACKGROUND_PATH, null)
            if (legacyPath != null && !legacyPath.isBlank()) {
                val list: List<String> = runCatching<List<String>> {
                    val raw = prefs.getString(KEY_CUSTOM_BACKGROUND_LIST, null)
                    if (raw != null) {
                        val type = object : TypeToken<List<String>>() {}.type
                        gson.fromJson(raw, type) ?: emptyList()
                    } else emptyList()
                }.getOrDefault(emptyList())
                if (!list.contains(legacyPath) && java.io.File(legacyPath).exists()) {
                    val newList = list + legacyPath
                    prefs.edit()
                        .putString(KEY_CUSTOM_BACKGROUND_LIST, gson.toJson(newList))
                        .putInt(KEY_CUSTOM_BACKGROUND_INDEX, newList.size - 1)
                        .apply()
                }
            }
            // 移除旧 KEY 避免下次再处理（路径已迁移到 list）
            prefs.edit().remove(KEY_CUSTOM_BACKGROUND_PATH).apply()
            backgroundV2Migrated = true
        }
    }

    private fun savePendingFeedbackRules(context: Context, rules: List<PendingFeedbackRule>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_FEEDBACK_RULES, gson.toJson(rules))
            .apply()
    }

    @Volatile private var cachedIdleShutdownEnabled: Boolean? = null
    @Volatile private var cachedIdleShutdownThreshold: Long? = null

    fun isIdleShutdownEnabled(context: Context): Boolean {
        cachedIdleShutdownEnabled?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_IDLE_SHUTDOWN_ENABLED, true)
            .also { cachedIdleShutdownEnabled = it }
    }

    fun setIdleShutdownEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IDLE_SHUTDOWN_ENABLED, enabled)
            .apply()
        cachedIdleShutdownEnabled = enabled
    }

    fun getIdleShutdownThreshold(context: Context): Long {
        cachedIdleShutdownThreshold?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_IDLE_SHUTDOWN_THRESHOLD, 60_000L)
            .also { cachedIdleShutdownThreshold = it }
    }

    fun setIdleShutdownThreshold(context: Context, threshold: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_IDLE_SHUTDOWN_THRESHOLD, threshold)
            .apply()
        cachedIdleShutdownThreshold = threshold
    }

    @Volatile private var cachedNotificationAdBlockEnabled: Boolean? = null
    @Volatile private var cachedNotificationAdBlockKeywords: String? = null

    fun isNotificationAdBlockEnabled(context: Context): Boolean {
        cachedNotificationAdBlockEnabled?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFICATION_AD_BLOCK_ENABLED, false)
            .also { cachedNotificationAdBlockEnabled = it }
    }

    fun setNotificationAdBlockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATION_AD_BLOCK_ENABLED, enabled)
            .apply()
        cachedNotificationAdBlockEnabled = enabled
    }

    fun getNotificationAdBlockKeywords(context: Context): String {
        cachedNotificationAdBlockKeywords?.let { return it }
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NOTIFICATION_AD_BLOCK_KEYWORDS, DEFAULT_NOTIFICATION_AD_KEYWORDS)
        val value = raw ?: DEFAULT_NOTIFICATION_AD_KEYWORDS
        cachedNotificationAdBlockKeywords = value
        return value
    }

    fun setNotificationAdBlockKeywords(context: Context, keywords: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NOTIFICATION_AD_BLOCK_KEYWORDS, keywords)
            .apply()
        cachedNotificationAdBlockKeywords = keywords
    }

    /**
     * 默认广告通知关键字：横幅/启动/限时/拼团等典型推广话术。用户可在设置里覆盖。
     */
    const val DEFAULT_NOTIFICATION_AD_KEYWORDS: String =
        "广告,推广,限时,秒杀,优惠券,红包,抽奖,领取,福利,免费,补贴,拼团,砍价,赚佣金,邀请好友,边玩边赚,首单立减,新人专享,今日特惠,签到领,推广入驻,送大礼,提现,up to,广告打开,新品首发,马上抢,零门槛"

    // ---------------- 弱网模拟 ----------------
    private const val KEY_WEAK_NET_ENABLED = "weak_net_enabled"
    private const val KEY_WEAK_NET_TARGET_PACKAGE = "weak_net_target_package"
    private const val KEY_WEAK_NET_LATENCY_MS = "weak_net_latency_ms"
    private const val KEY_WEAK_NET_JITTER_MS = "weak_net_jitter_ms"
    private const val KEY_WEAK_NET_LOSS_PERCENT = "weak_net_loss_percent"
    private const val KEY_WEAK_NET_DOWN_KBPS = "weak_net_down_kbps"
    private const val KEY_WEAK_NET_UP_KBPS = "weak_net_up_kbps"
    /** 旧版弱网音量键布尔字段，仅用于向 [KEY_VOLUME_KEY_ACTION] 迁移回退 */
    private const val KEY_WEAK_NET_VOLUME_KEY = "weak_net_volume_key"
    private const val KEY_VOLUME_KEY_ACTION = "volume_key_action"

    const val WEAK_NET_LATENCY_MAX_MS = 5000
    const val WEAK_NET_JITTER_MAX_MS = 1000
    const val WEAK_NET_LOSS_MAX_PERCENT = 100
    const val WEAK_NET_LIMIT_MAX_KBPS = 100000

    @Volatile private var cachedWeakNetEnabled: Boolean? = null
    @Volatile private var cachedWeakNetTargetPackage: String? = null
    @Volatile private var cachedWeakNetParams: WeakNetworkParams? = null
    @Volatile private var cachedVolumeKeyAction: VolumeKeyAction? = null

    fun isWeakNetEnabled(context: Context): Boolean {
        cachedWeakNetEnabled?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WEAK_NET_ENABLED, false)
            .also { cachedWeakNetEnabled = it }
    }

    fun setWeakNetEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WEAK_NET_ENABLED, enabled)
            .apply()
        cachedWeakNetEnabled = enabled
    }

    fun getWeakNetTargetPackage(context: Context): String? {
        cachedWeakNetTargetPackage?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WEAK_NET_TARGET_PACKAGE, null)
            .also { cachedWeakNetTargetPackage = it }
    }

    fun setWeakNetTargetPackage(context: Context, packageName: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WEAK_NET_TARGET_PACKAGE, packageName)
            .apply()
        cachedWeakNetTargetPackage = packageName
    }

    fun getWeakNetworkParams(context: Context): WeakNetworkParams {
        cachedWeakNetParams?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val params = WeakNetworkParams(
            latencyMs = prefs.getInt(KEY_WEAK_NET_LATENCY_MS, 0),
            jitterMs = prefs.getInt(KEY_WEAK_NET_JITTER_MS, 0),
            lossPercent = prefs.getInt(KEY_WEAK_NET_LOSS_PERCENT, 0),
            downKbps = prefs.getInt(KEY_WEAK_NET_DOWN_KBPS, 0),
            upKbps = prefs.getInt(KEY_WEAK_NET_UP_KBPS, 0)
        )
        cachedWeakNetParams = params
        return params
    }

    fun setWeakNetworkParams(context: Context, params: WeakNetworkParams) {
        // 钳制到合法范围，避免非法参数注入
        val safe = WeakNetworkParams(
            latencyMs = params.latencyMs.coerceIn(0, WEAK_NET_LATENCY_MAX_MS),
            jitterMs = params.jitterMs.coerceIn(0, WEAK_NET_JITTER_MAX_MS),
            lossPercent = params.lossPercent.coerceIn(0, WEAK_NET_LOSS_MAX_PERCENT),
            downKbps = params.downKbps.coerceIn(0, WEAK_NET_LIMIT_MAX_KBPS),
            upKbps = params.upKbps.coerceIn(0, WEAK_NET_LIMIT_MAX_KBPS)
        )
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_WEAK_NET_LATENCY_MS, safe.latencyMs)
            .putInt(KEY_WEAK_NET_JITTER_MS, safe.jitterMs)
            .putInt(KEY_WEAK_NET_LOSS_PERCENT, safe.lossPercent)
            .putInt(KEY_WEAK_NET_DOWN_KBPS, safe.downKbps)
            .putInt(KEY_WEAK_NET_UP_KBPS, safe.upKbps)
            .apply()
        cachedWeakNetParams = safe
    }

    /**
     * 当前"音量键快捷功能"动作。首次读取时若只有旧版 `weak_net_volume_key`
     * 布尔字段，则迁移到新版枚举并写回（默认为 [VolumeKeyAction.NONE]）。
     */
    fun getVolumeKeyAction(context: Context): VolumeKeyAction {
        cachedVolumeKeyAction?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_VOLUME_KEY_ACTION, null)
        val action = if (raw == null) {
            val legacyEnabled = prefs.getBoolean(KEY_WEAK_NET_VOLUME_KEY, false)
            val migrated = if (legacyEnabled) VolumeKeyAction.WEAK_NET else VolumeKeyAction.NONE
            prefs.edit().putString(KEY_VOLUME_KEY_ACTION, migrated.name).apply()
            migrated
        } else {
            volumeKeyActionSafe(raw)
        }
        cachedVolumeKeyAction = action
        return action
    }

    fun setVolumeKeyAction(context: Context, action: VolumeKeyAction) {
        val safe = if (action == null) VolumeKeyAction.NONE else action
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VOLUME_KEY_ACTION, safe.name)
            .apply()
        cachedVolumeKeyAction = safe
    }

    /** 弱网音量键开关（派生自 [getVolumeKeyAction]）。 */
    fun isWeakNetVolumeKeyEnabled(context: Context): Boolean =
        getVolumeKeyAction(context) == VolumeKeyAction.WEAK_NET

    fun setWeakNetVolumeKeyEnabled(context: Context, enabled: Boolean) {
        setVolumeKeyAction(context, if (enabled) VolumeKeyAction.WEAK_NET else VolumeKeyAction.NONE)
    }

    /** 将音量键快捷功能恢复为系统原生音量行为。 */
    fun clearVolumeKeyAction(context: Context) {
        setVolumeKeyAction(context, VolumeKeyAction.NONE)
    }

    private fun volumeKeyActionSafe(raw: String): VolumeKeyAction {
        return runCatching { VolumeKeyAction.valueOf(raw) }.getOrDefault(VolumeKeyAction.NONE)
    }
}
