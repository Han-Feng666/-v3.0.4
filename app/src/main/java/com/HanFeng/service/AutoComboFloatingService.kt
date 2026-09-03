package com.HanFeng.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationCompat
import com.HanFeng.R
import com.HanFeng.data.AutoComboRepository
import com.HanFeng.model.ComboPlayMode
import com.HanFeng.model.ComboScript
import com.HanFeng.ui.AutoComboActivity
import com.HanFeng.ui.AutoComboEditorActivity
import kotlin.math.abs

/**
 * 自动连招前台服务：
 * - 显示可拖动的连招悬浮球（独立于悬浮球）
 * - 点按悬浮球展开/收起矩形圆角液态玻璃弹窗面板
 * - 面板内完成脚本切换、播放模式、变速、录制/回放、编辑/管理入口与总开关
 * 回放手势由 [AutoComboAccessibilityService] 宿主，本服务负责编排与 UI 状态。
 */
class AutoComboFloatingService : Service() {

    companion object {
        private const val TAG = "AutoComboFloatingService"
        private const val CHANNEL_ID = "hf_auto_combo"
        private const val NOTIFICATION_ID = 0xA1C0
        private const val TOUCH_SLOP = 12

        @Volatile private var running = false
        fun isRunning(): Boolean = running
    }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelVisible = false

    private lateinit var switchMaster: SwitchCompat
    private lateinit var tvScript: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnPlay: Button
    private lateinit var btnPlayMode: Button
    private lateinit var tvLoopCount: TextView
    private lateinit var btnLoopMinus: View
    private lateinit var btnLoopPlus: View
    private lateinit var sbSpeed: SeekBar
    private lateinit var tvSpeedVal: TextView

    private var recorder: AutoComboRecorder? = null
    private var recording = false
    private var playing = false
    private var suppressToggle = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
        addBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        running = false
        stopRecording()
        stopPlaybackInternal()
        removeBall()
        removePanel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    // ---------------- 悬浮球 ----------------

    private fun addBall() {
        val view = android.widget.TextView(this).apply {
            text = "招"
            setTextColor(0xFF302444.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_combo_ball)
        }
        val size = (48 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            y = (resources.displayMetrics.heightPixels * 0.25f).toInt()
        }
        attachDrag(view, params, onTap = { togglePanel() })
        runCatching { windowManager.addView(view, params) }
        ballView = view
        ballParams = params
    }

    private fun removeBall() {
        ballView?.let { runCatching { windowManager.removeView(it) } }
        ballView = null
        ballParams = null
    }

    // ---------------- 面板 ----------------

    private fun togglePanel() {
        if (panelVisible) {
            removePanel()
        } else {
            addPanel()
        }
    }

