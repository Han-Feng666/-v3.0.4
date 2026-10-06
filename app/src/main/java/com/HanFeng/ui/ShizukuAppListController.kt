package com.HanFeng.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R
import com.HanFeng.data.ShizukuFlags
import rikka.shizuku.Shizuku

private val DIFF = object : DiffUtil.ItemCallback<ShizukuAppListController.AppItem>() {
    override fun areItemsTheSame(oldItem: ShizukuAppListController.AppItem, newItem: ShizukuAppListController.AppItem): Boolean =
        oldItem.packageName == newItem.packageName && oldItem.uid == newItem.uid

    override fun areContentsTheSame(oldItem: ShizukuAppListController.AppItem, newItem: ShizukuAppListController.AppItem): Boolean =
        oldItem.label == newItem.label &&
            oldItem.isChecked == newItem.isChecked &&
            oldItem.isDenied == newItem.isDenied &&
            oldItem.icon === newItem.icon &&
            oldItem.isSystemApp == newItem.isSystemApp
}

/**
 * Shizuku 权限管理页的应用列表控制器：负责列表数据加载（PackageManager + Shizuku 授权表）、
 * 搜索过滤、"只看已授权"筛选与 RecyclerView Adapter，Activity 只保留 UI 装配与授权操作回调。
 */
