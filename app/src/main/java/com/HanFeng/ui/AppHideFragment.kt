package com.HanFeng.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.HanFeng.R
import com.HanFeng.adblocker.shizuku.AppHideManager
import com.HanFeng.adblocker.shizuku.RootHideRepository
import com.HanFeng.model.InstalledApp

/**
 * 应用隐藏页签：勾选的应用对其它 App 不可见。
 *
 * 与「作用域」页签的区别：
 * - 作用域：对哪些 App 隐藏 Root 特征
 * - 应用隐藏：把哪些 App 从包查询结果中抹掉
 */
class AppHideFragment : Fragment() {

    private val manager = AppHideManager()
    private var allApps: List<InstalledApp> = emptyList()
    private var loadThread: Thread? = null
    private var loadVersion = 0
    private val handler = Handler(Looper.getMainLooper())
    private var searchInput: EditText? = null
    private var countText: TextView? = null
    private var appsContainer: LinearLayout? = null
    private var currentKeyword: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val scrollView = ScrollView(requireContext())
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 8.dp, 16.dp, 16.dp)
        }
        scrollView.addView(root)

        root.addView(TextView(requireContext()).apply {
            text = "勾选后，这些应用将不再被其它 App 发现"
            textSize = 14f
            setTextColor(resources.getColor(R.color.hf_text_primary, null))
            setPadding(0, 0, 0, 8.dp)
        })

        root.addView(TextView(requireContext()).apply {
            text = "隐藏后目标应用会从桌面和所有包查询中消失，可用「恢复」找回。\n" +
                "系统关键包与本应用不可隐藏。"
            textSize = 12f
            setTextColor(resources.getColor(R.color.hf_text_secondary, null))
            setPadding(0, 0, 0, 8.dp)
        })

        searchInput = EditText(requireContext()).apply {
            hint = "搜索应用名或包名"
            textSize = 14f
            setTextColor(resources.getColor(R.color.hf_text_primary, null))
            setHintTextColor(resources.getColor(R.color.hf_text_secondary, null))
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            setSingleLine(true)
        }
        root.addView(searchInput!!)

        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp, 0, 0)
        }
        btnRow.addView(Button(requireContext()).apply {
            text = "全选"
            textSize = 13f
            setOnClickListener { toggleHidden(true) }
        })
        btnRow.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(8.dp, 1)
        })
        btnRow.addView(Button(requireContext()).apply {
            text = "全不选"
            textSize = 13f
            setOnClickListener { toggleHidden(false) }
        })
        btnRow.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(8.dp, 1)
        })
        btnRow.addView(Button(requireContext()).apply {
            text = "恢复全部"
            textSize = 13f
            setOnClickListener { unhideAll() }
        })
        root.addView(btnRow)

        val ct = TextView(requireContext()).apply {
            text = "已隐藏: 0 个应用"
            textSize = 12f
            setTextColor(resources.getColor(R.color.hf_text_secondary, null))
            setPadding(0, 4.dp, 0, 4.dp)
        }
        this.countText = ct
        root.addView(ct)

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        this.appsContainer = container
        root.addView(container)

        searchInput!!.doAfterTextChanged {
            currentKeyword = it?.toString().orEmpty()
            renderList()
        }

        loadApps()
        return scrollView
    }

    override fun onResume() {
        super.onResume()
        if (view != null) {
            loadApps()
        }
    }

    private fun loadApps() {
        loadVersion++
        val requestVersion = loadVersion
        loadThread?.interrupt()
        val ctx = context ?: return
        val pm = ctx.packageManager
        val selfPkg = ctx.packageName
        loadThread = Thread {
            try {
                if (!SuSessionProvider.checkRoot()) {
                    handler.post {
                        if (requestVersion != loadVersion || isRemoving || isDetached) return@post
                        countText?.text = "未获取 Root 权限，请返回重新打开此页面"
                    }
                    return@Thread
                }
                val hiddenPackages = RootHideRepository.getHiddenApps(ctx)
                @Suppress("DEPRECATION")
                val pkgFlags = android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES or
                    android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS

                val thirdPartyRaw = SuSessionProvider.execute("pm list packages -3 2>/dev/null | sed 's/^package://'", 15)

                val processed = mutableListOf<InstalledApp>()
                if (thirdPartyRaw.isNotBlank()) {
                    for (pkg in thirdPartyRaw.trim().lines().map { it.trim() }.filter { it.isNotBlank() && it != selfPkg }.distinct()) {
                        if (pkg.length >= 128) continue
                        val appInfo = runCatching { pm.getApplicationInfo(pkg, pkgFlags) }.getOrNull() ?: continue
                        val dispLabel = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
                        val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
                        processed.add(
                            InstalledApp(
                                label = dispLabel,
                                packageName = pkg,
                                icon = icon,
                                whitelisted = false,
                                coexistSelected = false,
                                coexistRecommended = false,
                                rootHideSelected = pkg in hiddenPackages
                            )
                        )
                    }
                }
                if (processed.isEmpty()) {
                    val allRaw = SuSessionProvider.execute("pm list packages 2>/dev/null | sed 's/^package://'", 15)
                    for (pkg in allRaw.trim().lines().map { it.trim() }.filter { it.isNotBlank() && it != selfPkg }.distinct()) {
                        if (pkg.length >= 128) continue
                        val appInfo = runCatching { pm.getApplicationInfo(pkg, pkgFlags) }.getOrNull() ?: continue
                        val dispLabel = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
                        val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
                        processed.add(
                            InstalledApp(
                                label = dispLabel,
                                packageName = pkg,
                                icon = icon,
                                whitelisted = false,
                                coexistSelected = false,
                                coexistRecommended = false,
                                rootHideSelected = pkg in hiddenPackages
                            )
                        )
                    }
                }
                val apps = processed.sortedBy { it.label.lowercase() }
                if (requestVersion != loadVersion) return@Thread
                handler.post {
                    if (requestVersion != loadVersion || isRemoving || isDetached) return@post
                    allApps = apps
                    renderList()
                    updateCount()
                    if (apps.isEmpty()) {
                        countText?.text = "未发现可勾选的应用。请确认已授予 Root 权限，或设备无第三方 App。"
                    }
                }
            } catch (e: Exception) {
                if (requestVersion != loadVersion) return@Thread
                handler.post {
                    if (requestVersion != loadVersion || isRemoving || isDetached) return@post
                    Toast.makeText(ctx, "加载应用列表失败: ${e.message}", Toast.LENGTH_LONG).show()
                    countText?.text = "加载失败: ${e.message}"
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun filteredApps(): List<InstalledApp> {
        val normalized = currentKeyword.trim().lowercase()
        return if (normalized.isBlank()) allApps
        else allApps.filter { it.label.lowercase().contains(normalized) || it.packageName.lowercase().contains(normalized) }
    }

    private fun renderList() {
        if (!isAdded || context == null) return
        val container = appsContainer ?: return
        container.removeAllViews()
        val ctx = context ?: return
        val inflater = LayoutInflater.from(ctx)
        val visible = filteredApps()
        for (item in visible) {
            val binding = com.HanFeng.databinding.ItemAppBinding.inflate(inflater, container, false)
            binding.loadingIndicator.visibility = View.GONE
            binding.appIcon.setImageDrawable(item.icon)
            binding.appName.text = item.label
            binding.packageName.text = item.packageName
            binding.whitelistBox.setOnCheckedChangeListener(null)
            binding.whitelistBox.isChecked = item.rootHideSelected
            binding.whitelistBox.setOnCheckedChangeListener { _, checked ->
                if (!isAdded || context == null) return@setOnCheckedChangeListener
                applyHide(item.packageName, checked)
            }
            container.addView(binding.root)
        }
        if (visible.isEmpty() && allApps.isNotEmpty()) {
            val tv = TextView(ctx).apply {
                text = "搜索无结果"
                setPadding(0, 12.dp, 0, 12.dp)
                setTextColor(resources.getColor(R.color.hf_text_secondary, null))
            }
            container.addView(tv)
        }
    }

    private fun applyHide(packageName: String, hidden: Boolean) {
        val ctx = context ?: return
        if (hidden && manager.isProtectedPackage(packageName)) {
            Toast.makeText(ctx, "$packageName 属于系统关键包，禁止隐藏", Toast.LENGTH_SHORT).show()
            renderList()
            return
        }
        val result = if (hidden) manager.hideApp(packageName, ctx.packageName) else manager.unhideApp(packageName)
        if (result.success) {
            RootHideRepository.toggleHiddenApp(ctx, packageName, hidden)
            Toast.makeText(ctx, result.detail, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, result.detail, Toast.LENGTH_LONG).show()
        }
        allApps = allApps.map { if (it.packageName != packageName) it else it.copy(rootHideSelected = hidden) }
        renderList()
        updateCount()
    }

    private fun toggleHidden(selectAll: Boolean) {
        if (!isAdded || context == null) return
        val ctx = context ?: return
        val visible = filteredApps()
        if (visible.isEmpty()) return
        val targets = visible.filter { !manager.isProtectedPackage(it.packageName) && it.packageName != ctx.packageName }
        if (targets.isEmpty()) {
            Toast.makeText(ctx, "当前列表没有可隐藏的应用", Toast.LENGTH_SHORT).show()
            return
        }
        val results = if (selectAll) manager.hideApps(targets.map { it.packageName }, ctx.packageName)
        else manager.unhideAll(targets.map { it.packageName })
        val failed = results.filter { !it.success }
        val succeeded = results.filter { it.success }.map { it.packageName }.toSet()
        val updated = RootHideRepository.getHiddenApps(ctx).toMutableSet()
        if (selectAll) updated += succeeded else updated -= succeeded
        RootHideRepository.setHiddenApps(ctx, updated)
        allApps = allApps.map { if (it.packageName in succeeded) it.copy(rootHideSelected = selectAll) else it }
        renderList()
        updateCount()
        Toast.makeText(
            ctx,
            if (selectAll) "已隐藏 ${succeeded.size} 个应用" else "已恢复 ${succeeded.size} 个应用",
            Toast.LENGTH_SHORT
        ).show()
        if (failed.isNotEmpty()) {
            Toast.makeText(ctx, "${failed.size} 个应用操作失败", Toast.LENGTH_LONG).show()
        }
    }

    private fun unhideAll() {
        if (!isAdded || context == null) return
        val ctx = context ?: return
        val current = RootHideRepository.getHiddenApps(ctx)
        if (current.isEmpty()) {
            Toast.makeText(ctx, "当前没有已隐藏的应用", Toast.LENGTH_SHORT).show()
            return
        }
        val results = manager.unhideAll(current)
        val succeeded = results.filter { it.success }.map { it.packageName }.toSet()
        val updated = current - succeeded
        RootHideRepository.setHiddenApps(ctx, updated)
        allApps = allApps.map { if (it.packageName in succeeded) it.copy(rootHideSelected = false) else it }
        renderList()
        updateCount()
        Toast.makeText(ctx, "已恢复 ${succeeded.size} 个应用", Toast.LENGTH_SHORT).show()
    }

    fun getSelectedHiddenPackages(): Set<String> {
        return allApps.filter { it.rootHideSelected }.map { it.packageName }.toSet()
    }

    fun reloadFromOutside() {
        if (context == null) return
        loadApps()
    }

    private fun updateCount() {
        val count = allApps.count { it.rootHideSelected }
        countText?.text = "已隐藏: $count 个应用"
    }

    override fun onDestroyView() {
        loadThread?.interrupt()
        searchInput = null
        countText = null
        appsContainer = null
        super.onDestroyView()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
