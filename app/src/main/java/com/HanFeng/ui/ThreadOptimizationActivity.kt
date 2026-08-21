package com.HanFeng.ui

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.HanFeng.adblocker.shizuku.PerfTunerManager
import com.HanFeng.adblocker.shizuku.SuSession
import com.HanFeng.data.PerformanceTunerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThreadOptimizationActivity : BaseActivity() {

    private lateinit var binding: com.HanFeng.databinding.ActivityThreadOptimizationBinding
    private var rootReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = com.HanFeng.databinding.ActivityThreadOptimizationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val bgPath = com.HanFeng.data.FeatureSettingsRepository.getCustomBackgroundPath(this)
        if (!bgPath.isNullOrEmpty()) {
            binding.ivBackground.applyCustomFileBackground(bgPath)
        } else {
            binding.ivBackground.applyCustomAssetBackground("custom/background")
        }
        setupBackButton()
        setupAppOptUi()
        refreshStatus()
    }

    private fun setupBackButton() {
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun setupAppOptUi() {
        binding.switchAppOpt.setOnCheckedChangeListener { _, isChecked ->
            if (binding.switchAppOpt.tag != null) return@setOnCheckedChangeListener
            if (!rootReady) {
                showShortToast("Root 不可用")
                syncAppOptSwitch()
                return@setOnCheckedChangeListener
            }
            toggleAppOpt(isChecked)
        }

        binding.switchAppOptCpuControl.setOnCheckedChangeListener { _, isChecked ->
            PerformanceTunerRepository.setAppOptCpuControlEnabled(this, isChecked)
        }

        binding.btnEditRules.setOnClickListener {
            launchActivitySafely(
                AppOptRulesEditorActivity.createIntent(this),
                failureMessage = "打开规则编辑器失败"
            )
        }

        binding.btnAppOptLog.setOnClickListener { showAppOptLog() }

        binding.btnAppBinding.setOnClickListener {
            launchActivitySafely(
                Intent(this, AppOptAppsActivity::class.java),
                failureMessage = "打开应用线程绑定失败"
            )
        }
    }

    private fun toggleAppOpt(start: Boolean) {
        lifecycleScope.launch {
            if (start) {
                val started = withContext(Dispatchers.IO) {
                    PerfTunerManager.startAppOpt(this@ThreadOptimizationActivity)
                }
                if (!started) {
                    showShortToast("AppOpt 启动失败，请先查看日志")
                    syncAppOptSwitch()
                    return@launch
                }
                showShortToast("AppOpt 线程优化已启动")
            } else {
                withContext(Dispatchers.IO) { PerfTunerManager.stopAppOpt(this@ThreadOptimizationActivity) }
                showShortToast("AppOpt 已停止")
            }
            refreshStatus()
        }
    }

    private fun showAppOptLog() {
        lifecycleScope.launch {
            val log = withContext(Dispatchers.IO) { PerfTunerManager.dumpLog("appopt", 200) }
            StableDialog.builder(this@ThreadOptimizationActivity)
                .setTitle("AppOpt 日志（最近 200 行）")
                .setMessage(log.ifBlank { "(暂无日志)" })
                .setPositiveButton("关闭", null)
                .showSafely(this@ThreadOptimizationActivity, "Show appopt log failed")
        }
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            rootReady = withContext(Dispatchers.IO) {
                val s = SuSession.getInstance()
                s.isSessionOpen() || s.open(12)
            }
            if (!rootReady) {
                binding.tvStatus.text = "Root 不可用。该功能需要 Root（Magisk/KernelSU/APatch）才能部署与运行。"
                binding.switchAppOpt.isEnabled = false
                binding.btnAppOptLog.isEnabled = false
                return@launch
            }

            val deployed = withContext(Dispatchers.IO) { PerfTunerManager.isDeployed() }
            val status = withContext(Dispatchers.IO) { PerfTunerManager.status(this@ThreadOptimizationActivity) }

            val text = buildString {
                append("模块文件：").append(if (deployed) "已部署" else "未部署").appendLine()
                appendLine("— AppOpt 线程优化 —")
                append("状态：").append(if (status.appoptRunning) "运行中" else "未运行").appendLine()
                append("规则条数：").append(status.appoptRulesLines)
            }
            binding.tvStatus.text = text

            syncAppOptSwitch()

            binding.switchAppOpt.isEnabled = rootReady
            binding.btnAppOptLog.isEnabled = rootReady
        }
    }

    private fun syncAppOptSwitch() {
        val enabled = PerformanceTunerRepository.isAppOptEnabled(this)
        if (binding.switchAppOpt.isChecked == enabled) return
        binding.switchAppOpt.setOnCheckedChangeListener(null)
        binding.switchAppOpt.tag = true
        binding.switchAppOpt.isChecked = enabled
        binding.switchAppOpt.setOnCheckedChangeListener { _, isChecked ->
            if (binding.switchAppOpt.tag != null) return@setOnCheckedChangeListener
            if (!rootReady) {
                showShortToast("Root 不可用")
                syncAppOptSwitch()
                return@setOnCheckedChangeListener
            }
            toggleAppOpt(isChecked)
        }
        binding.switchAppOpt.tag = null
    }
}