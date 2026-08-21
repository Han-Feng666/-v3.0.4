package com.HanFeng.data

import android.content.Context

object PerformanceTunerRepository {
    private const val PREFS = "performance_tuner"

    const val KEY_SCENE_ENABLED = "scene_enabled"
    const val KEY_SCENE_MODE = "scene_mode"
    const val KEY_SCENE_PROFILE = "scene_profile"
    const val KEY_SCENE_CUSTOM_PARAMS = "scene_custom_params"
    const val KEY_SCENE_FMAX_CAP = "scene_fmax_cap"
    const val KEY_SCENE_THERMAL = "scene_thermal"
    const val KEY_APPOPT_ENABLED = "appopt_enabled"
    const val KEY_APPOPT_RULE_TEXT = "appopt_rule_text"
    const val KEY_APPOPT_CPU_CONTROL = "appopt_cpu_control"
    const val KEY_APPOPT_DAEMON = "appopt_daemon"
    const val KEY_EVENT_REPLAY = "event_replay"

    const val SCENE_MODE_STANDALONE = "standalone"
    const val SCENE_MODE_SCENE_DEP = "scene_dep"

    const val PROFILE_BALANCED = "balanced"
    const val PROFILE_PERFORMANCE = "performance"
    const val PROFILE_GAME = "game"
    const val PROFILE_CUSTOM = "custom"
    const val PROFILE_DEFAULT = "default"

    const val DEPLOY_DIR = "/data/adb/HanFengPerf"
    const val SCENE_DIR = "$DEPLOY_DIR/scene"
    const val APPOPT_DIR = "$DEPLOY_DIR/appopt"
    const val SCENE_LOG = "$DEPLOY_DIR/scene.log"
    const val APPOPT_LOG = "$DEPLOY_DIR/appopt.log"
    const val SCENE_PID = "$DEPLOY_DIR/scene.pid"
    const val APPOPT_PID = "$DEPLOY_DIR/appopt.pid"
    const val SCENE_DAEMON = "$SCENE_DIR/scene_daemon.sh"
    const val APPOPT_DAEMON = "$APPOPT_DIR/appopt_daemon.sh"

    private const val DEFAULT_APPOPT_RULES = """# 线程优化规则 (AppOpt 格式)
# 每条规则: 包名{线程名}=CPU范围
# 不指定 {线程名} 时对该应用全部线程生效
# # 开头为注释

# ---------- 王者荣耀 ----------
com.tencent.tmgp.sgame{UnityMain}=2-7
com.tencent.tmgp.sgame{RenderThread}=2-7
com.tencent.tmgp.sgame{.*}=2-6
com.tencent.tmgp.sgame=0-6
# 附加进程
com.tencent.tmgp.sgame:.{UnityMain}=2-7

# ---------- 和平精英 ----------
com.tencent.tmgp.pubgmhd{RenderThread}=2-7
com.tencent.tmgp.pubgmhd{.*}=2-6
com.tencent.tmgp.pubgmhd=0-6

# ---------- 原神 ----------
com.miHoYo.Yuanshen{UnityMain}=2-7
com.miHoYo.Yuanshen{UnityGfx}=2-7
com.miHoYo.Yuanshen{.*}=2-6
com.miHoYo.Yuanshen=0-6

# ---------- 星穹铁道 ----------
com.miHoYo.hkrpg{UnityMain}=2-7
com.miHoYo.hkrpg{UnityGfx}=2-7
com.miHoYo.hkrpg{.*}=2-6
com.miHoYo.hkrpg=0-6

# ---------- 视频类 ----------
com.ss.android.ugc.aweme{RenderThread}=2-7
com.ss.android.ugc.aweme=0-6
com.smile.gifmaker{RenderThread}=2-7
com.smile.gifmaker=0-6
tv.danmaku.bili{RenderThread}=2-7
tv.danmaku.bili=0-6

# ---------- 购物类 ----------
com.taobao.idlefish{RenderThread}=2-7
com.taobao.idlefish{1.ui}=2-7
com.taobao.idlefish{1.raster}=2-7
com.taobao.idlefish=0-6
com.jingdong.app.mall{RenderThread}=2-7
com.jingdong.app.mall=0-6

# ---------- 浏览器 WebView 渲染 ----------
mark.via{RenderThread}=2-7
mark.via{.*}=0-6
"""

