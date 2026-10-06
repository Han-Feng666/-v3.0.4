package com.HanFeng.adblocker.shizuku

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 蓝牙耳机管理：已配对音频设备枚举、电量/编解码探测（root dumpsys）、音量、系统音效。
 *
 * 降噪（ANC）边界说明：ANC 是厂商私有能力，Android 公开 API 不暴露降噪控制。
 * 这里按设备名匹配厂商适配表（AncVendorAdapter），给出各品牌可控路径；
 * 无法用通用命令控制的品牌如实提示需要厂商 App，绝不伪造成功状态。
 */
class BluetoothHeadsetManager(private val context: Context) {

    data class HeadsetDevice(
        val name: String,
        val address: String,
        val ancAdapter: AncVendorAdapter?
    )

    /** 降噪能力适配描述 */
    data class AncVendorAdapter(
        val vendorName: String,
        /** 是否存在可编程控制路径（如厂商 App 深链 / AT 指令），false 表示仅提示用户 */
        val programmable: Boolean,
        /** 用户操作指引 */
        val hint: String
    )

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 已配对的音频类蓝牙设备（连接状态由 [isDeviceConnected] 判定）
     *  TWS/LE Audio 设备 bluetoothClass 可能为 null, 此类设备一律保留, 由音频类特征兜底 */
    fun listBondedAudioDevices(): List<HeadsetDevice> {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager ?: return emptyList()
        val adapter = manager.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val bonded = runCatching { adapter.bondedDevices }.getOrNull() ?: return emptyList()
        return bonded
            .filter { device ->
                val cls = device.bluetoothClass
                    ?: return@filter true  // class 未知(TWS/LE Audio 常见)时保留, 避免漏掉已配对耳机
                cls.hasService(android.bluetooth.BluetoothClass.Service.RENDER) ||
                    cls.deviceClass in AUDIO_DEVICE_CLASSES
            }
            .map { device ->
                HeadsetDevice(
                    name = device.name ?: "未知设备",
                    address = device.address,
                    ancAdapter = findAncAdapter(device.name ?: "")
                )
            }
    }

