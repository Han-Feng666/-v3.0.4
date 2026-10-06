package com.HanFeng.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.HanFeng.R
import com.HanFeng.adblocker.shizuku.DeviceCapabilityDetector
import com.HanFeng.adblocker.shizuku.SmartRecommender
import com.HanFeng.adblocker.shizuku.SuSession
import com.HanFeng.data.SocDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 性能优化 Hub：状态面板（温度/电量/内存/频率）+ 智能推荐 + 调度与线程优化入口。
 * 合并原"智能优化推荐/性能调优/线程优化"三个入口为一个页面。
 */
class PerformanceHubActivity : BaseActivity() {

    private val recommender = SmartRecommender()
    private val capabilityDetector = DeviceCapabilityDetector()

    private lateinit var tvHubDevice: TextView
    private lateinit var tvHubSnapshot: TextView
    private lateinit var tvHubRootState: TextView
    private lateinit var tvHubRecommendStatus: TextView
    private lateinit var layoutRecommendations: LinearLayout
    private var analyzeJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_performance_hub)

        tvHubDevice = findViewById(R.id.tvHubDevice)
        tvHubSnapshot = findViewById(R.id.tvHubSnapshot)
        tvHubRootState = findViewById(R.id.tvHubRootState)
        tvHubRecommendStatus = findViewById(R.id.tvHubRecommendStatus)
        layoutRecommendations = findViewById(R.id.layoutHubRecommendations)

        val initialTopPadding = (resources.displayMetrics.density * 16).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.hubRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                bars.top + initialTopPadding,
                view.paddingRight,
                bars.bottom + initialTopPadding
            )
            insets
        }

        findViewById<Button>(R.id.btnHubRefresh).setOnClickListener { analyze() }
        findViewById<Button>(R.id.btnHubTuner).setOnClickListener {
            launchActivitySafely(
                PerformanceTunerActivity.createIntent(this),
                failureMessage = "打开性能调优失败"
            )
        }
        findViewById<Button>(R.id.btnHubThreadOpt).setOnClickListener {
            launchActivitySafely(
                Intent(this, ThreadOptimizationActivity::class.java),
                failureMessage = "打开线程优化失败"
            )
        }

        analyze()
    }

    private fun analyze() {
        analyzeJob?.cancel()
        tvHubDevice.text = "设备：检测中..."
        tvHubSnapshot.text = "正在采集运行状态..."
        tvHubRecommendStatus.text = "等待状态采集完成..."
        layoutRecommendations.removeAllViews()

        analyzeJob = lifecycleScope.launch {
            val rootReady = withContext(Dispatchers.IO) {
                val s = SuSession.getInstance()
                s.isSessionOpen() || s.open(15)
            }
            if (isFinishing || isDestroyed) return@launch

            if (!rootReady) {
                tvHubRootState.text = "Root 不可用：无法读取实时状态，仅展示优化入口"
                tvHubSnapshot.text = "Root 可用后点击\"刷新状态\"查看温度/电量/内存/CPU 频率"
                tvHubRecommendStatus.text = "智能推荐需要 Root 权限"
                return@launch
            }

            val capability = withContext(Dispatchers.IO) { capabilityDetector.detect() }
            val socName = withContext(Dispatchers.IO) { SocDatabase.detectSocName(this@PerformanceHubActivity) }
            if (isFinishing || isDestroyed) return@launch

            tvHubDevice.text = "设备：${capability.deviceType} / $socName"
            tvHubRootState.text = "Root：${capability.rootSolution}"

            val snapshot = withContext(Dispatchers.IO) { recommender.captureSnapshot(this@PerformanceHubActivity) }
            if (isFinishing || isDestroyed) return@launch

            tvHubSnapshot.text = buildString {
                appendLine("CPU 温度：${snapshot.temperature}°C")
                appendLine("电量：${snapshot.batteryLevel}%${if (snapshot.isCharging) "（充电中）" else ""} | 电池温度：${snapshot.batteryTemp / 10.0}°C")
                appendLine("内存占用：${snapshot.memoryUsedPercent}% | 后台应用：${snapshot.backgroundAppCount} 个")
                if (snapshot.cpuPerCoreFreqKHz.isNotEmpty()) {
                    val cores = snapshot.cpuPerCoreFreqKHz
                        .joinToString("/") { "${it / 1000}" }
                    appendLine("CPU 每核频率（MHz）：$cores")
                    append("GPU：${snapshot.gpuFreqKHz / 1000}MHz")
                } else {
                    append("CPU：${snapshot.cpuFreqKHz / 1000}MHz / 最高 ${snapshot.cpuMaxFreqKHz / 1000}MHz | GPU：${snapshot.gpuFreqKHz / 1000}MHz")
                }
            }

            val report = recommender.generateRecommendations(
                snapshot = snapshot,
                context = this@PerformanceHubActivity,
                deviceType = capability.deviceType,
                socName = socName
            )

            tvHubRecommendStatus.text = report.summary
            layoutRecommendations.removeAllViews()
            if (report.recommendations.isEmpty()) {
                val tv = TextView(this@PerformanceHubActivity).apply {
                    text = "当前设备状态良好，无需优化。"
                    setTextColor(resources.getColor(R.color.hf_text_secondary, null))
                    textSize = 14f
                    setPadding(0, 16.dp, 0, 16.dp)
                }
                layoutRecommendations.addView(tv)
                return@launch
            }
            for (rec in report.recommendations) {
                layoutRecommendations.addView(buildRecommendationCard(rec))
            }
        }
    }

    private fun buildRecommendationCard(rec: SmartRecommender.Recommendation): View {
        val ctx = this
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
        }

        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(ctx).apply {
            text = rec.title
            textSize = 15f
            setTextColor(resources.getColor(R.color.hf_text_primary, null))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val priorityLabel = TextView(ctx).apply {
            text = when {
                rec.priority >= 90 -> "紧急"
                rec.priority >= 70 -> "重要"
                rec.priority >= 50 -> "建议"
                else -> "提示"
            }
            textSize = 11f
            setTextColor(resources.getColor(R.color.hf_text_secondary, null))
            setPadding(8.dp, 2.dp, 8.dp, 2.dp)
            setBackgroundResource(R.drawable.bg_panel)
        }
        titleRow.addView(title)
        titleRow.addView(priorityLabel)
        card.addView(titleRow)

        card.addView(TextView(ctx).apply {
            text = rec.description
            textSize = 13f
            setTextColor(resources.getColor(R.color.hf_text_secondary, null))
            setPadding(0, 6.dp, 0, 8.dp)
        })

        val actionText = when (rec.action) {
            is SmartRecommender.RecommendationAction.NoAction -> "知道了"
            else -> "一键应用"
        }
        card.addView(Button(ctx).apply {
            text = actionText
            textSize = 13f
            setOnClickListener {
                when (val action = rec.action) {
                    is SmartRecommender.RecommendationAction.NoAction ->
                        Toast.makeText(ctx, action.reason, Toast.LENGTH_SHORT).show()
                    else -> lifecycleScope.launch {
                        val ok = recommender.applyRecommendation(ctx, rec)
                        Toast.makeText(
                            ctx,
                            if (ok) "已应用：${rec.title}" else "应用失败",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (ok) analyze()
                    }
                }
            }
        })
        return card
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
