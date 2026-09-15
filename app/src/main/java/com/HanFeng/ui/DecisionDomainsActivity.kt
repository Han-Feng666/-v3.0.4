package com.HanFeng.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import android.widget.ScrollView
import android.widget.TextView
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
import com.HanFeng.data.RuleRepository
import com.HanFeng.databinding.ActivityDecisionDomainsBinding
import com.HanFeng.databinding.ItemDecisionDomainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var refreshJob: Job? = null
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
            onPersistRequest = { domain -> persistLearnedDomain(domain) },
            onInspectRequest = { entry -> showDomainActions(entry) }
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
        startAutoRefresh()
    }

    override fun onPause() {
        refreshJob?.cancel()
        refreshJob = null
        super.onPause()
    }

    /**
     * 实时刷新：日志写入约 0.5 秒落盘，页面可见时每 2 秒做一轮增量解析刷新列表；
     * 离开页面即停止，避免后台空转耗电。
     */
    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                loadEntries()
                delay(AUTO_REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    /**
     * 点击条目的操作面板：先看这个域名的内容，再决定是否切换拦截/放行。
     */
    private fun showDomainActions(entry: LogRepository.DomainDecisionEntry) {
        val canToggle = entry.scope == LogRepository.DecisionScope.DOMAIN
        val newAction = if (entry.type == LogRepository.DomainDecisionType.BLOCKED) "放行" else "拦截"
        val summary = buildString {
            append("标识：${entry.identifier}\n")
            append("当前：${if (entry.type == LogRepository.DomainDecisionType.BLOCKED) "拦截" else "放行"}")
            append("（${scopeLabelOf(entry.scope)}）\n")
            append("归属应用：${entry.appName.ifBlank { "未知" }}\n")
            append("最近判定：${dateFormat.format(Date(entry.timestamp))}")
        }
        StableDialog.builder(this)
            .setTitle("域名操作")
            .setMessage(summary)
            .setNeutralButton("复制", null)
            .setNegativeButton(if (canToggle) "切换为$newAction" else "无法切换", if (canToggle) { _, _ -> toggleDecision(entry) } else null)
            .setPositiveButton("查看内容") { dialog, _ ->
                dialog.dismiss()
                showDomainDetail(entry, entryForLearned(entry.domain))
            }
            .showSafely(this, "decision-actions-dialog")
            ?.let { dialog ->
                // 复制按钮点击后不关闭弹窗，方便继续看内容
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                    copyToClipboard(entry.identifier)
                }
            }
    }

    /**
     * 域名内容详情：规则库现状、厂商归属、判定依据与原始日志，帮助用户判断该拦还是该放。
     */
    private fun showDomainDetail(
        entry: LogRepository.DomainDecisionEntry,
        learned: ScoredBlockCache.Entry?
    ) {
        lifecycleScope.launch {
            val detail = withContext(Dispatchers.IO) { describeEntry(entry, learned) }
            if (isFinishing || isDestroyed) return@launch
            val scroll = ScrollView(this@DecisionDomainsActivity)
            val textView = TextView(this@DecisionDomainsActivity).apply {
                text = detail
                setTextIsSelectable(true)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(this@DecisionDomainsActivity, com.HanFeng.R.color.hf_text_primary))
                val padding = 6.dp
                setPadding(padding * 3, padding, padding * 3, padding)
            }
            scroll.addView(textView)
            val canToggle = entry.scope == LogRepository.DecisionScope.DOMAIN
            val newAction = if (entry.type == LogRepository.DomainDecisionType.BLOCKED) "放行" else "拦截"
            StableDialog.builder(this@DecisionDomainsActivity)
                .setTitle("域名内容")
                .setView(scroll)
                .setNeutralButton("复制", null)
                .setNegativeButton(if (canToggle) "切换为$newAction" else "关闭", if (canToggle) { _, _ -> toggleDecision(entry) } else null)
                .setPositiveButton("知道了", null)
                .showSafely(this@DecisionDomainsActivity, "domain-detail-dialog")
                ?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                    copyToClipboard(entry.identifier)
                }
        }
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("domain", text))
        }
        Toast.makeText(this, "已复制 $text", Toast.LENGTH_SHORT).show()
    }

    /** 汇总一个标识当前"是什么内容、为什么被拦/放、规则库现在怎么处理它" */
    private fun describeEntry(
        entry: LogRepository.DomainDecisionEntry,
        learned: ScoredBlockCache.Entry?
    ): String {
        val appContext = applicationContext
        val message = entry.message
        val lines = mutableListOf<String>()
        lines += "标识：${entry.identifier}"
        lines += "当前：${if (entry.type == LogRepository.DomainDecisionType.BLOCKED) "拦截" else "放行"}（${scopeLabelOf(entry.scope)}）"
        lines += "归属应用：${entry.appName.ifBlank { "未知" }}"
        lines += "最近判定：${dateFormat.format(Date(entry.timestamp))}"
        lines += "判定来源：${decisionSourceOf(message)}"
        ReasonPattern.find(message)?.let { lines += "判定原因：${it.groupValues[1].trim()}" }
        ScorePattern.find(message)?.let { lines += "置信度：${it.groupValues[1]}" }
        QTypePattern.find(message)?.let { lines += "记录类型：${it.groupValues[1]}" }
        if (entry.scope == LogRepository.DecisionScope.DOMAIN) {
            val domain = entry.domain
            val userBlocked = runCatching { domain in RuleRepository.getUserOwnedBlockedDomains(appContext) }.getOrDefault(false)
            val excepted = runCatching { domain in RuleRepository.getExceptedDomains(appContext) }.getOrDefault(false)
            val whitelisted = runCatching { RuleRepository.isWhitelistedDomain(domain) }.getOrDefault(false)
            val matchedRule = runCatching { RuleRepository.hasMatchingRule(appContext, domain) }.getOrDefault(false)
            lines += "规则库现状：" + when {
                whitelisted || excepted -> "白名单/例外放行，规则库不会再拦这个域名"
                userBlocked -> "你在「拦截与放行」里手工加入的拦截规则"
                matchedRule -> "命中规则库（订阅或导入的规则）"
                else -> "未命中任何规则，需要靠启发式或智能识别判断"
            }
            val vendor = runCatching { RuleRepository.classifyVendor(appContext, domain) }.getOrNull()
            if (!vendor.isNullOrBlank()) {
                lines += "厂商归属：" + if (vendor == "其它 (Other)") "无明显厂商归属" else vendor
            }
            val traits = buildList {
                if (runCatching { RuleRepository.looksLikeAdSdkInfraDomain(domain) }.getOrDefault(false)) {
                    add("广告 SDK 基础设施命名特征")
                } else if (runCatching { RuleRepository.looksLikeAdDomain(domain) }.getOrDefault(false)) {
                    add("广告域名命名特征")
                }
            }
            if (traits.isNotEmpty()) lines += "命名特征：" + traits.joinToString("；")
            val risks = buildList {
                if (runCatching { RuleRepository.isSensitiveAuthDomain(domain) }.getOrDefault(false)) {
                    add("登录/支付等敏感认证域名，拦截可能导致无法登录或支付失败")
                }
                if (runCatching { RuleRepository.isSocialCoreDomain(domain) }.getOrDefault(false)) {
                    add("社交核心域名，拦截可能影响消息收发")
                }
                if (runCatching { RuleRepository.isMediaCoreDomain(domain) || RuleRepository.isBusinessCoreDomain(domain) || RuleRepository.isGameCoreDomain(domain) }.getOrDefault(false)) {
                    add("媒体/业务/游戏核心域名，拦截可能影响正常内容加载")
                }
            }
            if (risks.isNotEmpty()) lines += "风险提示：" + risks.joinToString("；") else {
                lines += "风险提示：未发现登录支付等核心功能依赖，拦截风险较低"
            }
        }
        if (learned != null) {
            val minutes = ((learned.expiresAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
            lines += "智能识别：已命中，置信度 ${learned.score}，依据 ${learned.reason.ifBlank { "行为特征" }}，约 $minutes 分钟后失效"
            if (learned.vendor.isNotBlank()) lines += "疑似厂商：${learned.vendor}"
        } else {
            lines += "智能识别：未命中学习引擎"
        }
        lines += "原始日志：${message.take(260)}"
        return lines.joinToString("\n")
    }

    private fun decisionSourceOf(message: String): String = when {
        "via rule-sync" in message -> "你在「拦截与放行」页手工切换"
        "by learned engine" in message -> "智能识别引擎（行为特征学习）"
        "by ad heuristic" in message -> "广告行为启发式判定"
        "reason=cache-hit" in message -> "放行（DNS 缓存命中）"
        "Blocked" in message -> "命中拦截规则或流量特征"
        "Passed" in message -> "放行（未命中拦截规则）"
        else -> "其它"
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
        // 实时刷新间隔：日志写入器约 500ms 落盘，2 秒足够跟手又不会空转
        private const val AUTO_REFRESH_INTERVAL_MILLIS = 2_000L
        private val ReasonPattern = Regex("reason=([^\n]+?)(?= app=| vendor=| qType=| score=| qtype=|$)")
        private val ScorePattern = Regex("score=(-?\\d+)")
        private val QTypePattern = Regex("qType=(\\S+)")

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
        private val onPersistRequest: (String) -> Unit,
        private val onInspectRequest: (LogRepository.DomainDecisionEntry) -> Unit
    ) : ListAdapter<LogRepository.DomainDecisionEntry, DecisionDomainAdapter.ViewHolder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                ItemDecisionDomainBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                dateFormat,
                learnedLookup,
                onToggleRequest,
                onPersistRequest,
                onInspectRequest
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
            private val onPersistRequest: (String) -> Unit,
            private val onInspectRequest: (LogRepository.DomainDecisionEntry) -> Unit
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
                    // 点击整行：先看域名内容，再决定是否切换拦截/放行
                    binding.root.setOnClickListener { onInspectRequest(item) }
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
                    binding.root.setOnClickListener { onInspectRequest(item) }
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
        refreshJob?.cancel()
        super.onDestroy()
    }
}
