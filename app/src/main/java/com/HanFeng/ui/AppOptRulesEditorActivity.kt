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
import com.HanFeng.databinding.ActivityAppoptRulesEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AppOpt 线程规则全屏编辑器 —— 大编辑区 + 实时规则统计 + 模板插入 + 恢复默认。
 */
class AppOptRulesEditorActivity : BaseActivity() {

    private lateinit var binding: ActivityAppoptRulesEditorBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAppoptRulesEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top + 8.dp, view.paddingRight, bars.bottom + 16.dp)
            insets
        }

        binding.btnBack.setOnClickListener { finish() }

        binding.etRules.setText(PerformanceTunerRepository.getAppOptRuleText(this))
        updateRuleStats()

        binding.etRules.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateRuleStats()
            }
        })

        binding.btnRestoreDefault.setOnClickListener {
            PerformanceTunerRepository.restoreDefaultAppOptRules(this)
            binding.etRules.setText(PerformanceTunerRepository.getAppOptRuleText(this))
            updateRuleStats()
            showShortToast("已恢复出厂默认规则")
        }

        binding.btnInsertTemplate.setOnClickListener {
            val current = binding.etRules.text?.toString().orEmpty()
            val template = "\n# ---------- 新应用 ----------\ncom.example.app{RenderThread}=2-7\ncom.example.app=0-6\n"
            binding.etRules.setText(current + template)
            binding.etRules.setSelection(binding.etRules.length())
        }

        binding.btnSave.setOnClickListener {
            val text = binding.etRules.text?.toString().orEmpty()
            PerformanceTunerRepository.setAppOptRuleText(this, text)
            if (PerformanceTunerRepository.isAppOptEnabled(this)) {
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        PerfTunerManager.applyAppOptRules(this@AppOptRulesEditorActivity) &&
                            PerfTunerManager.restartAppOpt(this@AppOptRulesEditorActivity)
                    }
                    showShortToast(if (ok) "规则已保存并重启 AppOpt" else "已保存，但 AppOpt 重启失败")
                }
            } else {
                showShortToast("规则已保存")
            }
            finish()
        }
    }

    private fun updateRuleStats() {
        val text = binding.etRules.text?.toString().orEmpty()
        val valid = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter { it.contains("=") }
            .count()
        binding.tvRuleStats.text = "有效规则：$valid 条 · 共 ${text.lineSequence().count()} 行"
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, AppOptRulesEditorActivity::class.java)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
