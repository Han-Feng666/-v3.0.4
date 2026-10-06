package com.HanFeng.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.HanFeng.R
import com.HanFeng.adblocker.shizuku.DeviceCapabilityDetector
import com.HanFeng.adblocker.shizuku.SmartRecommender
import com.HanFeng.data.SocDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 智能优化推荐页。
 *
 * 监控设备运行状态，自动生成优化建议。
 * 用户无需懂参数，一键应用推荐方案。
 */
class SmartRecommenderActivity : AppCompatActivity() {

    private val recommender = SmartRecommender()
    private val capabilityDetector = DeviceCapabilityDetector()
    private lateinit var statusView: TextView
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_smart_recommend)

        statusView = findViewById(R.id.tvRecommendStatus)
        container = findViewById(R.id.layoutRecommendations)

        val initialTopPadding = (resources.displayMetrics.density * 16).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.recommendRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top + initialTopPadding, view.paddingRight, bars.bottom + initialTopPadding)
            insets
        }

        findViewById<Button>(R.id.btnRefreshRecommend).setOnClickListener { analyze() }

        analyze()
    }

    private fun analyze() {
        statusView.text = "正在分析设备状态..."
        container.removeAllViews()

        lifecycleScope.launch {
            val rootReady = withContext(Dispatchers.IO) {
                val s = com.HanFeng.adblocker.shizuku.SuSession.getInstance()
                s.isSessionOpen() || s.open(15)
            }

            if (!rootReady) {
                statusView.text = "Root 不可用。智能推荐需要 Root 权限来读取设备状态。"
                return@launch
            }

            val snapshot = withContext(Dispatchers.IO) { recommender.captureSnapshot(this@SmartRecommenderActivity) }
            val capability = withContext(Dispatchers.IO) { capabilityDetector.detect() }
            val socName = withContext(Dispatchers.IO) { SocDatabase.detectSocName(this@SmartRecommenderActivity) }

            val report = recommender.generateRecommendations(
                snapshot = snapshot,
                context = this@SmartRecommenderActivity,
                deviceType = capability.deviceType,
                socName = socName
            )

            val statusText = buildString {
                appendLine("设备：${capability.deviceType} / $socName")
                appendLine("Root：${capability.rootSolution}")
                appendLine("温度：${snapshot.temperature}°C | 电量：${snapshot.batteryLevel}% | 内存：${snapshot.memoryUsedPercent}%")
                appendLine()
                append(report.summary)
            }
            statusView.text = statusText

            if (report.recommendations.isEmpty()) {
                val tv = TextView(this@SmartRecommenderActivity).apply {
                    text = "当前设备状态良好，无需优化。"
                    setTextColor(resources.getColor(R.color.hf_text_secondary, null))
                    textSize = 14f
                    setPadding(0, 16.dp, 0, 16.dp)
                }
                container.addView(tv)
                return@launch
            }

            for (rec in report.recommendations) {
                val card = buildRecommendationCard(rec)
                container.addView(card)
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
                if (rec.action is SmartRecommender.RecommendationAction.NoAction) {
                    Toast.makeText(ctx, rec.action.reason, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        recommender.applyRecommendation(this@SmartRecommenderActivity, rec)
                    }
                    Toast.makeText(ctx, if (ok) "已应用：${rec.title}" else "应用失败", Toast.LENGTH_SHORT).show()
                    if (ok) analyze()
                }
            }
        })

        return card
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
