package com.HanFeng.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.collection.LruCache
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R
import com.HanFeng.data.PerformanceTunerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

data class AppOptAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    var currentAffinity: String
)

class AppOptAppsActivity : AppCompatActivity() {

    private lateinit var rvList: RecyclerView
    private lateinit var tvSummary: TextView
    private lateinit var adapter: AppOptAppsAdapter
    private val allApps = mutableListOf<AppOptAppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_appopt_apps)

        ensureAppBackground()

        rvList = findViewById(R.id.rvAppOptApps)
        tvSummary = findViewById(R.id.tvAppOptSummary)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        adapter = AppOptAppsAdapter(this) { app ->
            showAffinityDialog(app)
        }
        rvList.layoutManager = LinearLayoutManager(this)
        rvList.adapter = adapter
        rvList.setHasFixedSize(true)

        loadApps()
    }

    private fun loadApps() {
        tvSummary.text = "正在加载已安装应用..."
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                loadInstalledApps()
            }
            allApps.clear()
            allApps.addAll(apps)
            adapter.submitList(apps)
            val withRules = apps.count { it.currentAffinity.isNotEmpty() }
            tvSummary.text = "第三方应用 ${apps.size} 个 · 已设置 $withRules 个"
        }
    }

    private fun loadInstalledApps(): List<AppOptAppInfo> {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)

        val existingRules = PerformanceTunerRepository.getAppOptRuleText(this)
        val ruleMap = parseExistingRules(existingRules)

        return apps.asSequence()
            .filter { info ->
                (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .map { info ->
                val label = pm.getApplicationLabel(info).toString()
                val affinity = ruleMap[info.packageName] ?: ""
                AppOptAppInfo(
                    packageName = info.packageName,
                    label = label,
                    icon = null,
                    currentAffinity = affinity
                )
            }
            .sortedWith(compareByDescending<AppOptAppInfo> { it.currentAffinity.isNotEmpty() }.thenBy { it.label.lowercase() })
            .toList()
    }

    private fun parseExistingRules(rulesText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        rulesText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    val pkg = if (key.contains("{")) {
                        key.substringBefore("{")
                    } else {
                        key
                    }
                    if (result.containsKey(pkg).not()) {
                        result[pkg] = value
                    }
                }
            }
        return result
    }

    private fun showAffinityDialog(app: AppOptAppInfo) {
        val presets = arrayOf("0-3", "4-7", "0-7", "自定义")
        val presetsDisplay = arrayOf("小核 0-3", "大核 4-7", "全部核心 0-7", "自定义")
        val currentAffinity = app.currentAffinity

        var selectedIndex = presets.indexOfFirst { it == currentAffinity }
        if (selectedIndex < 0) selectedIndex = 3

        val builder = AlertDialog.Builder(this)
        builder.setTitle("设置 CPU 亲和性")
        builder.setMessage("${app.label} (${app.packageName})")

        val layout = LayoutInflater.from(this).inflate(R.layout.dialog_affinity_selector, null)
        val radioGroup = layout.findViewById<RadioGroup>(R.id.rgAffinityPresets)
        val customLayout = layout.findViewById<View>(R.id.layoutCustomAffinity)
        val etCustom = layout.findViewById<EditText>(R.id.etCustomAffinity)

        for (i in presets.indices) {
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = presetsDisplay[i]
            rb.setTextColor(resources.getColor(R.color.hf_text_primary, null))
            rb.textSize = 14f
            radioGroup.addView(rb)
        }

        radioGroup.check(radioGroup.getChildAt(selectedIndex).id)
        if (selectedIndex == 3) {
            customLayout.visibility = View.VISIBLE
            etCustom.setText(currentAffinity)
        }

        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            val idx = group.indexOfChild(group.findViewById(checkedId))
            customLayout.visibility = if (idx == 3) View.VISIBLE else View.GONE
        }

        builder.setView(layout)

        builder.setPositiveButton("确定") { _, _ ->
            val checkedId = radioGroup.checkedRadioButtonId
            val idx = radioGroup.indexOfChild(radioGroup.findViewById(checkedId))
            val affinity = if (idx == 3) {
                etCustom.text.toString().trim()
            } else {
                presets[idx]
            }
            if (affinity.isNotEmpty()) {
                saveRule(app.packageName, affinity)
                app.currentAffinity = affinity
                adapter.notifyItemChanged(allApps.indexOf(app))
                updateSummary()
            }
        }

        builder.setNegativeButton("移除规则") { _, _ ->
            removeRule(app.packageName)
            app.currentAffinity = ""
            adapter.notifyItemChanged(allApps.indexOf(app))
            updateSummary()
        }

        builder.setNeutralButton("取消", null)
        builder.show()
    }

    private fun saveRule(packageName: String, affinity: String) {
        val existingRules = PerformanceTunerRepository.getAppOptRuleText(this)
        val lines = existingRules.lines().toMutableList()

        val replaced = mutableListOf<String>()
        var found = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                val key = trimmed.substringBefore("=").trim()
                val pkg = if (key.contains("{")) key.substringBefore("{") else key
                if (pkg == packageName && !key.contains("{")) {
                    replaced.add("$packageName=$affinity")
                    found = true
                    continue
                }
            }
            replaced.add(line)
        }
        if (!found) {
            replaced.add("$packageName=$affinity")
        }
        PerformanceTunerRepository.setAppOptRuleText(this, replaced.joinToString("\n"))
    }

    private fun removeRule(packageName: String) {
        val existingRules = PerformanceTunerRepository.getAppOptRuleText(this)
        val lines = existingRules.lines()
        val filtered = lines.filter { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                val key = trimmed.substringBefore("=").trim()
                val pkg = if (key.contains("{")) key.substringBefore("{") else key
                pkg != packageName
            } else {
                true
            }
        }
        PerformanceTunerRepository.setAppOptRuleText(this, filtered.joinToString("\n"))
    }

    private fun updateSummary() {
        val withRules = allApps.count { it.currentAffinity.isNotEmpty() }
        tvSummary.text = "第三方应用 ${allApps.size} 个 · 已设置 $withRules 个"
    }
}