    private const val DEFAULT_SCENE_CUSTOM_PARAMS =
        "fmax_cap=auto\n" +
            "adaptive=0\n" +
            "thermal_guard=49500\n" +
            "disable_migt=1\n" +
            "hispd_load=90\n"

    const val DEFAULT_SCENE_FMAX_CAP = "auto"
    const val DEFAULT_SCENE_THERMAL = "49500"

    fun isSceneEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCENE_ENABLED, false)

    fun setSceneEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SCENE_ENABLED, enabled).apply()
    }

    fun getSceneMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SCENE_MODE, SCENE_MODE_STANDALONE) ?: SCENE_MODE_STANDALONE

    fun setSceneMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SCENE_MODE, mode).apply()
    }

    fun getSceneProfile(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SCENE_PROFILE, PROFILE_PERFORMANCE) ?: PROFILE_PERFORMANCE

    fun setSceneProfile(context: Context, profile: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SCENE_PROFILE, profile).apply()
    }

    fun getSceneCustomParams(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SCENE_CUSTOM_PARAMS, DEFAULT_SCENE_CUSTOM_PARAMS)
            ?: DEFAULT_SCENE_CUSTOM_PARAMS

    fun setSceneCustomParams(context: Context, content: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SCENE_CUSTOM_PARAMS, content).apply()
    }

    /** 频率上限：auto 或 KHz 数值 */
    fun getSceneFmaxCap(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SCENE_FMAX_CAP, DEFAULT_SCENE_FMAX_CAP)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SCENE_FMAX_CAP

    fun setSceneFmaxCap(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SCENE_FMAX_CAP, value.trim().ifBlank { DEFAULT_SCENE_FMAX_CAP }).apply()
    }

    /** 温度墙（防热降频阈值，单位与设备一致，默认 49500） */
    fun getSceneThermal(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SCENE_THERMAL, DEFAULT_SCENE_THERMAL)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SCENE_THERMAL

    fun setSceneThermal(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SCENE_THERMAL, value.trim().ifBlank { DEFAULT_SCENE_THERMAL }).apply()
    }

    /** 恢复 SCENE 出厂默认：档位保持、自定义参数与频率/温度墙回到默认 */
    fun restoreDefaultSceneConfig(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SCENE_CUSTOM_PARAMS, DEFAULT_SCENE_CUSTOM_PARAMS)
            .putString(KEY_SCENE_FMAX_CAP, DEFAULT_SCENE_FMAX_CAP)
            .putString(KEY_SCENE_THERMAL, DEFAULT_SCENE_THERMAL)
            .apply()
    }

    /** 恢复 AppOpt 出厂默认规则 */
    fun restoreDefaultAppOptRules(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_APPOPT_RULE_TEXT, DEFAULT_APPOPT_RULES)
            .apply()
    }

    fun isAppOptEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_APPOPT_ENABLED, false)

    fun setAppOptEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_APPOPT_ENABLED, enabled).apply()
    }

    fun getAppOptRuleText(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_APPOPT_RULE_TEXT, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_APPOPT_RULES

    fun setAppOptRuleText(context: Context, content: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_APPOPT_RULE_TEXT, content).apply()
    }

    fun isAppOptCpuControlEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_APPOPT_CPU_CONTROL, true)

    fun setAppOptCpuControlEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_APPOPT_CPU_CONTROL, enabled).apply()
    }

    fun isAppOptDaemonEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_APPOPT_DAEMON, true)

    fun setAppOptDaemonEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_APPOPT_DAEMON, enabled).apply()
    }

    fun isEventReplayEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_EVENT_REPLAY, false)

    fun setEventReplayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_EVENT_REPLAY, enabled).apply()
    }
}