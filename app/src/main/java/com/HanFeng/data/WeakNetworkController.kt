package com.HanFeng.data

import android.content.Context
import android.content.Intent
import com.HanFeng.service.AdBlockVpnService
import com.HanFeng.service.WeakNetworkEngine

/**
 * 弱网模拟状态中枢：悬浮球、设置页、音量键统一通过本 controller 开关与热更新。
 * 全局模式（未指定目标 App）直接热更新整形引擎；单 App 模式需要重建 VPN 会话以用
 * addAllowedApplication 限定目标 App 的流量进入隧道（届时非目标 App 绕过 VPN）。
 */
object WeakNetworkController {

    const val ACTION_WEAK_NET_STATE_CHANGED = "com.HanFeng.action.WEAK_NET_STATE_CHANGED"

    fun isEnabled(context: Context): Boolean = FeatureSettingsRepository.isWeakNetEnabled(context)

    fun hasTargetPackage(context: Context): Boolean =
        !FeatureSettingsRepository.getWeakNetTargetPackage(context).isNullOrBlank()

    /** 切换弱网开/关。返回切换后的状态。 */
    fun toggle(context: Context): Boolean {
        val next = !isEnabled(context)
        setEnabled(context, next)
        return next
    }

    /** 显式设置弱网开关（悬浮球/设置页共用）。开启且存在目标 App 时需要重建会话限定流量。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        FeatureSettingsRepository.setWeakNetEnabled(context, enabled)
        applyRuntimeChange(context, vpnReload = enabled && hasTargetPackage(context))
    }

    /** 设置页修改参数/目标 App 后调用：热更新整形参数，目标 App 变化（含清除）时重建会话。 */
    fun applyConfigChange(context: Context, targetChanged: Boolean = false) {
        applyRuntimeChange(context, vpnReload = isEnabled(context) && targetChanged)
    }

    private fun applyRuntimeChange(context: Context, vpnReload: Boolean) {
        if (isEnabled(context)) {
            WeakNetworkEngine.refresh(context)
        } else {
            WeakNetworkEngine.deactivate()
        }
        if (vpnReload) requestVpnReload(context)
        runCatching {
            context.sendBroadcast(Intent(ACTION_WEAK_NET_STATE_CHANGED))
        }
    }

    /** 在 VPN 开启前（如 setWeakNetEnabled 由外部调用）也能保持引擎与存储一致。 */
    fun syncEngine(context: Context) {
        if (isEnabled(context)) WeakNetworkEngine.refresh(context) else WeakNetworkEngine.deactivate()
    }

    private fun requestVpnReload(context: Context) {
        val running = runCatching { com.HanFeng.core.network.NetworkKernel.isRunning() }.getOrDefault(false)
        val action = if (running) AdBlockVpnService.ACTION_RELOAD else AdBlockVpnService.ACTION_START
        runCatching {
            context.startService(
                Intent(context, AdBlockVpnService::class.java).setAction(action)
            )
        }
    }
}