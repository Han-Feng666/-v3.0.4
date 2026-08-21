package com.HanFeng.data

import android.content.Context
import com.HanFeng.model.ComboScript
import com.HanFeng.model.ComboStep
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * 自动连招脚本仓库：脚本 CRUD、当前脚本、总开关，持久化在 feature_settings
 * 的 SharedPreferences（JSON 序列化，与 feature_settings 的弱网等设置同源）。
 */
object AutoComboRepository {
    private const val PREFS = "feature_settings"
    private const val KEY_ENABLED = "auto_combo_enabled"
    private const val KEY_SCRIPTS = "auto_combo_scripts_v1"
    private const val KEY_CURRENT_SCRIPT = "auto_combo_current_script"

    const val SPEED_MIN = 0.5f
    const val SPEED_MAX = 2.0f
    const val LOOP_COUNT_MIN = 1

    private val gson = Gson()
    private val listType = object : TypeToken<ArrayList<ComboScript>>() {}.type

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getCurrentScriptId(context: Context): String? =
        prefs(context).getString(KEY_CURRENT_SCRIPT, null)

    fun setCurrentScriptId(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_CURRENT_SCRIPT, id).apply()
    }

    /** 按最近更新倒序返回全部脚本。 */
    fun listScripts(context: Context): List<ComboScript> =
        readScripts(context).sortedByDescending { it.updatedAt }

    fun getScript(context: Context, id: String): ComboScript? =
        readScripts(context).firstOrNull { it.id == id }

    fun getCurrentScript(context: Context): ComboScript? {
        val currentId = getCurrentScriptId(context) ?: return null
        return getScript(context, currentId)
    }

    /**
     * 保存脚本（更新或新增）。字段会按合法范围钳制：
     * id 为空时生成新 id；变速钳制到 [SPEED_MIN, SPEED_MAX]；
     * 循环次数不小于 [LOOP_COUNT_MIN]；坐标不小于 0；持续时长不小于 1ms；间隔不小于 0ms。
     */
    fun saveScript(context: Context, input: ComboScript): ComboScript {
        return synchronized(this) {
            val scripts = readScripts(context).toMutableList()
            val now = System.currentTimeMillis()
            val script = sanitize(
                if (input.id.isBlank()) input.copy(id = UUID.randomUUID().toString()) else input
            ).copy(updatedAt = now, createdAt = if (input.createdAt > 0) input.createdAt else now)
            val index = scripts.indexOfFirst { it.id == script.id }
            if (index >= 0) {
                scripts[index] = script
            } else {
                scripts.add(script)
            }
            writeScripts(context, scripts)
            if (getCurrentScriptId(context) == null) {
                setCurrentScriptId(context, script.id)
            }
            script
        }
    }

    /** 删除脚本；若删除的是当前脚本，则自动回落到最近更新脚本或清空当前 id。 */
    fun deleteScript(context: Context, id: String) {
        synchronized(this) {
            val scripts = readScripts(context).toMutableList()
            scripts.removeIf { it.id == id }
            writeScripts(context, scripts)
            if (getCurrentScriptId(context) == id) {
                val next = scripts.maxByOrNull { it.updatedAt }
                setCurrentScriptId(context, next?.id)
            }
        }
    }

    /** 用已导入的脚本数据新建脚本（id 重置、时间重置），返回新脚本。 */
    fun importScript(context: Context, imported: ComboScript): ComboScript {
        return saveScript(
            context,
            imported.copy(id = UUID.randomUUID().toString(), createdAt = 0L)
        )
    }

    private fun readScripts(context: Context): List<ComboScript> {
        val raw = prefs(context).getString(KEY_SCRIPTS, null) ?: return emptyList()
        return runCatching {
            val parsed = gson.fromJson<List<ComboScript>>(raw, listType)
            parsed.orEmpty().map { sanitize(it) }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun writeScripts(context: Context, scripts: List<ComboScript>) {
        prefs(context).edit().putString(KEY_SCRIPTS, gson.toJson(scripts)).apply()
    }

    private fun sanitize(script: ComboScript): ComboScript {
        val speed = script.speed.coerceIn(SPEED_MIN, SPEED_MAX)
        val steps = script.steps.map { step ->
            step.copy(
                startX = step.startX.coerceAtLeast(0),
                startY = step.startY.coerceAtLeast(0),
                endX = step.endX.coerceAtLeast(0),
                endY = step.endY.coerceAtLeast(0),
                durationMs = step.durationMs.coerceAtLeast(1),
                startDelayMs = step.startDelayMs.coerceAtLeast(0)
            )
        }
        return script.copy(
            name = script.name.ifBlank { "连招脚本" },
            steps = steps,
            speed = speed,
            loopCount = script.loopCount.coerceAtLeast(LOOP_COUNT_MIN)
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}