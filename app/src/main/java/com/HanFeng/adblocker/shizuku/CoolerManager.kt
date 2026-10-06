package com.HanFeng.adblocker.shizuku

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * BLE 散热器（散热背夹/风扇）管理：扫描、GATT 连接、功率/灯效/温度控制。
 *
 * 协议说明：散热背夹没有公开统一协议。这里内置社区公开逆向的红魔/黑鲨帧格式，
 * 并保留通用透传协议供扩展。各协议均标注来源与置信度，发送失败如实回报。
 */
class CoolerManager(private val context: Context) {

    /** 协议适配器：帧编码为纯逻辑，可单测 */
    interface CoolerProtocol {
        val id: String
        val displayName: String
        /** 匹配设备名（大小写不敏感），null 表示不自动匹配 */
        fun matches(deviceName: String): Boolean
        /** 功率帧（level 0-100） */
        fun encodePower(level: Int): ByteArray
        /** 灯效帧（effect/color/brightness） */
        fun encodeRgb(effect: String, colorRgb: Int, brightness: Int): ByteArray
        /** 从通知数据解析设备温度（摄氏度），不支持返回 null */
        fun parseTemperature(data: ByteArray): Double? = null
        /** 控制特征 UUID（写），null 表示协议未定义 */
        val writeCharacteristicUuid: UUID?
        /** 温度通知特征 UUID，null 表示不支持 */
        val notifyCharacteristicUuid: UUID?
    }

    /**
     * 红魔散热背夹协议（社区公开逆向资料，非官方文档，实际机型可能存在差异）。
     * 帧头 0xAA + 命令 + 载荷 + 校验和（帧头到载荷累加取低 8 位）。
     */
    class RedMagicCoolerProtocol : CoolerProtocol {
        override val id = "redmagic"
        override val displayName = "红魔/努比亚 散热背夹"
        override val writeCharacteristicUuid: UUID =
            UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb")
        override val notifyCharacteristicUuid: UUID? =
            UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")

        override fun matches(deviceName: String): Boolean {
            val n = deviceName.uppercase()
            return n.contains("REDMAGIC") || n.contains("红魔") || n.contains("NUBIA")
        }

        override fun encodePower(level: Int): ByteArray {
            val clamped = level.coerceIn(0, 100)
            return buildFrame(CMD_POWER, byteArrayOf(clamped.toByte()))
        }

        override fun encodeRgb(effect: String, colorRgb: Int, brightness: Int): ByteArray {
            val effectCode = when (effect) {
                com.HanFeng.data.PeripheralRepository.RGB_EFFECT_OFF -> 0x00
                com.HanFeng.data.PeripheralRepository.RGB_EFFECT_STATIC -> 0x01
                com.HanFeng.data.PeripheralRepository.RGB_EFFECT_BREATH -> 0x02
                com.HanFeng.data.PeripheralRepository.RGB_EFFECT_RAINBOW -> 0x03
                else -> 0x01
            }
            val r = ((colorRgb shr 16) and 0xFF).toByte()
            val g = ((colorRgb shr 8) and 0xFF).toByte()
            val b = (colorRgb and 0xFF).toByte()
            return buildFrame(
                CMD_RGB,
                byteArrayOf(effectCode.toByte(), r, g, b, brightness.coerceIn(0, 100).toByte())
            )
        }