    private fun addPanel() {
        if (panelView != null) {
            removePanel()
        }
        val view = LayoutInflater.from(this).inflate(R.layout.layout_auto_combo_panel, null, false)
        bindPanelViews(view)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels * 0.45f).toInt()
            y = (resources.displayMetrics.heightPixels * 0.2f).toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = 24
            }
        }
        attachDrag(view.findViewById(android.R.id.content) ?: view, params, onTap = null)
        runCatching { windowManager.addView(view, params) }
        panelView = view
        panelParams = params
        panelVisible = true
        refreshPanel()
    }

    private fun removePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        panelParams = null
        panelVisible = false
    }

    private fun bindPanelViews(root: View) {
        switchMaster = root.findViewById(R.id.switchComboMaster)
        tvScript = root.findViewById(R.id.tvComboScript)
        tvStatus = root.findViewById(R.id.tvComboStatus)
        btnRecord = root.findViewById(R.id.btnRecord)
        btnPlay = root.findViewById(R.id.btnPlay)
        btnPlayMode = root.findViewById(R.id.btnPlayMode)
        tvLoopCount = root.findViewById(R.id.tvLoopCount)
        btnLoopMinus = root.findViewById(R.id.btnLoopMinus)
        btnLoopPlus = root.findViewById(R.id.btnLoopPlus)
        sbSpeed = root.findViewById(R.id.sbComboSpeed)
        tvSpeedVal = root.findViewById(R.id.tvComboSpeedVal)

        root.findViewById<View>(R.id.btnPanelClose).setOnClickListener { removePanel() }
        root.findViewById<View>(R.id.btnScriptPrev).setOnClickListener { cycleScript(-1) }
        root.findViewById<View>(R.id.btnScriptNext).setOnClickListener { cycleScript(1) }
        root.findViewById<View>(R.id.btnComboEdit).setOnClickListener { openEditor() }
        root.findViewById<View>(R.id.btnComboList).setOnClickListener { openScriptList() }

        switchMaster.setOnCheckedChangeListener { _: android.widget.CompoundButton?, checked: Boolean ->
            if (suppressToggle) return@setOnCheckedChangeListener
            if (!checked) {
                AutoComboController.applyState(this, false)
                removePanel()
            }
        }

        btnPlayMode.setOnClickListener { cyclePlayMode() }
        btnLoopMinus.setOnClickListener { adjustLoop(-1) }
        btnLoopPlus.setOnClickListener { adjustLoop(1) }

        sbSpeed.max = 30
        sbSpeed.progress = 10
        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = speedFromProgress(progress)
                tvSpeedVal.text = String.format("%.1fx", speed)
                if (fromUser) saveSpeedToCurrent(speed)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnRecord.setOnClickListener { toggleRecording() }
        btnPlay.setOnClickListener { togglePlayback() }
    }

    private fun refreshPanel() {
        suppressToggle = true
        switchMaster.isChecked = AutoComboRepository.isEnabled(this)
        suppressToggle = false

        refreshScriptLabel()
        val script = AutoComboRepository.getCurrentScript(this)
        refreshModeUi(script)
        if (script != null) {
            sbSpeed.progress = progressFromSpeed(script.speed)
        }
        refreshStatusText()
    }

    private fun refreshScriptLabel() {
        val script = AutoComboRepository.getCurrentScript(this)
        tvScript.text = script?.let { "${it.name}（${it.steps.size}步）" } ?: "未创建脚本"
    }

    private fun refreshModeUi(script: ComboScript?) {
        btnPlayMode.text = when (script?.playMode) {
            ComboPlayMode.COUNT -> "次数"
            ComboPlayMode.INFINITE -> "无限"
            else -> "单次"
        }
        val countVisible = script?.playMode == ComboPlayMode.COUNT
        btnLoopMinus.visibility = if (countVisible) View.VISIBLE else View.GONE
        btnLoopPlus.visibility = if (countVisible) View.VISIBLE else View.GONE
        tvLoopCount.visibility = if (countVisible) View.VISIBLE else View.GONE
        tvLoopCount.text = script?.loopCount?.toString() ?: "1"
    }

    private fun cyclePlayMode() {
        val script = AutoComboRepository.getCurrentScript(this) ?: return
        val next = when (script.playMode) {
            ComboPlayMode.SINGLE -> ComboPlayMode.COUNT
            ComboPlayMode.COUNT -> ComboPlayMode.INFINITE
            ComboPlayMode.INFINITE -> ComboPlayMode.SINGLE
        }
        AutoComboRepository.saveScript(this, script.copy(playMode = next))
        refreshModeUi(AutoComboRepository.getScript(this, script.id))
        refreshScriptLabel()
    }

    private fun adjustLoop(delta: Int) {
        val script = AutoComboRepository.getCurrentScript(this) ?: return
        val count = (script.loopCount + delta).coerceIn(1, 999)
        AutoComboRepository.saveScript(this, script.copy(loopCount = count))
        refreshModeUi(AutoComboRepository.getScript(this, script.id))
    }

    private fun cycleScript(direction: Int) {
        val scripts = AutoComboRepository.listScripts(this)
        if (scripts.isEmpty()) return
        val currentId = AutoComboRepository.getCurrentScriptId(this)
        val index = scripts.indexOfFirst { it.id == currentId }
        val nextIndex = when {
            scripts.isEmpty() -> -1
            index < 0 -> 0
            else -> ((index + direction) % scripts.size + scripts.size) % scripts.size
        }
        AutoComboRepository.setCurrentScriptId(this, scripts[nextIndex].id)
        refreshScriptLabel()
        refreshModeUi(scripts[nextIndex])
        sbSpeed.progress = progressFromSpeed(scripts[nextIndex].speed)
        refreshStatusText()
    }

    private fun openEditor() {
        val script = AutoComboRepository.getCurrentScript(this) ?: return
        runCatching {
            startActivity(
                Intent(this, AutoComboEditorActivity::class.java)
                    .putExtra("scriptId", script.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun openScriptList() {
        runCatching {
            startActivity(
                Intent(this, AutoComboActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun speedFromProgress(progress: Int): Float =
        0.5f + (progress / 30f) * 1.5f

    private fun progressFromSpeed(speed: Float): Int =
        (((speed.coerceIn(0.5f, 2.0f) - 0.5f) / 1.5f) * 30).toInt()

    private fun currentSpeedValue(): Float = speedFromProgress(sbSpeed.progress)

    private fun saveSpeedToCurrent(speed: Float) {
        val script = AutoComboRepository.getCurrentScript(this) ?: return
        AutoComboRepository.saveScript(this, script.copy(speed = speed))
    }

    private fun toggleRecording() {
        if (recording) {
            stopRecordingAndSave()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (playing) {
            Toast.makeText(this, "回放中请先停止回放", Toast.LENGTH_SHORT).show()
            return
        }
        // 无障碍服务未开启时录制坐标仍会生成，但手势注入会静默跳过，用户录完才发现无效
        if (AutoComboAccessibilityService.current == null) {
            Toast.makeText(this, "请先在系统无障碍设置中开启寒枫的自动连招服务", Toast.LENGTH_LONG).show()
            AutoComboAccessibilityService.openAccessibilitySettings(this)
            return
        }
        recorder = AutoComboRecorder(windowManager).also {
            it.callback = object : AutoComboRecorder.Callback {
                override fun onStepChanged(stepCount: Int) {
                    handler.post { tvStatus.text = "录制中：$stepCount 步" }
                }
            }
            it.start(AutoComboAccessibilityService.current)
        }
        recording = true
        btnRecord.text = "停止录制"
        tvStatus.text = "录制中：0 步"
    }

    private fun stopRecordingAndSave() {
        val steps = stopRecording()
        if (steps.isEmpty()) {
            Toast.makeText(this, "没有录制到任何操作", Toast.LENGTH_SHORT).show()
            refreshStatusText()
            return
        }
        val label = android.text.format.DateFormat.format("MMdd_HHmm", System.currentTimeMillis())
        val script = com.HanFeng.model.ComboScript(
            id = "",
            name = "连招_$label",
            steps = steps,
            speed = currentSpeedValue()
        )
        AutoComboRepository.saveScript(this, script)
        Toast.makeText(this, "已保存脚本：${script.name}", Toast.LENGTH_SHORT).show()
        refreshScriptLabel()
        refreshModeUi(script)
        refreshStatusText()
    }

    private fun stopRecording(): List<com.HanFeng.model.ComboStep> {
        recording = false
        btnRecord.text = "开始录制"
        return recorder?.stop().orEmpty()
    }

    private fun togglePlayback() {
        if (playing) {
            stopPlaybackInternal()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        val script = AutoComboRepository.getCurrentScript(this)
        if (script == null || script.steps.isEmpty()) {
            Toast.makeText(this, "当前脚本没有可回放的步骤", Toast.LENGTH_SHORT).show()
            return
        }
        if (recording) {
            Toast.makeText(this, "录制中请先停止录制", Toast.LENGTH_SHORT).show()
            return
        }
        val service = AutoComboAccessibilityService.current
        if (service == null) {
            Toast.makeText(this, "需要先开启自动连招的无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }
        playing = true
        btnPlay.text = "停止"
        btnRecord.isEnabled = false
        service.startPlayback(script, playbackListener)
        keepScreenOn(true)
    }

    private val playbackListener = object : AutoComboEngine.Listener {
        override fun onStepChanged(loopIndex: Int, stepIndex: Int, totalSteps: Int) {
            handler.post { tvStatus.text = "回放中 ${loopIndex + 1} 轮 ${stepIndex + 1}/$totalSteps" }
        }

        override fun onFinished(reason: AutoComboEngine.FinishReason) {
            handler.post { refreshPlaybackEnd(reason, null) }
        }

        override fun onError(message: String) {
            handler.post { refreshPlaybackEnd(AutoComboEngine.FinishReason.ERROR, message) }
        }
    }

    private fun refreshPlaybackEnd(reason: AutoComboEngine.FinishReason, message: String?) {
        playing = false
        btnPlay.text = "回放"
        btnRecord.isEnabled = true
        keepScreenOn(false)
        tvStatus.text = when (reason) {
            AutoComboEngine.FinishReason.COMPLETED -> "回放完成"
            AutoComboEngine.FinishReason.STOPPED -> "已停止"
            AutoComboEngine.FinishReason.ERROR -> message ?: "回放出错"
        }
        if (reason == AutoComboEngine.FinishReason.ERROR) {
            Toast.makeText(this, tvStatus.text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlaybackInternal() {
        AutoComboAccessibilityService.current?.stopPlayback()
        playing = false
        btnPlay.text = "回放"
        btnRecord.isEnabled = true
        keepScreenOn(false)
        refreshStatusText()
    }

    private fun refreshStatusText() {
        if (recording) {
            tvStatus.text = "录制中：${recorder?.stepCount ?: 0} 步"
            return
        }
        if (playing) {
            tvStatus.text = "回放中..."
            return
        }
        val script = AutoComboRepository.getCurrentScript(this)
        tvStatus.text = if (script == null) "空闲（无脚本）" else "空闲"
    }

    private fun keepScreenOn(on: Boolean) {
        listOfNotNull(ballView, panelView).forEach { view ->
            runCatching {
                val lp = view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
                if (on) {
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                } else {
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                }
                windowManager.updateViewLayout(view, lp)
            }
        }
    }

    private fun attachDrag(view: View, params: WindowManager.LayoutParams, onTap: (() -> Unit)?) {
        var lastX = 0f
        var lastY = 0f
        var initialX = 0f
        var initialY = 0f
        var dragging = false
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = event.rawX
                    initialY = event.rawY
                    lastX = event.rawX
                    lastY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    if (abs(event.rawX - initialX) > TOUCH_SLOP || abs(event.rawY - initialY) > TOUCH_SLOP) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) onTap?.invoke()
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun getOverlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "自动连招", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "保持连招悬浮球持续显示"
                        setShowBadge(false)
                    }
                )
            }
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.HanFeng.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HanFeng 自动连招")
            .setContentText("连招悬浮球正在运行")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()
    }
}