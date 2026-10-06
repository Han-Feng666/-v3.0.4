package com.HanFeng.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.HanFeng.R
import com.HanFeng.adblocker.shizuku.BluetoothHeadsetManager
import com.HanFeng.adblocker.shizuku.CoolAutoTuner
import com.HanFeng.adblocker.shizuku.CoolerManager
import com.HanFeng.adblocker.shizuku.SuSession
import com.HanFeng.data.PeripheralRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 外设管理页：蓝牙耳机（降噪指引 / 音量 / 音效）+ 散热器（BLE 功率 / 灯效 / 温度 / 智能温控）。
 */
class PeripheralManagementActivity : AppCompatActivity(), CoolerManager.Listener {

    companion object {
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }

    private lateinit var headsetManager: BluetoothHeadsetManager
    private lateinit var coolerManager: CoolerManager
    private var suSession: SuSession? = null

    private lateinit var tvHeadsetStatus: TextView
    private lateinit var tvAncHint: TextView
    private lateinit var tvVolume: TextView
    private lateinit var tvBass: TextView
    private lateinit var tvVirtualizer: TextView
    private lateinit var tvCoolerStatus: TextView
    private lateinit var tvCoolerPower: TextView
    private lateinit var tvRgbBrightness: TextView
    private lateinit var tvTemperature: TextView

    private var selectedHeadset: BluetoothHeadsetManager.HeadsetDevice? = null
    private var selectedCooler: CoolerManager.DiscoveredCooler? = null
    private var currentRgbEffect = PeripheralRepository.RGB_EFFECT_STATIC
    private var currentRgbColor = 0xFF3D5AFE.toInt()
    private var coolerTempCelsius: Double? = null
    private var phoneTempCelsius: Double? = null
    private var coolerPickerShown = false
    private val scanStopHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val coolAutoTuner = CoolAutoTuner()
    private val autoTuneHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoTuneRunnable = object : Runnable {
        override fun run() {
            refreshAutoTuneOnce()
            autoTuneHandler.postDelayed(this, 5000)
        }
    }
    private var volumeReceiverRegistered = false
    private val volumeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (isFinishing || isDestroyed) return
            if (intent?.action == VOLUME_CHANGED_ACTION) {
                val sbVolume = findViewById<SeekBar>(R.id.sbVolume) ?: return
                val pct = headsetManager.getMediaVolumePercent()
                sbVolume.progress = pct
                tvVolume.text = "$pct%"
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            loadHeadsets()
        } else {
            Toast.makeText(this, "需要蓝牙权限才能管理外设", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_peripheral)

        headsetManager = BluetoothHeadsetManager(this)
        coolerManager = CoolerManager(this)
        coolerManager.listener = this

        tvHeadsetStatus = findViewById(R.id.tvHeadsetStatus)
        tvAncHint = findViewById(R.id.tvAncHint)
        tvVolume = findViewById(R.id.tvVolume)
        tvBass = findViewById(R.id.tvBass)
        tvVirtualizer = findViewById(R.id.tvVirtualizer)
        tvCoolerStatus = findViewById(R.id.tvCoolerStatus)
        tvCoolerPower = findViewById(R.id.tvCoolerPower)
        tvRgbBrightness = findViewById(R.id.tvRgbBrightness)
        tvTemperature = findViewById(R.id.tvTemperature)

        setupTabs()
        setupHeadsetControls()
        setupCoolerControls()
        requestBlePermissionsThenLoad()
    }

    // ==================== 双页 Tab ====================

    private fun setupTabs() {
        val tabHeadset = findViewById<Button>(R.id.btnTabHeadset)
        val tabCooler = findViewById<Button>(R.id.btnTabCooler)
        val svHeadset = findViewById<android.view.View>(R.id.svHeadset)
        val svCooler = findViewById<android.view.View>(R.id.svCooler)
        fun select(headset: Boolean) {
            svHeadset.visibility = if (headset) android.view.View.VISIBLE else android.view.View.GONE
            svCooler.visibility = if (headset) android.view.View.GONE else android.view.View.VISIBLE
            tabHeadset.alpha = if (headset) 1f else 0.5f
            tabCooler.alpha = if (headset) 0.5f else 1f
        }
        tabHeadset.setOnClickListener { select(true) }
        tabCooler.setOnClickListener { select(false) }
        select(true)
    }

