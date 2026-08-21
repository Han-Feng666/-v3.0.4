package com.HanFeng.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.HanFeng.R
import com.HanFeng.data.AutoComboRepository
import com.HanFeng.model.ComboScript
import com.HanFeng.service.AutoComboController
import com.HanFeng.service.FloatingBallService
import java.util.UUID

/**
 * 自动连招脚本列表页：展示所有脚本的名称/步骤数/最近使用，
 * 支持选择/重命名/删除/新建与 JSON 导入导出。
 */
class AutoComboActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_combo)
        ensureAppBackground()

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnCreateScript).setOnClickListener {
            val script = AutoComboRepository.saveScript(
                this, ComboScript(
                    id = UUID.randomUUID().toString(),
                    name = "新脚本_${java.text.SimpleDateFormat("MMdd_HHmm", java.util.Locale.getDefault()).format(java.util.Date())}",
                    steps = emptyList(),
                    speed = 1f,
                    playMode = com.HanFeng.model.ComboPlayMode.SINGLE
                )
            )
            if (!FloatingBallService.hasOverlayPermission(this)) {
                showOverlayPermissionDialog()
                return@setOnClickListener
            }
            AutoComboController.applyState(this, true)
            startActivity(
                Intent(this, AutoComboEditorActivity::class.java)
                    .putExtra("scriptId", script.id)
            )
            refreshList()
        }

        findViewById<SwitchCompat>(R.id.switchFloatingBall).apply {
            isChecked = AutoComboController.isEnabled(this@AutoComboActivity)
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && !FloatingBallService.hasOverlayPermission(this@AutoComboActivity)) {
                    showOverlayPermissionDialog()
                    setChecked(false)
                    return@setOnCheckedChangeListener
                }
                AutoComboController.applyState(this@AutoComboActivity, isChecked)
            }
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        findViewById<SwitchCompat>(R.id.switchFloatingBall).isChecked = AutoComboController.isEnabled(this)
    }

    private fun showOverlayPermissionDialog() {
        StableDialog.builder(this)
            .setTitle("需要悬浮窗权限")
            .setMessage("自动连招需要悬浮窗权限才能显示悬浮球和操作面板。\n\n请在设置中开启悬浮窗权限。")
            .setPositiveButton("去设置") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshList() {
        val scripts = AutoComboRepository.listScripts(this)
        val tv = findViewById<TextView>(R.id.tvScriptList)
        if (scripts.isEmpty()) {
            tv.text = "暂无脚本\n点击下方按钮创建新脚本"
            return
        }
        tv.text = scripts.joinToString("\n\n") { it.displaySummary() }
    }

    private fun ComboScript.displaySummary(): String =
        "$name（${steps.size} 步）\n模式=${playMode.name} 变速=${speed}x"
}