        override fun parseTemperature(data: ByteArray): Double? {
            // 温度帧: AA 05 tempX10(lo hi) checksum
            if (data.size < 5 || (data[0].toInt() and 0xFF) != FRAME_HEAD ||
                data[1].toInt() != CMD_TEMP_REPORT
            ) {
                return null
            }
            val raw = ((data[3].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
            val temp = raw / 10.0
            return if (temp in -20.0..120.0) temp else null
        }

        companion object {
            const val FRAME_HEAD = 0xAA
            const val CMD_POWER = 0x01
            const val CMD_RGB = 0x02
            const val CMD_TEMP_REPORT = 0x05

            fun buildFrame(cmd: Int, payload: ByteArray): ByteArray {
                val frame = ByteArray(payload.size + 3)
                frame[0] = FRAME_HEAD.toByte()
                frame[1] = cmd.toByte()
                System.arraycopy(payload, 0, frame, 2, payload.size)
                var sum = FRAME_HEAD + cmd
                for (b in payload) sum += b.toInt() and 0xFF
                frame[frame.size - 1] = (sum and 0xFF).toByte()
                return frame
            }

            fun verifyChecksum(frame: ByteArray): Boolean {
                if (frame.size < 3 || (frame[0].toInt() and 0xFF) != FRAME_HEAD) return false
                var sum = FRAME_HEAD + (frame[1].toInt() and 0xFF)
                for (i in 2 until frame.size - 1) sum += frame[i].toInt() and 0xFF
                return (sum and 0xFF) == (frame[frame.size - 1].toInt() and 0xFF)
            }
        }
    }

    /** 黑鲨散热背夹协议（社区公开资料，置信度低于红魔，供实测调参） */
    class BlackSharkCoolerProtocol : CoolerProtocol {
        override val id = "blackshark"
        override val displayName = "黑鲨 散热背夹"
        override val writeCharacteristicUuid: UUID =
            UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
        override val notifyCharacteristicUuid: UUID? = null

        override fun matches(deviceName: String): Boolean {
            val n = deviceName.uppercase()
            return n.contains("BLACK SHARK") || n.contains("黑鲨") || n.contains("FUNCOOLER")
        }

        override fun encodePower(level: Int): ByteArray {
            val clamped = level.coerceIn(0, 100)
            // 公开资料: 0x51 档位映射 0..100 -> 0..0xFF
            return byteArrayOf(0x51, (clamped * 0xFF / 100).toByte())
        }

        override fun encodeRgb(effect: String, colorRgb: Int, brightness: Int): ByteArray {
            val r = ((colorRgb shr 16) and 0xFF).toByte()
            val g = ((colorRgb shr 8) and 0xFF).toByte()
            val b = (colorRgb and 0xFF).toByte()
            val effectCode = when (effect) {
                com.HanFeng.data.PeripheralRepository.RGB_EFFECT_OFF -> 0x00
                com.HanFeng.data.PeripheralRepository.RGB_EFFECT_RAINBOW -> 0x04
                com.HanFeng.data.PeripheralRepository.RGB_EFFECT_BREATH -> 0x02
                else -> 0x01
            }
            return byteArrayOf(0x52, effectCode.toByte(), r, g, b, brightness.coerceIn(0, 100).toByte())
        }
    }

    /** 通用协议：标准 Nordic-UART 风格 UUID，供支持公开文档的第三方散热器接入 */
    class GenericCoolerProtocol : CoolerProtocol {
        override val id = "generic"
        override val displayName = "通用散热器（透传）"
        override val writeCharacteristicUuid: UUID =
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9f")
        override val notifyCharacteristicUuid: UUID? =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9f")

        override fun matches(deviceName: String): Boolean = false

        override fun encodePower(level: Int): ByteArray =
            "PWR:${level.coerceIn(0, 100)}\n".toByteArray(Charsets.US_ASCII)

        override fun encodeRgb(effect: String, colorRgb: Int, brightness: Int): ByteArray =
            "RGB:${effect}:${colorRgb and 0xFFFFFF}:${brightness.coerceIn(0, 100)}\n"
                .toByteArray(Charsets.US_ASCII)
    }

    data class DiscoveredCooler(val name: String, val address: String, val protocol: CoolerProtocol?)

    interface Listener {
        fun onScanResult(devices: List<DiscoveredCooler>) {}
        fun onConnected(protocol: CoolerProtocol) {}
        fun onDisconnected() {}
        fun onTemperature(celsius: Double) {}
        fun onError(message: String) {}
    }

    var listener: Listener? = null

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val protocols = listOf(
        RedMagicCoolerProtocol(),
        BlackSharkCoolerProtocol(),
        GenericCoolerProtocol()
    )
    private val discovered = LinkedHashMap<String, DiscoveredCooler>()
    private var gatt: BluetoothGatt? = null
    private var activeProtocol: CoolerProtocol? = null
    private var scanning = false

    fun isBluetoothEnabled(): Boolean =
        bluetoothManager?.adapter?.isEnabled == true

    /** 按名称匹配协议（纯逻辑，可测） */
    fun findProtocolFor(deviceName: String): CoolerProtocol? =
        protocols.firstOrNull { it.matches(deviceName) }

    fun startScan() {
        val adapter = bluetoothManager?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            listener?.onError("蓝牙未开启")
            return
        }
        if (scanning) return
        discovered.clear()
        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(null, settings, scanCallback) }
            .onFailure { scanning = false; listener?.onError("扫描启动失败：${it.message}") }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (name.isBlank()) return
            // 只收录疑似散热器或所有设备中已匹配协议的；无协议的设备也收录供手动连接
            val known = discovered[result.device.address]
            if (known != null) return
            discovered[result.device.address] =
                DiscoveredCooler(name, result.device.address, findProtocolFor(name))
            listener?.onScanResult(discovered.values.toList())
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener?.onError("扫描失败，错误码 $errorCode")
        }
    }

    /** 连接设备；连接成功后自动发现服务并订阅温度通知 */
    fun connect(device: BluetoothDevice, protocol: CoolerProtocol) {
        disconnect()
        activeProtocol = protocol
        runCatching { device.connectGatt(context, false, gattCallback) }
            .onSuccess { gatt = it }
            .onFailure { listener?.onError("连接失败：${it.message}") }
    }

    fun disconnect() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        probeNotifyChars.clear()
        writableChars = emptyList()
        synchronized(opQueue) { opQueue.clear(); opBusy = false }
    }

    fun isConnected(): Boolean = gatt != null

    // ==================== BLE 顺序操作队列 ====================
    // GATT 是单操作协议: 并发 writeDescriptor/writeCharacteristic 只有第一个生效, 其余静默失败。
    // 之前 onServicesDiscovered 里循环写全部描述符、写控制帧不校验回调, 是"已连接但控制没效果"
    // 的直接根因。所有 GATT 操作排队执行, 收到对应回调(或超时兜底)再放行下一个。

    private class Op(val seq: Long, val action: (Long) -> Unit)

    private val opQueue = ArrayDeque<Op>()
    @Volatile private var opBusy = false
    @Volatile private var activeOpSeq = -1L
    private var opSeqCounter = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun enqueueOp(action: (Long) -> Unit) {
        synchronized(opQueue) { opQueue.addLast(Op(++opSeqCounter, action)) }
        drainOps()
    }

    private fun drainOps() {
        val op = synchronized(opQueue) {
            if (opBusy) return
            val removed = opQueue.removeFirstOrNull() ?: return
            opBusy = true
            activeOpSeq = removed.seq
            removed
        }
        runCatching { op.action(op.seq) }.onFailure {
            Log.w("CoolerManager", "BLE op failed", it)
            finishOp(op.seq)
        }
    }

    private fun finishOp(seq: Long) {
        synchronized(opQueue) {
            if (!opBusy || activeOpSeq != seq) return
            opBusy = false
        }
        drainOps()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    g.close()
                    this@CoolerManager.gatt = null
                    listener?.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener?.onError("服务发现失败，错误码 $status")
                return
            }
            val protocol = activeProtocol ?: return
            val allChars = g.services.flatMap { it.characteristics }
            writableChars = allChars.filter {
                (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
            }
            if (writableChars.isEmpty()) {
                listener?.onError("未找到可写特征")
                return
            }
            // 协议预置 UUID 优先; 写入失败时自动换下一个可写特征重试
            writeCharacteristic = allChars.firstOrNull { it.uuid == protocol.writeCharacteristicUuid }
                ?: writableChars.first()
            val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            probeNotifyChars.clear()
            // 1) MTU 协商(灯效等长帧需要, 默认 23 字节 ATT MTU 只能装 20 字节载荷)
            enqueueOp { seq ->
                runCatching { g.requestMtu(185) }
                mainHandler.postDelayed({ finishOp(seq) }, 700)
            }
            // 2) 逐个订阅可通知特征并写 CCCD, 每个等描述符回调/兜底后再下一个
            allChars.forEach { c ->
                if ((c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) return@forEach
                enqueueOp { seq ->
                    var queued = false
                    runCatching {
                        g.setCharacteristicNotification(c, true)
                        c.getDescriptor(cccd)?.let { d ->
                            @Suppress("DEPRECATION")
                            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            queued = g.writeDescriptor(d)
                        }
                    }
                    if (c.uuid != protocol.notifyCharacteristicUuid) probeNotifyChars += c.uuid
                    if (!queued) mainHandler.postDelayed({ finishOp(seq) }, 200)
                }
            }
            // 3) 订阅完成后报一次就绪, 用户能在协议日志里确认通道就绪
            enqueueOp { seq ->
                rawFrameListener?.invoke("# 通道就绪: 可写 ${writableChars.size} 个, 已订阅全部可通知特征")
                mainHandler.postDelayed({ finishOp(seq) }, 100)
            }
            listener?.onConnected(protocol)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            finishOp(activeOpSeq)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeResultListener?.invoke(status)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotify(characteristic, value)
        }

        // API < 33 系统回调旧签名 (K70=API34 走上面新签名, 其他机型走这里)
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleNotify(characteristic, characteristic.value)
        }
    }

    private fun handleNotify(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (value.isNotEmpty()) {
            rawFrameListener?.invoke("← ${characteristic.uuid.toString().take(8)}: ${value.toHexString()}")
        }
        activeProtocol?.parseTemperature(value)?.let { listener?.onTemperature(it) }
    }

    private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }

    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var writableChars: List<BluetoothGattCharacteristic> = emptyList()
    @Volatile private var writeResultListener: ((Int) -> Unit)? = null

    /** 连接后订阅到的非协议预置通知特征（用于协议试探时展示设备上报的原始帧） */
    var probeNotifyChars: MutableList<UUID> = mutableListOf()

    /** 原始 BLE 帧回调（协议日志/未知固件试探用），主线程外回调 */
    var rawFrameListener: ((String) -> Unit)? = null

    /** 发送功率帧（0-100） */
    fun sendPower(level: Int): Boolean = writeFrame { it.encodePower(level) }

    /** 发送灯效帧 */
    fun sendRgb(effect: String, colorRgb: Int, brightness: Int): Boolean =
        writeFrame { it.encodeRgb(effect, colorRgb, brightness) }

    /** 发送原始帧（未知型号散热器的协议试探） */
    fun sendRaw(frame: ByteArray): Boolean = enqueueWrite(frame, "RAW")

    private fun writeFrame(encoder: (CoolerProtocol) -> ByteArray): Boolean {
        val protocol = activeProtocol ?: run {
            listener?.onError("散热器未连接")
            return false
        }
        return enqueueWrite(encoder(protocol), protocol.id)
    }

    /**
     * 排队写入控制帧。协议预置特征写入失败时自动逐个尝试其余可写特征，
     * 全部失败则如实回报 —— 不再静默丢弃。
     */
    private fun enqueueWrite(frame: ByteArray, tag: String): Boolean {
        val g = gatt ?: run {
            listener?.onError("散热器未连接")
            return false
        }
        val targets = (listOfNotNull(writeCharacteristic) + writableChars).distinctBy { it.uuid }
        if (targets.isEmpty()) {
            listener?.onError("写入通道未就绪")
            return false
        }
        enqueueOp { seq ->
            var idx = 0
            fun attempt() {
                val c = targets.getOrNull(idx)
                if (c == null) {
                    writeResultListener = null
                    rawFrameListener?.invoke("→ $tag ${frame.toHexString()} 全部特征写入失败")
                    listener?.onError("控制帧写入失败：设备未接受任何可写特征")
                    finishOp(seq)
                    return
                }
                @Suppress("DEPRECATION")
                c.value = frame
                c.writeType = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                val accepted = @Suppress("DEPRECATION") g.writeCharacteristic(c)
                if (accepted) {
                    writeResultListener = { status ->
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            writeResultListener = null
                            rawFrameListener?.invoke("→ $tag ${frame.toHexString()} @${c.uuid.toString().take(8)}")
                            finishOp(seq)
                        } else {
                            writeResultListener = null
                            idx++
                            attempt()
                        }
                    }
                } else {
                    idx++
                    attempt()
                }
            }
            attempt()
            // GATT 回调丢失兜底, 防止单次写入卡死整个队列
            mainHandler.postDelayed({
                if (opBusy && activeOpSeq == seq) {
                    writeResultListener = null
                    finishOp(seq)
                }
            }, 1500)
        }
        return true
    }

    /** 读取手机温度作为参考（root：thermal 区区临时下限值） */
    fun queryPhoneTemperatureViaRoot(suSession: SuSession): Double? {
        if (!suSession.isSessionOpen()) return null
        val out = suSession.execute(
            "cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null",
            5
        ).output.trim()
        return parseMilliCelsius(out)
    }

    companion object {
        /** thermal_zone0 输出毫摄氏度（如 45123 -> 45.1），纯逻辑可测 */
        fun parseMilliCelsius(raw: String): Double? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val value = text.toDoubleOrNull() ?: return null
            if (value < -40000 || value > 150000) return null
            return value / 1000.0
        }
    }
}