class AppOptAppsAdapter(
    private val context: Context,
    private val onItemClick: (AppOptAppInfo) -> Unit
) : ListAdapter<AppOptAppInfo, AppOptAppsAdapter.Holder>(DIFF) {

    private val iconCache = LruCache<String, Drawable>(64)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_appopt_app, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvPkg: TextView = itemView.findViewById(R.id.tvPackageName)
        private val tvAffinity: TextView = itemView.findViewById(R.id.tvCpuAffinity)

        fun bind(item: AppOptAppInfo) {
            tvName.text = item.label
            tvPkg.text = item.packageName
            tvAffinity.text = if (item.currentAffinity.isNotEmpty()) {
                "CPU 亲和性: ${item.currentAffinity}"
            } else {
                "未设置"
            }

            val cached = iconCache[item.packageName]
            if (cached != null) {
                ivIcon.setImageDrawable(cached)
            } else {
                ivIcon.setImageDrawable(null)
                IconExecutorPool.executor.execute {
                    val dr = runCatching {
                        context.packageManager.getApplicationIcon(item.packageName)
                    }.getOrNull()
                    if (dr != null) {
                        iconCache.put(item.packageName, dr)
                        mainHandler.post {
                            val pos = bindingAdapterPosition
                            if (pos in 0 until itemCount &&
                                getItem(pos).packageName == item.packageName
                            ) {
                                ivIcon.setImageDrawable(dr)
                            }
                        }
                    }
                }
            }

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in 0 until itemCount) {
                    onItemClick(getItem(pos))
                }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppOptAppInfo>() {
            override fun areItemsTheSame(
                oldItem: AppOptAppInfo,
                newItem: AppOptAppInfo
            ): Boolean = oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(
                oldItem: AppOptAppInfo,
                newItem: AppOptAppInfo
            ): Boolean = oldItem == newItem
        }
    }
}