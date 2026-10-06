package com.HanFeng.data

import android.content.Context

/**
 * 外设管理持久化：蓝牙耳机降噪/EQ 偏好、散热器配置。
 */
object PeripheralRepository {
    private const val PREFS = "peripheral_settings"

    private const val KEY_ANC_MODE_PREFIX = "anc_mode_"
    private const val KEY_EQ_BASS_PREFIX = "eq_bass_"
    private const val KEY_EQ_VIRTUAL_PREFIX = "eq_virtual_"
    private const val KEY_COOLER_LAST_MAC = "cooler_last_mac"
    private const val KEY_COOLER_LAST_NAME = "cooler_last_name"
    private const val KEY_HEADSET_LAST_MAC = "headset_last_mac"
    private const val KEY_HEADSET_LAST_NAME = "headset_last_name"
    private const val KEY_COOLER_POWER = "cooler_power"
    private const val KEY_COOLER_RGB_EFFECT = "cooler_rgb_effect"
    private const val KEY_COOLER_RGB_COLOR = "cooler_rgb_color"
    private const val KEY_COOLER_RGB_BRIGHTNESS = "cooler_rgb_brightness"
    private const val KEY_COOLER_AUTO_TUNER = "cooler_auto_tuner"

    const val ANC_OFF = "off"
    const val ANC_ON = "on"
    const val ANC_TRANSPARENCY = "transparency"

    // RGB 灯效
    const val RGB_EFFECT_OFF = "off"
    const val RGB_EFFECT_STATIC = "static"
    const val RGB_EFFECT_BREATH = "breath"
    const val RGB_EFFECT_RAINBOW = "rainbow"

    fun getAncMode(context: Context, deviceAddress: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ANC_MODE_PREFIX + deviceAddress, ANC_OFF) ?: ANC_OFF

    fun setAncMode(context: Context, deviceAddress: String, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ANC_MODE_PREFIX + deviceAddress, mode).apply()
    }

    fun getEqBass(context: Context, deviceAddress: String): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_EQ_BASS_PREFIX + deviceAddress, 0)

    fun setEqBass(context: Context, deviceAddress: String, strength: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_EQ_BASS_PREFIX + deviceAddress, strength).apply()
    }

    fun getEqVirtualizer(context: Context, deviceAddress: String): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_EQ_VIRTUAL_PREFIX + deviceAddress, 0)

    fun setEqVirtualizer(context: Context, deviceAddress: String, strength: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_EQ_VIRTUAL_PREFIX + deviceAddress, strength).apply()
    }

    fun getLastCoolerAddress(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COOLER_LAST_MAC, null)

    fun setLastHeadset(context: Context, address: String, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HEADSET_LAST_MAC, address)
            .putString(KEY_HEADSET_LAST_NAME, name)
            .apply()
    }

    fun getLastHeadsetAddress(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HEADSET_LAST_MAC, null)

    fun getLastHeadsetName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HEADSET_LAST_NAME, "") ?: ""

    fun setLastCooler(context: Context, address: String, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COOLER_LAST_MAC, address)
            .putString(KEY_COOLER_LAST_NAME, name)
            .apply()
    }

    fun getLastCoolerName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COOLER_LAST_NAME, "") ?: ""

    fun getCoolerPower(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_COOLER_POWER, 50)

    fun setCoolerPower(context: Context, power: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_COOLER_POWER, power.coerceIn(0, 100)).apply()
    }

    fun getCoolerRgbEffect(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COOLER_RGB_EFFECT, RGB_EFFECT_STATIC) ?: RGB_EFFECT_STATIC

    fun setCoolerRgbEffect(context: Context, effect: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COOLER_RGB_EFFECT, effect).apply()
    }

    fun getCoolerRgbColor(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_COOLER_RGB_COLOR, 0xFF3D5AFE.toInt())

    fun setCoolerRgbColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_COOLER_RGB_COLOR, color).apply()
    }

    fun getCoolerRgbBrightness(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_COOLER_RGB_BRIGHTNESS, 80)

    fun setCoolerRgbBrightness(context: Context, brightness: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_COOLER_RGB_BRIGHTNESS, brightness.coerceIn(0, 100)).apply()
    }

    fun isCoolAutoTunerEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_COOLER_AUTO_TUNER, false)

    fun setCoolAutoTunerEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_COOLER_AUTO_TUNER, enabled).apply()
    }
}