    override fun onDestroy() {
        scanStopHandler.removeCallbacksAndMessages(null)
        autoTuneHandler.removeCallbacksAndMessages(null)
        unregisterVolumeReceiver()
        coolerManager.stopScan()
        coolerManager.disconnect()
        headsetManager.releaseAudioEffects()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        registerVolumeReceiverIfNeeded()
        if (PeripheralRepository.isCoolAutoTunerEnabled(this)) startAutoTuner()
        autoReconnectCooler()
    }

    override fun onPause() {
        autoTuneHandler.removeCallbacks(autoTuneRunnable)
        unregisterVolumeReceiver()
        super.onPause()
    }

    private fun registerVolumeReceiverIfNeeded() {
        if (volumeReceiverRegistered) return
        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(volumeReceiver, filter)
        }
        volumeReceiverRegistered = true
    }

    private fun unregisterVolumeReceiver() {
        if (!volumeReceiverRegistered) return
        runCatching { unregisterReceiver(volumeReceiver) }
        volumeReceiverRegistered = false
    }

    // ==================== 智能温控 ====================

    private fun startAutoTuner() {
        coolAutoTuner.reset()
        autoTuneHandler.removeCallbacks(autoTuneRunnable)
        autoTuneRunnable.run()
    }

    private fun stopAutoTuner() {
        autoTuneHandler.removeCallbacks(autoTuneRunnable)
    }

    private fun refreshAutoTuneOnce() {
        if (isFinishing || isDestroyed) return
        lifecycleScope.launch {
            val session = ensureRootSession() ?: return@launch
            val temp = withContext(Dispatchers.IO) {
                coolerManager.queryPhoneTemperatureViaRoot(session)
            }
            if (isFinishing || isDestroyed) return@launch
            phoneTempCelsius = temp
            updateTemperatureText()
            val t = temp ?: return@launch
            val decision = coolAutoTuner.onTemperature(t)
            val statusView = findViewById<TextView>(R.id.tvAutoTunerStatus) ?: return@launch
            statusView.text = decision.reason
            if (decision.changed && decision.newLevel != null && coolerManager.isConnected()) {
                val sent = coolerManager.sendPower(decision.newLevel)
                if (sent) {
                    val sbPower = findViewById<SeekBar>(R.id.sbCoolerPower) ?: return@launch
                    sbPower.progress = decision.newLevel
                    tvCoolerPower.text = "${decision.newLevel}%"
                    PeripheralRepository.setCoolerPower(this@PeripheralManagementActivity, decision.newLevel)
                }
            }
        }
    }

    // ==================== 蓝牙耳机 ====================

    private fun hasBluetoothConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun requestBlePermissionsThenLoad() {
        if (hasBluetoothConnectPermission()) {
            loadHeadsets()
            return
        }
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
            wanted += Manifest.permission.BLUETOOTH_SCAN
        }
        if (wanted.isNotEmpty()) {
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }

    private fun loadHeadsets() {
        if (!hasBluetoothConnectPermission()) return
        lifecycleScope.launch {
            val devices = withContext(Dispatchers.IO) { headsetManager.listBondedAudioDevices() }
            if (isFinishing || isDestroyed) return@launch
            if (devices.isEmpty()) {
                tvHeadsetStatus.text = "未发现已配对的蓝牙音频设备"
                return@launch
            }
            // 已连接设备优先自动选中; 无已连接则恢复上次选择的耳机
            val connected = devices.firstOrNull { headsetManager.isDeviceConnected(it.address) }
            val lastMac = PeripheralRepository.getLastHeadsetAddress(this@PeripheralManagementActivity)
            val last = devices.firstOrNull { it.address == lastMac }
            val auto = connected ?: last
            if (auto != null) {
                applySelectedHeadset(auto)
            } else {
                tvHeadsetStatus.text = "已配对 ${devices.size} 个音频设备，点击下方按钮选择"
            }
        }
    }

    /** 刷新当前耳机的连接状态/电量/编解码（"刷新状态"按钮与 onResume 调用） */
    private fun refreshHeadsetStatus() {
        val dev = selectedHeadset ?: run {
            loadHeadsets()
            return
        }
        tvHeadsetStatus.text = "当前耳机：${dev.name}\n正在读取状态..."
        lifecycleScope.launch {
            val session = ensureRootSession()
            val battery = session?.let {
                withContext(Dispatchers.IO) { headsetManager.queryBatteryViaRoot(it, dev.address) }
            }
            val codec = session?.let {
                withContext(Dispatchers.IO) { headsetManager.queryCodecViaRoot(it, dev.address) }
            }
            val apiBattery = withContext(Dispatchers.IO) { headsetManager.queryBatteryViaApi(dev.address) }
            if (isFinishing || isDestroyed) return@launch
            val connected = headsetManager.isDeviceConnected(dev.address)
            val parts = mutableListOf(
                "当前耳机：${dev.name}${if (connected) "（已连接）" else "（未连接）"}"
            )
            parts += when {
                battery != null -> "电量 $battery%"
                apiBattery != null -> "电量 $apiBattery%"
                else -> "电量未知"
            }
            parts += if (codec != null) "编解码 $codec" else "编解码未知"
            tvHeadsetStatus.text = parts.joinToString("\n")
        }
    }

    private fun showHeadsetPicker(devices: List<BluetoothHeadsetManager.HeadsetDevice>) {
        lifecycleScope.launch {
            val connectedAddrs = withContext(Dispatchers.IO) {
                headsetManager.connectedBluetoothOutputAddresses()
            }
            if (isFinishing || isDestroyed) return@launch
            val names = devices.map { d ->
                d.name +
                    (if (connectedAddrs.any { it.equals(d.address, true) }) "（已连接）" else "") +
                    (d.ancAdapter?.let { a -> "（${a.vendorName}降噪）" } ?: "")
            }
            var index = intArrayOf(0)
            StableDialog.materialBuilder(this@PeripheralManagementActivity)
                .setTitle("选择耳机")
                .setSingleChoiceItems(names.toTypedArray(), 0) { _, which -> index[0] = which }
                .setPositiveButton("选择") { _, _ ->
                    applySelectedHeadset(devices[index[0]])
                }
                .setNegativeButton("取消", null)
                .showMaterialSafely(this@PeripheralManagementActivity, "headset picker")
        }
    }

    private fun applySelectedHeadset(device: BluetoothHeadsetManager.HeadsetDevice) {
        selectedHeadset = device
        PeripheralRepository.setLastHeadset(this, device.address, device.name)
        tvAncHint.text = device.ancAdapter?.hint ?: "该型号未收录降噪适配信息，可用下方音效增强听感"
        tvHeadsetStatus.text = "当前耳机：${device.name}\n正在读取电量与编解码..."
        lifecycleScope.launch {
            val session = ensureRootSession()
            val battery = session?.let {
                withContext(Dispatchers.IO) { headsetManager.queryBatteryViaRoot(it, device.address) }
            }
            val codec = session?.let {
                withContext(Dispatchers.IO) { headsetManager.queryCodecViaRoot(it, device.address) }
            }
            val apiBattery = withContext(Dispatchers.IO) { headsetManager.queryBatteryViaApi(device.address) }
            if (isFinishing || isDestroyed) return@launch
            val connected = headsetManager.isDeviceConnected(device.address)
            val parts = mutableListOf(
                "当前耳机：${device.name}${if (connected) "（已连接）" else "（未连接）"}"
            )
            parts += when {
                battery != null -> "电量 $battery%"
                apiBattery != null -> "电量 $apiBattery%"
                else -> "电量未知"
            }
            parts += if (codec != null) "编解码 $codec" else "编解码未知"
            tvHeadsetStatus.text = parts.joinToString("\n")
        }
    }

    private fun setupHeadsetControls() {
        findViewById<Button>(R.id.btnHeadsetPick).setOnClickListener {
            if (hasBluetoothConnectPermission()) {
                lifecycleScope.launch {
                    val devices = withContext(Dispatchers.IO) { headsetManager.listBondedAudioDevices() }
                    if (isFinishing || isDestroyed) return@launch
                    if (devices.isEmpty()) {
                        tvHeadsetStatus.text = "未发现已配对的蓝牙音频设备"
                    } else {
                        showHeadsetPicker(devices)
                    }
                }
            } else {
                requestBlePermissionsThenLoad()
            }
        }
        findViewById<Button>(R.id.btnHeadsetRefresh).setOnClickListener { refreshHeadsetStatus() }
        findViewById<Button>(R.id.btnAncOff).setOnClickListener { setAncMode(PeripheralRepository.ANC_OFF) }
        findViewById<Button>(R.id.btnAncOn).setOnClickListener { setAncMode(PeripheralRepository.ANC_ON) }
        findViewById<Button>(R.id.btnAncTransparency).setOnClickListener {
            setAncMode(PeripheralRepository.ANC_TRANSPARENCY)
        }
        val sbVolume = findViewById<SeekBar>(R.id.sbVolume)
        val sbBass = findViewById<SeekBar>(R.id.sbBass)
        val sbVirtualizer = findViewById<SeekBar>(R.id.sbVirtualizer)
        sbVolume.progress = headsetManager.getMediaVolumePercent()
        tvVolume.text = "${sbVolume.progress}%"
        sbBass.progress = 0
        sbVirtualizer.progress = 0
        sbVolume.setOnSeekBarChangeListener(
            seekListener(
                onChanged = { pct ->
                    tvVolume.text = "$pct%"
                    headsetManager.setMediaVolumePercent(pct)
                }
            )
        )
        sbBass.setOnSeekBarChangeListener(seekListener(onChanged = { v -> tvBass.text = "$v" }))
        sbVirtualizer.setOnSeekBarChangeListener(
            seekListener(onChanged = { v -> tvVirtualizer.text = "$v" })
        )
        findViewById<Button>(R.id.btnApplyEffects).setOnClickListener {
            val result = headsetManager.applyAudioEffects(sbBass.progress, sbVirtualizer.progress)
            Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
            val dev = selectedHeadset
            if (dev != null && result.success) {
                PeripheralRepository.setEqBass(this, dev.address, sbBass.progress)
                PeripheralRepository.setEqVirtualizer(this, dev.address, sbVirtualizer.progress)
            }
        }
        findViewById<Button>(R.id.btnPresetPop).setOnClickListener { applyPreset(500, 300, "流行") }
        findViewById<Button>(R.id.btnPresetBass).setOnClickListener { applyPreset(800, 0, "低音") }
        findViewById<Button>(R.id.btnPresetVocal).setOnClickListener { applyPreset(200, 500, "人声") }
        findViewById<Button>(R.id.btnPresetFlat).setOnClickListener { applyPreset(0, 0, "关闭") }
    }

    /** EQ 预设：低音增强 + 虚拟环绕组合，一键应用并按设备持久化 */
    private fun applyPreset(bass: Int, virtualizer: Int, name: String) {
        val result = headsetManager.applyAudioEffects(bass, virtualizer)
        Toast.makeText(
            this,
            if (result.success) "$name 音效已应用" else result.message,
            Toast.LENGTH_SHORT
        ).show()
        if (result.success) {
            val sbBass = findViewById<SeekBar>(R.id.sbBass)
            val sbVirtualizer = findViewById<SeekBar>(R.id.sbVirtualizer)
            sbBass.progress = bass
            sbVirtualizer.progress = virtualizer
            tvBass.text = "$bass"
            tvVirtualizer.text = "$virtualizer"
            selectedHeadset?.let {
                PeripheralRepository.setEqBass(this, it.address, bass)
                PeripheralRepository.setEqVirtualizer(this, it.address, virtualizer)
            }
        }
    }

    private fun setAncMode(mode: String) {
        val device = selectedHeadset ?: run {
            Toast.makeText(this, "请先选择耳机", Toast.LENGTH_SHORT).show()
            return
        }
        val adapter = device.ancAdapter
        if (adapter == null) {
            Toast.makeText(this, "该型号未收录降噪适配信息", Toast.LENGTH_SHORT).show()
            return
        }
        PeripheralRepository.setAncMode(this, device.address, mode)
        val modeName = when (mode) {
            PeripheralRepository.ANC_ON -> "降噪"
            PeripheralRepository.ANC_TRANSPARENCY -> "通透"
            else -> "关闭"
        }
        Toast.makeText(this, "${adapter.vendorName}耳机已记录「$modeName」偏好", Toast.LENGTH_SHORT).show()
    }

    // ==================== 散热器 ====================

    private fun setupCoolerControls() {
        val sbPower = findViewById<SeekBar>(R.id.sbCoolerPower)
        val sbBrightness = findViewById<SeekBar>(R.id.sbRgbBrightness)
        sbPower.progress = PeripheralRepository.getCoolerPower(this)
        tvCoolerPower.text = "${sbPower.progress}%"
        sbBrightness.progress = PeripheralRepository.getCoolerRgbBrightness(this)
        tvRgbBrightness.text = "${sbBrightness.progress}"
        currentRgbEffect = PeripheralRepository.getCoolerRgbEffect(this)
        currentRgbColor = PeripheralRepository.getCoolerRgbColor(this)

        findViewById<Button>(R.id.btnCoolerScan).setOnClickListener {
            if (checkScanPermission()) {
                selectedCooler = null
                coolerPickerShown = false
                tvCoolerStatus.text = "正在扫描附近的散热器（约 8 秒）..."
                coolerManager.startScan()
                scanStopHandler.postDelayed({
                    coolerManager.stopScan()
                    if (!coolerPickerShown) {
                        tvCoolerStatus.text = "扫描结束：未发现可连接的散热器，请确认背夹已开机进入配对状态"
                    }
                }, 8000)
            }
        }
        findViewById<Button>(R.id.btnCoolerDisconnect).setOnClickListener {
            coolerManager.stopScan()
            coolerManager.disconnect()
            tvCoolerStatus.text = "已断开"
        }
        sbPower.setOnSeekBarChangeListener(
            seekListener(
                onChanged = { v -> tvCoolerPower.text = "$v%" },
                onStopped = {
                    PeripheralRepository.setCoolerPower(this, sbPower.progress)
                    val ok = coolerManager.sendPower(sbPower.progress)
                    Toast.makeText(
                        this,
                        if (ok) "功率 ${sbPower.progress}% 已发送" else "发送失败，请确认散热器已连接",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        )
        sbBrightness.setOnSeekBarChangeListener(
            seekListener(
                onChanged = { v -> tvRgbBrightness.text = "$v" },
                onStopped = { PeripheralRepository.setCoolerRgbBrightness(this, sbBrightness.progress) }
            )
        )
        findViewById<Button>(R.id.btnRgbOff).setOnClickListener { pickRgb(PeripheralRepository.RGB_EFFECT_OFF) }
        findViewById<Button>(R.id.btnRgbStatic).setOnClickListener { pickRgb(PeripheralRepository.RGB_EFFECT_STATIC) }
        findViewById<Button>(R.id.btnRgbBreath).setOnClickListener { pickRgb(PeripheralRepository.RGB_EFFECT_BREATH) }
        findViewById<Button>(R.id.btnRgbRainbow).setOnClickListener { pickRgb(PeripheralRepository.RGB_EFFECT_RAINBOW) }
        findViewById<Button>(R.id.btnColorRed).setOnClickListener { pickRgbColor(0xFFE53935.toInt()) }
        findViewById<Button>(R.id.btnColorGreen).setOnClickListener { pickRgbColor(0xFF43A047.toInt()) }
        findViewById<Button>(R.id.btnColorBlue).setOnClickListener { pickRgbColor(0xFF3D5AFE.toInt()) }
        findViewById<Button>(R.id.btnColorWhite).setOnClickListener { pickRgbColor(0xFFF5F5F5.toInt()) }
        findViewById<Button>(R.id.btnCoolerApply).setOnClickListener { applyRgb() }
        // 未知型号散热器（如派威）协议试探：向当前写入特征发送不同格式的帧，
        // 设备有反应（风扇/灯）即说明该格式可用，反馈给开发者固化协议
        findViewById<Button>(R.id.btnProbeRedMagic).setOnClickListener {
            probeFrame(
                "红魔格式功率帧 60%",
                CoolerManager.RedMagicCoolerProtocol.buildFrame(
                    CoolerManager.RedMagicCoolerProtocol.CMD_POWER,
                    byteArrayOf(60)
                )
            )
        }
        findViewById<Button>(R.id.btnProbeBlackShark).setOnClickListener {
            probeFrame("黑鲨格式功率帧 60%", byteArrayOf(0x51, (60 * 0xFF / 100).toByte()))
        }
        findViewById<Button>(R.id.btnProbeAscii).setOnClickListener {
            probeFrame("ASCII 文本帧 PWR:60", "PWR:60\n".toByteArray(Charsets.US_ASCII))
        }
        findViewById<Button>(R.id.btnModeSilent).setOnClickListener { applyCoolerMode(30, lightsOff = true) }
        findViewById<Button>(R.id.btnModeBalance).setOnClickListener { applyCoolerMode(60, lightsOff = false) }
        findViewById<Button>(R.id.btnModeRage).setOnClickListener { applyCoolerMode(100, lightsOff = false) }
        val switchAutoTuner = findViewById<Switch>(R.id.switchCoolAutoTuner)
        switchAutoTuner.isChecked = PeripheralRepository.isCoolAutoTunerEnabled(this)
        switchAutoTuner.setOnCheckedChangeListener { _, checked ->
            PeripheralRepository.setCoolAutoTunerEnabled(this, checked)
            if (checked) startAutoTuner() else stopAutoTuner()
        }
    }

    /** 一键散热模式：手动接管时关闭智能温控，避免定时器随后覆盖用户选择 */
    private fun applyCoolerMode(power: Int, lightsOff: Boolean) {
        val switchAutoTuner = findViewById<Switch>(R.id.switchCoolAutoTuner)
        if (switchAutoTuner.isChecked) {
            switchAutoTuner.isChecked = false
            PeripheralRepository.setCoolAutoTunerEnabled(this, false)
            stopAutoTuner()
        }
        PeripheralRepository.setCoolerPower(this, power)
        val sbPower = findViewById<SeekBar>(R.id.sbCoolerPower)
        sbPower.progress = power
        tvCoolerPower.text = "$power%"
        val ok = coolerManager.sendPower(power)
        if (lightsOff) {
            currentRgbEffect = PeripheralRepository.RGB_EFFECT_OFF
            PeripheralRepository.setCoolerRgbEffect(this, currentRgbEffect)
            coolerManager.sendRgb(
                currentRgbEffect,
                currentRgbColor,
                PeripheralRepository.getCoolerRgbBrightness(this)
            )
        }
        Toast.makeText(
            this,
            if (ok) "已切换到 $power% 功率" else "发送失败，请确认散热器已连接",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun pickRgb(effect: String) {
        currentRgbEffect = effect
        PeripheralRepository.setCoolerRgbEffect(this, effect)
        applyRgb()
    }

    /** 协议试探：发送候选帧并如实反馈结果 */
    private fun probeFrame(description: String, frame: ByteArray) {
        if (!coolerManager.isConnected()) {
            Toast.makeText(this, "请先连接散热器", Toast.LENGTH_SHORT).show()
            return
        }
        val hex = frame.joinToString(" ") { String.format("%02X", it) }
        val ok = coolerManager.sendRaw(frame)
        Toast.makeText(
            this,
            if (ok) "已发送 $description（$hex），观察设备是否响应" else "发送失败",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun pickRgbColor(color: Int) {
        currentRgbColor = color
        PeripheralRepository.setCoolerRgbColor(this, color)
        applyRgb()
    }

    private fun applyRgb() {
        val ok = coolerManager.sendRgb(
            currentRgbEffect,
            currentRgbColor,
            PeripheralRepository.getCoolerRgbBrightness(this)
        )
        Toast.makeText(
            this,
            if (ok) "灯效已发送" else "发送失败，请确认散热器已连接",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun checkScanPermission(): Boolean {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
            ) perms += Manifest.permission.BLUETOOTH_SCAN
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
            return false
        }
        if (!coolerManager.isBluetoothEnabled()) {
            Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    /** 进入页面时自动回连上次的散热器（已连接则跳过） */
    private fun autoReconnectCooler() {
        if (coolerManager.isConnected()) return
        val address = PeripheralRepository.getLastCoolerAddress(this) ?: return
        if (!hasBluetoothConnectPermission()) return
        val adapter = bluetoothAdapter() ?: return
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        val name = PeripheralRepository.getLastCoolerName(this)
        val protocol = coolerManager.findProtocolFor(name)
            ?: CoolerManager.GenericCoolerProtocol()
        tvCoolerStatus.text = "正在回连上次的散热器 $name..."
        coolerManager.connect(device, protocol)
    }

    // ==================== CoolerManager.Listener ====================

    override fun onScanResult(devices: List<CoolerManager.DiscoveredCooler>) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (coolerPickerShown) return@runOnUiThread
            if (devices.isEmpty()) return@runOnUiThread
            // 全部设备保留，按"已知协议 → 疑似散热器命名 → 其它"排序，
            // 避免附近存在红魔/黑鲨等已知设备时把派威等未知型号从候选里挤掉
            val known = devices.filter { it.protocol != null }
            val keywords = listOf("COOL", "FAN", "PIVA", "派威", "散热", "背夹", "REFRIG", "PW-", "PW ")
            val likely = devices.filter {
                it.protocol == null && keywords.any { kw -> it.name.uppercase().contains(kw) }
            }
            val rest = devices.filter { it !in known && it !in likely }
            coolerPickerShown = true
            showCoolerPicker(known + likely + rest)
        }
    }

    private fun showCoolerPicker(devices: List<CoolerManager.DiscoveredCooler>) {
        if (isFinishing || isDestroyed) return
        coolerManager.stopScan()
        val names = devices.map { d ->
            d.name + (d.protocol?.let { "（${it.displayName}）" } ?: "（未知型号）")
        }
        var index = intArrayOf(0)
        StableDialog.materialBuilder(this)
            .setTitle("选择散热器")
            .setSingleChoiceItems(names.toTypedArray(), 0) { _, which -> index[0] = which }
            .setPositiveButton("连接") { _, _ ->
                val target = devices[index[0]]
                selectedCooler = target
                PeripheralRepository.setLastCooler(this, target.address, target.name)
                tvCoolerStatus.text = "正在连接 ${target.name}..."
                connectCooler(target)
            }
            .setNegativeButton("取消", null)
            .showMaterialSafely(this, "cooler picker")
    }

    private fun connectCooler(target: CoolerManager.DiscoveredCooler) {
        val adapter = bluetoothAdapter() ?: run {
            tvCoolerStatus.text = "蓝牙不可用"
            return
        }
        val protocol = target.protocol ?: coolerManager.findProtocolFor(target.name)
            ?: CoolerManager.GenericCoolerProtocol()
        val device: BluetoothDevice = adapter.getRemoteDevice(target.address)
        coolerManager.connect(device, protocol)
    }

    private fun bluetoothAdapter(): android.bluetooth.BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    override fun onConnected(protocol: CoolerManager.CoolerProtocol) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            tvCoolerStatus.text = "已连接：${protocol.displayName}"
            val power = PeripheralRepository.getCoolerPower(this)
            coolerManager.sendPower(power)
            applyRgb()
            refreshPhoneTemperature()
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            coolerTempCelsius = null
            updateTemperatureText()
            tvCoolerStatus.text = "散热器已断开"
        }
    }

    override fun onTemperature(celsius: Double) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            coolerTempCelsius = celsius
            updateTemperatureText()
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            tvCoolerStatus.text = message
        }
    }

    // ==================== 通用 ====================

    private fun updateTemperatureText() {
        val cooler = coolerTempCelsius?.let { "散热器 %.1f°C".format(it) } ?: "散热器 --"
        val phone = phoneTempCelsius?.let { "手机 %.1f°C".format(it) } ?: "手机 --"
        tvTemperature.text = "温度：$cooler / $phone"
    }

    private suspend fun ensureRootSession(): SuSession? = withContext(Dispatchers.IO) {
        val existing = suSession
        if (existing != null && existing.isSessionOpen()) return@withContext existing
        val session = SuSession.getInstance()
        if (session.open(30)) {
            suSession = session
            session
        } else {
            null
        }
    }

    private fun seekListener(
        onChanged: (Int) -> Unit,
        onStopped: () -> Unit = {}
    ): SeekBar.OnSeekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChanged(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}

        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            onStopped()
        }
    }

    private fun refreshPhoneTemperature() {
        lifecycleScope.launch {
            val session = ensureRootSession() ?: return@launch
            val temp = withContext(Dispatchers.IO) {
                coolerManager.queryPhoneTemperatureViaRoot(session)
            }
            if (isFinishing || isDestroyed) return@launch
            phoneTempCelsius = temp
            updateTemperatureText()
        }
    }
}