    /** 当前系统音频输出中的蓝牙设备地址集合（A2DP/SCO），用于判定耳机"真正在连接中" */
    fun connectedBluetoothOutputAddresses(): Set<String> = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            .map { it.address }
            .toSet()
    }.getOrDefault(emptySet())

    /** 判断指定耳机当前是否处于已连接（A2DP 活跃输出）状态 */
    fun isDeviceConnected(deviceAddress: String): Boolean =
        connectedBluetoothOutputAddresses().any { it.equals(deviceAddress, ignoreCase = true) }

    /** 按名称匹配降噪适配器（纯逻辑，可测） */
    fun findAncAdapter(deviceName: String): AncVendorAdapter? =
        findAncAdapterForName(deviceName)

    fun getMediaVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    /** 设置媒体音量（0-100） */
    fun setMediaVolumePercent(percent: Int): Boolean = runCatching {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return false
        val target = percent.coerceIn(0, 100) * max / 100
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        true
    }.getOrDefault(false)

    /**
     * 应用系统音效：低音增强 + 虚拟环绕，作用于输出混音。
     * Android 10+ 对跨应用音效有限制，失败时如实返回结果，由 UI 呈现。
     */
    fun applyAudioEffects(bassStrength: Int, virtualizerStrength: Int): EffectApplyResult {
        releaseAudioEffects()
        return runCatching {
            val sessionId = audioManager.generateAudioSessionId()
                ?: return EffectApplyResult(false, "无法生成音频会话，设备限制")
            var applied = 0
            if (bassStrength > 0) {
                bassEffect = BassBoost(0, sessionId).apply { setEnabled(true) }
                runCatching { bassEffect?.setStrength(bassStrength.toShort()) }
                applied++
            }
            if (virtualizerStrength > 0) {
                virtualizerEffect = Virtualizer(0, sessionId).apply { setEnabled(true) }
                runCatching { virtualizerEffect?.setStrength(virtualizerStrength.toShort()) }
                applied++
            }
            if (applied == 0) {
                EffectApplyResult(false, "未选择任何音效")
            } else {
                EffectApplyResult(true, "已应用 $applied 项音效")
            }
        }.getOrElse {
            EffectApplyResult(false, "音效应用失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun releaseAudioEffects() {
        runCatching { bassEffect?.release() }
        runCatching { virtualizerEffect?.release() }
        bassEffect = null
        virtualizerEffect = null
    }

    private var bassEffect: BassBoost? = null
    private var virtualizerEffect: Virtualizer? = null

    data class EffectApplyResult(val success: Boolean, val message: String)

    /** dumpsys 探测指定设备电量（root），格式随 ROM 有差异，容错解析 */
    fun queryBatteryViaRoot(suSession: SuSession, deviceAddress: String): Int? {
        if (!suSession.isSessionOpen()) return null
        val out = suSession.execute("dumpsys bluetooth_manager", 8).output
        return parseBatteryForDevice(out, deviceAddress)
    }

    /** dumpsys 探测 A2DP 当前编解码（root） */
    fun queryCodecViaRoot(suSession: SuSession, deviceAddress: String): String? {
        if (!suSession.isSessionOpen()) return null
        val out = suSession.execute("dumpsys bluetooth_manager", 8).output
        return parseSelectedCodec(out, deviceAddress)
    }

    /**
     * 通过系统 API 读取耳机电量（TWS 常见上报路径）。
     * BluetoothDevice.getBatteryLevel() 在 SDK 34+ 已从公共 API 移除（转入 mainline），
     * 因此用反射调用（API 29+ 设备上存在），无权限或未上报时返回 null。
     */
    @Suppress("DiscouragedPrivateApi", "PrivateApi")
    fun queryBatteryViaApi(deviceAddress: String): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager ?: return null
        val adapter = manager.adapter ?: return null
        return runCatching {
            val device = adapter.getRemoteDevice(deviceAddress)
            val level = device.javaClass.getMethod("getBatteryLevel").invoke(device) as? Int
            level?.takeIf { it in 1..100 }
        }.getOrNull()
    }

    companion object {
        /** 纯逻辑降噪适配查询，供实例方法与单测共用 */
        fun findAncAdapterForName(deviceName: String): AncVendorAdapter? =
            ANC_ADAPTERS.firstOrNull { entry -> entry.first.matches(deviceName) }?.second

        private val AUDIO_DEVICE_CLASSES = setOf(
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
        )

        /**
         * 降噪适配表：按设备名关键字匹配。
         * programmable=false 的品牌仅提供操作指引（耳机实体按键/厂商 App）。
         */
        private val ANC_ADAPTERS: List<Pair<AncNameMatcher, AncVendorAdapter>> = listOf(
            // 小米/Redmi 必须排在含裸 "BUDS" 关键词的三星条目之前，否则 Redmi Buds 被误判三星
            AncNameMatcher(contains = listOf("REDMI", "XIAOMI", "小米", "MI BUDS")) to
                AncVendorAdapter(
                    "小米", false,
                    "小米/Redmi 耳机需长按耳机切换降噪，或使用小米耳机 App"
                ),
            AncNameMatcher(prefixes = listOf("WH-", "WF-"), contains = listOf("SONY", "索尼")) to
                AncVendorAdapter(
                    "索尼", false,
                    "索尼耳机需在耳机上按 NC/AMB 按键切换，或使用 Sony Headphones Connect App"
                ),
            AncNameMatcher(contains = listOf("AIRPODS", "AIRPOD", "BEATS")) to
                AncVendorAdapter(
                    "苹果/Beats", false,
                    "AirPods 需长按耳机柄或控制中心切换降噪，或使用 iPhone 设置"
                ),
            AncNameMatcher(contains = listOf("FREEBUDS")) to
                AncVendorAdapter(
                    "华为", false,
                    "华为 FreeBuds 需双指长捏或使用智慧生活 App 切换降噪"
                ),
            AncNameMatcher(contains = listOf("ENCO")) to
                AncVendorAdapter(
                    "OPPO/一加", false,
                    "Enco 系列需长按耳机或使用欢律 App 切换降噪"
                ),
            AncNameMatcher(contains = listOf("GALAXY BUDS", "BUDS")) to
                AncVendorAdapter(
                    "三星", false,
                    "Galaxy Buds 需长按耳机或使用 Galaxy Wearable App 切换降噪"
                )
        )

        /**
         * 从 dumpsys bluetooth_manager 输出解析指定设备电量。
         * 兼容多种 ROM 格式：在设备地址行之后查找 battery 关键字与百分比数字。
         */
        fun parseBatteryForDevice(dumpsysOutput: String, deviceAddress: String): Int? {
            if (dumpsysOutput.isBlank() || deviceAddress.isBlank()) return null
            val lines = dumpsysOutput.lines()
            val addrIdx = lines.indexOfFirst { it.contains(deviceAddress, ignoreCase = true) }
            if (addrIdx < 0) return null
            // 在设备块内（下一个地址行之前）查找 battery
            for (i in (addrIdx + 1) until minOf(addrIdx + 40, lines.size)) {
                val line = lines[i]
                if (line.contains(deviceAddress, ignoreCase = true)) break
                val lower = line.lowercase()
                if ("battery" in lower || "电量" in line) {
                    val num = Regex("(\\d{1,3})\\s*%?").find(line.substringAfter("battery")
                        .ifBlank { line })?.groupValues?.get(1)?.toIntOrNull()
                    if (num != null && num in 0..100) return num
                }
            }
            return null
        }

        /** 从 dumpsys 输出解析 A2DP 已选编解码器（sbc/aac/ldac/aptx 等） */
        fun parseSelectedCodec(dumpsysOutput: String, deviceAddress: String): String? {
            if (dumpsysOutput.isBlank()) return null
            val lines = dumpsysOutput.lines()
            val addrIdx = lines.indexOfFirst { it.contains(deviceAddress, ignoreCase = true) }
            val searchLines = if (addrIdx >= 0) {
                lines.subList(addrIdx, minOf(addrIdx + 60, lines.size))
            } else {
                lines
            }
            val regex = Regex("codec\\s*[:=]\\s*([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            for (line in searchLines) {
                val m = regex.find(line) ?: continue
                val value = m.groupValues[1]
                if (value.length in 2..12) return value.uppercase()
            }
            return null
        }
    }
}

/** 降噪品牌名匹配器：前缀或包含任一关键字即命中（大小写不敏感） */
data class AncNameMatcher(
    val prefixes: List<String> = emptyList(),
    val contains: List<String> = emptyList()
) {
    fun matches(deviceName: String): Boolean {
        val upper = deviceName.uppercase()
        if (prefixes.any { upper.startsWith(it.uppercase()) }) return true
        if (contains.any { upper.contains(it.uppercase()) }) return true
        return false
    }
}
