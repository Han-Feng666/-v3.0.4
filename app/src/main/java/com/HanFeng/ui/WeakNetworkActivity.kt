package com.HanFeng.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.WeakNetworkController
import com.HanFeng.model.WeakNetworkParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网络弱网模拟设置页
 * - 总开关：开启/关闭弱网模拟（全局直通整形引擎，单 App 时重建 VPN 会话）
 * - 作用范围：全局 / 指定单个 App（广告拦截临时仅作用于该 App）
 * - 参数：附加延迟 / 抖动 / 丢包率 / 上下行限速
 * - 音量键控制：可选开关（默认关闭）
 */
class WeakNetworkActivity : AppCompatActivity() {

    private lateinit var switchEnable: SwitchCompat
    private lateinit var switchVolumeKey: SwitchCompat
    private lateinit var tvStatus: TextView
    private lateinit var tvTarget: TextView
    private lateinit var editLatency: EditText
    private lateinit var editJitter: EditText
    private lateinit var editLoss: EditText
    private lateinit var editDown: EditText
    private lateinit var editUp: EditText

    private var currentTargetPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weak_network)

        ensureAppBackground()

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        switchEnable = findViewById(R.id.switchEnable)
        switchVolumeKey = findViewById(R.id.switchVolumeKey)
        tvStatus = findViewById(R.id.tvWeakNetStatus)
        tvTarget = findViewById(R.id.tvWeakNetTarget)
        editLatency = findViewById(R.id.editLatency)
        editJitter = findViewById(R.id.editJitter)
        editLoss = findViewById(R.id.editLoss)
        editDown = findViewById(R.id.editDown)
        editUp = findViewById(R.id.editUp)

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveParams() }
        findViewById<Button>(R.id.btnSelectTarget).setOnClickListener { showAppPicker() }
        findViewById<Button>(R.id.btnClearTarget).setOnClickListener { clearTarget() }

        syncUiFromPrefs()

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            WeakNetworkController.setEnabled(this, isChecked)
            refreshStatus()
        }
        switchVolumeKey.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                FeatureSettingsRepository.setWeakNetVolumeKeyEnabled(this, true)
                if (!com.HanFeng.service.AutoComboAccessibilityService.isEnabled(this)) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("需要开启辅助功能")
                        .setMessage("音量键切换弱网需要使用系统辅助功能监听音量键。开启后按音量键将直接切换弱网开关，不再调节音量。")
                        .setPositiveButton("去开启") { _, _ ->
                            com.HanFeng.service.AutoComboAccessibilityService
                                .openAccessibilitySettings(this)
                        }
                        .setNegativeButton("暂不", null)
                        .show()
                }
            } else {
                FeatureSettingsRepository.setWeakNetVolumeKeyEnabled(this, false)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncUiFromPrefs()
    }

    private fun syncUiFromPrefs() {
        val enabled = FeatureSettingsRepository.isWeakNetEnabled(this)
        switchEnable.isChecked = enabled
        // 音量键实际可用性取决于辅助服务是否被用户在系统里开启
        val volumeEnabled = FeatureSettingsRepository.isWeakNetVolumeKeyEnabled(this) &&
            com.HanFeng.service.AutoComboAccessibilityService.isEnabled(this)
        switchVolumeKey.isChecked = volumeEnabled

        val params = FeatureSettingsRepository.getWeakNetworkParams(this)
        editLatency.setText(params.latencyMs.takeIf { it > 0 }?.toString() ?: "")
        editJitter.setText(params.jitterMs.takeIf { it > 0 }?.toString() ?: "")
        editLoss.setText(params.lossPercent.takeIf { it > 0 }?.toString() ?: "")
        editDown.setText(params.downKbps.takeIf { it > 0 }?.toString() ?: "")
        editUp.setText(params.upKbps.takeIf { it > 0 }?.toString() ?: "")

        currentTargetPackage = FeatureSettingsRepository.getWeakNetTargetPackage(this)
        refreshTargetText()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = FeatureSettingsRepository.isWeakNetEnabled(this)
        tvStatus.text = if (enabled) "当前：已开启" else "当前：未开启"
    }

    private fun refreshTargetText() {
        val pkg = currentTargetPackage
        if (pkg.isNullOrBlank()) {
            tvTarget.text = "全局（所有经过 VPN 的流量）"
            return
        }
        val label = queryLabel(pkg)
        tvTarget.text = if (label == null) pkg else "$label\n$pkg"
    }

    private fun queryLabel(packageName: String): String? {
        return runCatching {
            val info: ApplicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
    }

    private fun saveParams() {
        val params = WeakNetworkParams(
            latencyMs = parseInt(editLatency),
            jitterMs = parseInt(editJitter),
            lossPercent = parseInt(editLoss),
            downKbps = parseInt(editDown),
            upKbps = parseInt(editUp)
        )
        FeatureSettingsRepository.setWeakNetworkParams(this, params)
        WeakNetworkController.applyConfigChange(this, targetChanged = false)
        android.widget.Toast.makeText(this, "弱网参数已保存${if (FeatureSettingsRepository.isWeakNetEnabled(this)) "并立即生效" else ""}", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun parseInt(edit: EditText): Int {
        return edit.text.toString().trim().toIntOrNull() ?: 0
    }

    private fun clearTarget() {
        currentTargetPackage = null
        FeatureSettingsRepository.setWeakNetTargetPackage(this, null)
        WeakNetworkController.applyConfigChange(this, targetChanged = true)
        refreshTargetText()
        android.widget.Toast.makeText(this, "已切换为全局模式", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showAppPicker() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pick_weak_net_app, null)
        val searchInput = dialogView.findViewById<EditText>(R.id.editAppSearch)
        val rvList = dialogView.findViewById<RecyclerView>(R.id.rvAppList)
        val emptyText = dialogView.findViewById<TextView>(R.id.tvAppPickEmpty)

        val dialog = StableDialog.builder(this)
            .setTitle("选择弱网目标 App")
            .setView(dialogView)
            .setNegativeButton("取消", null)
            .create()

        val adapter = WeakNetAppPickerAdapter { app ->
            currentTargetPackage = app.packageName
            FeatureSettingsRepository.setWeakNetTargetPackage(this, app.packageName)
            WeakNetworkController.applyConfigChange(this, targetChanged = true)
            refreshTargetText()
            dialog.dismiss()
        }
        rvList.layoutManager = LinearLayoutManager(this)
        rvList.adapter = adapter
        emptyText.visibility = View.GONE

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString().orEmpty())
            }
        })

        dialog.show()

        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                loadInstalledApps()
            }
            if (dialog.isShowing) {
                adapter.submit(apps)
                if (apps.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun loadInstalledApps(): List<WeakNetAppInfo> {
        return runCatching {
            val pm = packageManager
            pm.getInstalledApplications(0).asSequence()
                .filter { info ->
                    (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        info.packageName != packageName
                }
                .map { info ->
                    WeakNetAppInfo(
                        packageName = info.packageName,
                        label = pm.getApplicationLabel(info).toString().ifBlank { info.packageName }
                    )
                }
                .sortedBy { it.label.lowercase() }
                .toList()
        }.getOrDefault(emptyList())
    }

    private class WeakNetAppInfo(
        val packageName: String,
        val label: String
    )

    private inner class WeakNetAppPickerAdapter(
        private val onClick: (WeakNetAppInfo) -> Unit
    ) : RecyclerView.Adapter<WeakNetAppPickerAdapter.ViewHolder>() {

        private var allItems: List<WeakNetAppInfo> = emptyList()
        private var visibleItems: List<WeakNetAppInfo> = emptyList()
        private var filterText: String = ""

        fun submit(items: List<WeakNetAppInfo>) {
            allItems = items
            applyFilter()
        }

        fun filter(text: String) {
            filterText = text.trim().lowercase()
            applyFilter()
        }

        private fun applyFilter() {
            visibleItems = if (filterText.isBlank()) {
                allItems
            } else {
                allItems.filter {
                    it.label.lowercase().contains(filterText) ||
                        it.packageName.lowercase().contains(filterText)
                }
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pick_weak_net_app, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = visibleItems.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(visibleItems[position])
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvLabel: TextView = itemView.findViewById(R.id.tvPickAppLabel)
            private val tvPackage: TextView = itemView.findViewById(R.id.tvPickAppPackage)

            fun bind(app: WeakNetAppInfo) {
                tvLabel.text = app.label
                tvPackage.text = app.packageName
                itemView.setOnClickListener { onClick(app) }
            }
        }
    }
}