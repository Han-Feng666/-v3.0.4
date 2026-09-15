package com.HanFeng.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.databinding.ActivityDecisionDomainsBinding
import com.HanFeng.databinding.ItemDecisionDomainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.HanFeng.core.network.NetworkKernel
import com.HanFeng.core.network.ScoredBlockCache
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DecisionDomainsActivity : BaseActivity() {
    private lateinit var binding: ActivityDecisionDomainsBinding
    private lateinit var adapter: DecisionDomainAdapter
    private var allEntries: List<LogRepository.DomainDecisionEntry> = emptyList()
    private var filter: LogRepository.DomainDecisionType? = null
    private var learnedOnly = false
    private var learnedMap: Map<String, ScoredBlockCache.Entry> = emptyMap()
    private var searchJob: Job? = null
    // SimpleDateFormat accessed only from main thread; no ThreadLocal needed
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityDecisionDomainsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val bgPath = com.HanFeng.data.FeatureSettingsRepository.getCustomBackgroundPath(this)
        if (!bgPath.isNullOrEmpty()) {
            binding.ivBackground.applyCustomFileBackground(bgPath)
        } else {
            binding.ivBackground.applyCustomAssetBackground("custom/background")
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top + 8.dp, view.paddingRight, bars.bottom + 16.dp)
            insets
        }
        adapter = DecisionDomainAdapter(
            dateFormat = dateFormat,
            learnedLookup = { domain -> entryForLearned(domain) },
            onToggleRequest = { entry -> toggleDecision(entry) },
            onPersistRequest = { domain -> persistLearnedDomain(domain) }
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.searchInput.doAfterTextChanged {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                delay(180)
                if (isFinishing || isDestroyed) return@launch
                applyFilters()
            }
        }
        binding.btnFilterAll.setOnClickListener {
            filter = null
            applyFilters()
        }
        binding.btnFilterBlocked.setOnClickListener {
            filter = LogRepository.DomainDecisionType.BLOCKED
            applyFilters()
        }
        binding.btnFilterAllowed.setOnClickListener {
            filter = LogRepository.DomainDecisionType.ALLOWED
            applyFilters()
        }
        // 只看智能识别命中的域名：这类条目带识别依据与分数，可逐条入库或撤销
        binding.btnFilterLearned.setOnClickListener {
            learnedOnly = !learnedOnly
            applyFilters()
        }
        // 学习域名一键入库：把 MITM/流量学习引擎命中的域名持久化为用户拦截规则
        binding.btnPersistLearned.setOnClickListener { confirmPersistAllLearned() }
        loadEntries()
    }

    /** 学习缓存变化后刷新“一键入库”按钮文案与可见性 */
    private fun refreshLearnedButton() {
        val learnedCount = learnedMap.size
        if (learnedCount <= 0) {
            binding.btnPersistLearned.isVisible = false
            return
        }
        binding.btnPersistLearned.isVisible = true
        binding.btnPersistLearned.text = "学习域名一键入库（$learnedCount 条）"
    }

    private fun entryForLearned(domain: String): ScoredBlockCache.Entry? =
        if (domain.isBlank()) null else learnedMap[domain.trim().lowercase()]

    private fun confirmPersistAllLearned() {
        val count = learnedMap.size
        if (count <= 0) {
            Toast.makeText(this, "当前没有可入库的学习域名", Toast.LENGTH_SHORT).show()
            return
        }
        StableDialog.builder(this)
            .setTitle("学习域名入库")
            .setMessage("将 $count 条学习命中的域名持久保存为拦截规则？入库后即使学习缓存过期也继续拦截。")
            .setPositiveButton("入库") { _, _ ->
                lifecycleScope.launch {
                    val added = withContext(Dispatchers.IO) {
                        runCatching { ScoredBlockCache.persistLearnedDomainsToRules(applicationContext) }
                            .getOrDefault(0)
                    }
                    Toast.makeText(
                        this@DecisionDomainsActivity,
                        if (added > 0) "已入库 $added 条学习域名规则" else "没有新增规则（可能已存在）",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadEntries()
                }
            }
            .setNegativeButton("取消", null)
            .showSafely(this, "persist-learned-dialog")
    }

    /** 单条学习域名入库（列表里的“入库”按钮） */
    private fun persistLearnedDomain(domain: String) {
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching { ScoredBlockCache.persistDomainToRules(applicationContext, domain) }
                    .getOrDefault(false)
            }
            Toast.makeText(
                this@DecisionDomainsActivity,
                if (added) "$domain 已入库为拦截规则" else "$domain 入库失败（规则可能已存在）",
                Toast.LENGTH_SHORT
            ).show()
            loadEntries()
        }
    }

    override fun onResume() {
        super.onResume()
        loadEntries()
    }

    private fun toggleDecision(entry: LogRepository.DomainDecisionEntry) {
        // 仅 DOMAIN scope 支持手工 toggle，非 DOMAIN（IP/CIDR/Port/EncryptedDNS/Learning）
        // 规则库不支持以 IP 作 host，提示用户该类事件不可手动切换。
        if (entry.scope != LogRepository.DecisionScope.DOMAIN) {
            val scopeLabel = scopeLabelOf(entry.scope)
            android.widget.Toast.makeText(
                applicationContext,
                "$scopeLabel 类拦截事件无法在此手工切换，请到「规则管理」中通过 IP-CIDR/端口规则管理",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        val appContext = applicationContext
        val newAction = if (entry.type == LogRepository.DomainDecisionType.BLOCKED) "放行" else "拦截"
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                LogRepository.toggleDomainDecision(appContext, entry.domain, entry.type)
                // 手工放行时同时撤销学习缓存里的拦截条目，否则下一轮查询仍会被 sinkhole
                if (newAction == "放行") {
                    ScoredBlockCache.dropDomain(entry.domain)
                }
            }
            loadEntries()
            android.widget.Toast.makeText(appContext, "已将该域名切换为 $newAction，已实时生效", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun scopeLabelOf(scope: LogRepository.DecisionScope): String = when (scope) {
        LogRepository.DecisionScope.DOMAIN -> "域名"
        LogRepository.DecisionScope.IP_CIDR -> "IP / CIDR"
        LogRepository.DecisionScope.PORT_ONLY -> "端口"
        LogRepository.DecisionScope.ENCRYPTED_DNS_SNI -> "加密 DNS SNI"
        LogRepository.DecisionScope.LEARNING_FEEDBACK -> "学习反馈 IP"
    }

    private fun loadEntries() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val entries = runCatching { LogRepository.getDomainDecisionEntries(applicationContext) }
                    .getOrElse { emptyList() }
                val learned = runCatching {
                    ScoredBlockCache.exportLearnedDomains().associateBy({ it.domain }, {
                        ScoredBlockCache.Entry(expiresAt = it.expiresAt, score = it.score, vendor = it.vendor, reason = it.reason)
                    })
                }.getOrDefault(emptyMap())
                entries to learned
            }
            if (isFinishing || isDestroyed) return@launch
            allEntries = loaded.first
            learnedMap = loaded.second
            refreshLearnedButton()
            applyFilters()
        }
    }

    private fun applyFilters() {
        val query = binding.searchInput.text?.toString().orEmpty().trim().lowercase()
        val filtered = allEntries.filter { entry ->
            val typeMatched = filter == null || entry.type == filter
            val learnedMatched = !learnedOnly || entryForLearned(entry.domain) != null
            val queryMatched = query.isBlank() ||
                entry.identifier.lowercase().contains(query) ||
                entry.domain.lowercase().contains(query) ||
                entry.message.lowercase().contains(query)
            typeMatched && learnedMatched && queryMatched
        }
        adapter.submitList(filtered)
        val blockedCount = filtered.count { it.type == LogRepository.DomainDecisionType.BLOCKED }
        val allowedCount = filtered.count { it.type == LogRepository.DomainDecisionType.ALLOWED }
        val learnedHitCount = filtered.count { entryForLearned(it.domain) != null }
        binding.summaryText.text = buildString {
            append("当前显示 ${filtered.size} 条，拦截 ${blockedCount} 条，放行 ${allowedCount} 条")
            if (learnedHitCount > 0) append("  智能识别 ${learnedHitCount} 条")
            append("  学习缓存 ${learnedMap.size} 条")
        }
        binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        if (filtered.isEmpty() && learnedOnly) {
            binding.emptyText.text = "当前没有智能识别命中的域名（识别依赖 TLS 指纹、空 SNI、QUIC 降级、共享 IP 聚类等行为特征）"
        }
        updateFilterButtons()
    }

    private fun updateFilterButtons() {
        updateFilterButton(binding.btnFilterAll, filter == null)
        updateFilterButton(binding.btnFilterBlocked, filter == LogRepository.DomainDecisionType.BLOCKED)
        updateFilterButton(binding.btnFilterAllowed, filter == LogRepository.DomainDecisionType.ALLOWED)
        updateFilterButton(binding.btnFilterLearned, learnedOnly)
    }

    private fun updateFilterButton(view: View, selected: Boolean) {
        view.alpha = if (selected) 1f else 0.72f
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, DecisionDomainsActivity::class.java)

        private val DIFF = object : DiffUtil.ItemCallback<LogRepository.DomainDecisionEntry>() {
            override fun areItemsTheSame(
                oldItem: LogRepository.DomainDecisionEntry,
                newItem: LogRepository.DomainDecisionEntry
            ): Boolean =
                oldItem.type == newItem.type &&
                oldItem.scope == newItem.scope &&
                oldItem.identifier == newItem.identifier

            override fun areContentsTheSame(
                oldItem: LogRepository.DomainDecisionEntry,
                newItem: LogRepository.DomainDecisionEntry
            ): Boolean = oldItem == newItem
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private class DecisionDomainAdapter(
        private val dateFormat: SimpleDateFormat,
        private val learnedLookup: (String) -> ScoredBlockCache.Entry?,
        private val onToggleRequest: (LogRepository.DomainDecisionEntry) -> Unit,
        private val onPersistRequest: (String) -> Unit
    ) : ListAdapter<LogRepository.DomainDecisionEntry, DecisionDomainAdapter.ViewHolder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                ItemDecisionDomainBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                dateFormat,
                learnedLookup,
                onToggleRequest,
                onPersistRequest
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        class ViewHolder(
            private val binding: ItemDecisionDomainBinding,
            private val dateFormat: SimpleDateFormat,
            private val learnedLookup: (String) -> ScoredBlockCache.Entry?,
            private val onToggleRequest: (LogRepository.DomainDecisionEntry) -> Unit,
            private val onPersistRequest: (String) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: LogRepository.DomainDecisionEntry) {
                val isDomain = item.scope == LogRepository.DecisionScope.DOMAIN
                // 主标识：identifier（域名/IP/CIDR/加密 DNS SNI 等），更直观
                val display = item.identifier.ifBlank { item.domain }
                binding.textDomain.text = display

                val scopeText = when (item.scope) {
                    LogRepository.DecisionScope.DOMAIN -> "域名"
                    LogRepository.DecisionScope.IP_CIDR -> "IP/CIDR"
                    LogRepository.DecisionScope.PORT_ONLY -> "端口"
                    LogRepository.DecisionScope.ENCRYPTED_DNS_SNI -> "加密DNS"
                    LogRepository.DecisionScope.LEARNING_FEEDBACK -> "学习IP"
                }
                val blocked = item.type == LogRepository.DomainDecisionType.BLOCKED
                binding.textType.text = if (blocked) "拦截" else "放行"
                binding.textType.setBackgroundResource(
                    if (blocked) com.HanFeng.R.drawable.bg_decision_blocked else com.HanFeng.R.drawable.bg_decision_allowed
                )
                binding.textType.setTextColor(
                    ContextCompat.getColor(
                        binding.root.context,
                        if (blocked) com.HanFeng.R.color.hf_blocked_red else com.HanFeng.R.color.hf_allowed_green
                    )
                )
                binding.textAppName.text = item.appName
                binding.textTime.text = dateFormat.format(Date(item.timestamp))

                // 学习引擎命中的条目额外展示识别依据，并提供单条入库入口
                val learnedEntry = if (isDomain) learnedLookup(item.domain) else null
                if (learnedEntry != null) {
                    binding.textNote.isVisible = true
                    binding.textNote.text = buildString {
                        append("智能识别 置信度 ${learnedEntry.score}")
                        if (learnedEntry.reason.isNotBlank()) append("  依据 ${learnedEntry.reason}")
                        append("  ${((learnedEntry.expiresAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)} 分钟后失效")
                    }
                    binding.btnRuleAction.isVisible = true
                    binding.btnRuleAction.setOnClickListener { onPersistRequest(item.domain) }
                } else {
                    binding.textNote.isVisible = false
                    binding.btnRuleAction.isVisible = false
                    binding.btnRuleAction.setOnClickListener(null)
                }
                if (isDomain) {
                    // 仅域名类事件支持长按手工切换
                    binding.root.setOnLongClickListener {
                        val context = binding.root.context
                        val currentAction = if (blocked) "拦截" else "放行"
                        val newAction = if (blocked) "放行" else "拦截"
                        
                        runCatching {
                            StableDialog.builder(context)
                                .setTitle("切换决策")
                                .setMessage("将 $display\n从【${currentAction}】切换为【${newAction}】？")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("确认") { _, _ ->
                                    onToggleRequest(item)
                                }
                                .showSafely(context, "glass-dialog")
                        }
                        true
                    }
                } else {
                    // 非 DOMAIN 类拦截禁用长按，避免误触发无法 fallback 的切换路径
                    binding.root.setOnLongClickListener {
                        android.widget.Toast.makeText(
                            binding.root.context,
                            "$scopeText 类拦截事件无法在此手工切换",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        true
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        searchJob?.cancel()
        super.onDestroy()
    }
}
