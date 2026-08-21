package com.HanFeng.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.HanFeng.adblocker.shizuku.PerfTunerManager
import com.HanFeng.data.PerformanceTunerRepository
import com.HanFeng.databinding.ActivitySceneParamsEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SceneParamsEditorActivity : BaseActivity() {

    private lateinit var binding: ActivitySceneParamsEditorBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySceneParamsEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top + 8.dp, view.paddingRight, bars.bottom + 16.dp)
            insets
        }

        binding.btnBack.setOnClickListener { finish() }

        loadValues()

        binding.btnRestoreDefault.setOnClickListener {
            PerformanceTunerRepository.restoreDefaultSceneConfig(this)
            loadValues()
            showShortToast("已恢复出厂默认参数")
        }

        binding.btnSave.setOnClickListener {
            if (validateAndSave()) {
                showShortToast("参数已保存")
                if (PerformanceTunerRepository.isSceneEnabled(this)) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            PerfTunerManager.deployScene(this@SceneParamsEditorActivity)
                            PerfTunerManager.startScene(this@SceneParamsEditorActivity)
                        }
                    }
                }
                finish()
            }
        }
    }

    private fun loadValues() {
        val pairs = PerformanceTunerRepository.getSceneCustomParams(this)
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .associate { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }

        binding.etFmaxCap.setText(PerformanceTunerRepository.getSceneFmaxCap(this))

        val thermalRaw = PerformanceTunerRepository.getSceneThermal(this)
        val thermalCelsius = rawThermalToCelsius(thermalRaw)
        binding.etThermal.setText(thermalCelsius)

        when (pairs["adaptive"] ?: "0") {
            "1" -> binding.rgAdaptive.check(com.HanFeng.R.id.rbAdaptiveOn)
            else -> binding.rgAdaptive.check(com.HanFeng.R.id.rbAdaptiveOff)
        }
        when (pairs["disable_migt"] ?: "1") {
            "0" -> binding.rgDisableMigt.check(com.HanFeng.R.id.rbMigtKeep)
            else -> binding.rgDisableMigt.check(com.HanFeng.R.id.rbMigtOff)
        }
        binding.etHispeedLoad.setText(pairs["hispd_load"] ?: "90")
    }

    private fun validateAndSave(): Boolean {
        val fmax = binding.etFmaxCap.text?.toString()?.trim().orEmpty()
        if (fmax.isNotEmpty() && fmax != "auto" && !fmax.matches(Regex("\\d+"))) {
            binding.tvResult.text = "频率上限只能填 auto 或数字（KHz）"
            return false
        }

        val thermalText = binding.etThermal.text?.toString()?.trim().orEmpty()
        val thermalMillis = if (thermalText.isNotEmpty()) {
            val celsius = thermalText.toDoubleOrNull()
            if (celsius == null || celsius < 0.0 || celsius > 100.0) {
                binding.tvResult.text = "温度墙需在 0-100 °C 之间"
                return false
            }
            (celsius * 1000).toLong().toString()
        } else {
            ""
        }

        val hispeed = binding.etHispeedLoad.text?.toString()?.trim().orEmpty()
        val hispeedInt = hispeed.toIntOrNull()
        if (hispeed.isNotEmpty() && (hispeedInt == null || hispeedInt !in 1..100)) {
            binding.tvResult.text = "高频负载阈值需在 1-100 之间"
            return false
        }

        val adaptive = if (binding.rgAdaptive.checkedRadioButtonId == com.HanFeng.R.id.rbAdaptiveOn) "1" else "0"
        val disableMigt = if (binding.rgDisableMigt.checkedRadioButtonId == com.HanFeng.R.id.rbMigtKeep) "0" else "1"

        PerformanceTunerRepository.setSceneFmaxCap(this, fmax.ifBlank { PerformanceTunerRepository.DEFAULT_SCENE_FMAX_CAP })
        PerformanceTunerRepository.setSceneThermal(this, thermalMillis.ifBlank { PerformanceTunerRepository.DEFAULT_SCENE_THERMAL })

        val params = buildString {
            append("fmax_cap=").append(PerformanceTunerRepository.getSceneFmaxCap(this@SceneParamsEditorActivity)).append('\n')
            append("adaptive=").append(adaptive).append('\n')
            append("thermal_guard=").append(PerformanceTunerRepository.getSceneThermal(this@SceneParamsEditorActivity)).append('\n')
            append("disable_migt=").append(disableMigt).append('\n')
            append("hispd_load=").append(hispeed.ifBlank { "90" }).append('\n')
        }
        PerformanceTunerRepository.setSceneCustomParams(this, params)
        binding.tvResult.text = ""
        return true
    }

    private fun rawThermalToCelsius(raw: String): String {
        val millis = raw.toLongOrNull() ?: return ""
        return "%.1f".format(millis / 1000.0)
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, SceneParamsEditorActivity::class.java)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}