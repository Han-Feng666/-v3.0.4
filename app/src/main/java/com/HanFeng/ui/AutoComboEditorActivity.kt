package com.HanFeng.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.HanFeng.R
import com.HanFeng.data.AutoComboRepository

/**
 * 自动连招脚本编辑页：逐步编辑坐标/持续时长/间隔、整体变速。
 */
class AutoComboEditorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_combo_editor)
        ensureAppBackground()

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        val scriptId = intent.getStringExtra("scriptId")
        val script = scriptId?.let { AutoComboRepository.getScript(this, it) }
        findViewById<TextView>(R.id.tvEditorInfo).text =
            script?.let { "编辑脚本：${it.name}（${it.steps.size} 步）" } ?: "脚本未找到"
    }
}