class ShizukuAppListController(
    private val packageManager: PackageManager,
    private val selfPackageName: String,
    /** Shizuku 服务是否存活（binder 可达） */
    private val isShizukuAlive: () -> Boolean,
    /** 本应用是否已被授予 Shizuku 权限 */
    private val isSelfAuthorized: () -> Boolean,
    /** 用户切换授权开关回调 */
    private val onItemCheckedChanged: (AppItem, Boolean) -> Unit
) {
    data class AppItem(
        val label: String,
        val packageName: String,
        val icon: Drawable?,
        var isChecked: Boolean,
        val isSystemApp: Boolean = false,
        val uid: Int = -1,
        /** 该 app 是否在 manifest 声明了 Shizuku 客户端 permission。false = 授权开关无效, 用户会被误导。 */
        val declaresClientPermission: Boolean = true,
        /** 该 app 是否被显式拒绝过（FLAG_DENIED）。被拒绝的应用下次请求会直接被拒，需重新开启授权。 */
        val isDenied: Boolean = false
    )

    private val allItems = mutableListOf<AppItem>()

    /** 只看已授权应用（官方 App 首页视角） */
    private var authorizedOnly: Boolean = false

    val adapter = AppListAdapter()

    /**
     * 加载应用列表: 基础层用 PackageManager 拿手机里所有第三方 App；
     * 若 Shizuku 已启动且本应用已授权，再通过 binder 查询每个 App 的授权状态。
     *
     * @return 状态提示文字（展示在列表标题栏）
     */
    fun load(): String {
        val pm = packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val items = mutableListOf<AppItem>()
        val selfPackage = selfPackageName
        for (appInfo in installedApps) {
            if (appInfo.packageName == selfPackage) continue
            val label = pm.getApplicationLabel(appInfo).toString()
            val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val uid = appInfo.uid
            val flags = runCatching { Shizuku.getFlagsForUid(uid, ShizukuFlags.MASK_PERMISSION) }.getOrDefault(0)
            val isAllowed = ShizukuFlags.isAllowed(flags)
            val isDenied = ShizukuFlags.isDenied(flags)
            val declaresClientPermission = runCatching {
                val pkgInfo = pm.getPackageInfo(appInfo.packageName, PackageManager.GET_PERMISSIONS)
                pkgInfo.requestedPermissions?.contains("moe.shizuku.manager.permission.API_V23") == true
            }.getOrDefault(true)
            items.add(AppItem(label, appInfo.packageName, icon, isAllowed, isSystem, uid, declaresClientPermission, isDenied))
        }
        items.sortWith(compareByDescending<AppItem> { it.isChecked }.thenBy { it.label.lowercase() })
        allItems.clear()
        allItems.addAll(items)
        applyCurrentFilter()
        return when {
            !isShizukuAlive() -> " · 请先激活 Shizuku (开关暂不可用)"
            !isSelfAuthorized() -> " · 请先授权本应用 (开关暂不可用)"
            else -> " · Shizuku 授权表"
        }
    }

    private fun applyCurrentFilter() {
        val filtered = if (authorizedOnly) allItems.filter { it.isChecked } else allItems
        adapter.submitList(filtered)
    }

    /** 全量刷新（加载完成后调用），叠加"只看已授权"筛选 */
    fun showAll() {
        applyCurrentFilter()
    }

    /** 搜索过滤：按标签或包名子串匹配（大小写不敏感），并叠加"只看已授权"筛选 */
    fun filter(query: String) {
        val q = query.trim().lowercase()
        val base = if (authorizedOnly) allItems.filter { it.isChecked } else allItems
        if (q.isEmpty()) {
            adapter.submitList(base)
            return
        }
        val filtered = base.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
        adapter.submitList(filtered)
    }

    fun setAuthorizedOnly(enabled: Boolean) {
        if (authorizedOnly == enabled) return
        authorizedOnly = enabled
        applyCurrentFilter()
    }

    fun isAuthorizedOnly(): Boolean = authorizedOnly

    fun itemCount(): Int = allItems.size

    fun authorizedCount(): Int = allItems.count { it.isChecked }

    fun setSwitchesEnabled(enabled: Boolean) {
        adapter.switchesEnabled = enabled
    }

    inner class AppListAdapter : ListAdapter<AppItem, AppListAdapter.ViewHolder>(DIFF) {

        @Volatile
        var switchesEnabled: Boolean = true
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_shizuku_authorized_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val iconView: ImageView = itemView.findViewById(R.id.appIcon)
            private val labelView: TextView = itemView.findViewById(R.id.appLabel)
            private val pkgView: TextView = itemView.findViewById(R.id.appPackage)
            private val revokeBtn: SwitchCompat = itemView.findViewById(R.id.btnRevoke)

            fun bind(item: AppItem) {
                labelView.text = if (item.isSystemApp) "${item.label} (系统)" else item.label
                // 未声明客户端权限的 app 即使开关打开也不生效 —— 在副标题明示提示,
                // 避免用户以为"我点开了为什么对方还说没权限"
                // 被显式拒绝（FLAG_DENIED）的 app 下次请求会直接被拒，同样在副标题提示
                val suffix = when {
                    !item.declaresClientPermission -> "  ·  该 app 未声明 Shizuku 客户端权限, 授权对其无效"
                    item.isDenied -> "  ·  已拒绝授权, 该 app 下次请求会直接被拒绝"
                    else -> ""
                }
                pkgView.text = "${item.packageName}  ·  uid=${item.uid}${suffix}"
                if (item.icon != null) {
                    iconView.setImageDrawable(item.icon)
                } else {
                    iconView.setImageResource(android.R.drawable.sym_def_app_icon)
                }
                revokeBtn.setOnCheckedChangeListener(null)
                revokeBtn.isChecked = item.isChecked
                // 未声明客户端权限的 app 开关禁用, 提示用户该 app 接口不支持 Shizuku
                revokeBtn.isEnabled = switchesEnabled && item.declaresClientPermission
                revokeBtn.alpha = if (item.declaresClientPermission) 1f else 0.35f
                revokeBtn.setOnCheckedChangeListener { _, isChecked ->
                    if (switchesEnabled && item.declaresClientPermission) {
                        onItemCheckedChanged(item, isChecked)
                    }
                }
                itemView.setOnClickListener {
                    if (switchesEnabled && item.declaresClientPermission) revokeBtn.toggle()
                }
            }
        }
    }
}
