package com.HanFeng.ui

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AlertDialog
import com.HanFeng.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * StableDialog：统一的弹窗工厂，保证所有调用入口圆角/阴影/按钮配色/入场动画一致。
 *
 * 所有弹窗窗口统一应用"液态玻璃毛玻璃"效果：Android 12+ 开启 FLAG_BLUR_BEHIND 真实高斯模糊，
 * 低版本靠 bg_dialog_window 的高不透明度渐变兜底，保证弹窗文字与背后界面内容不重叠。
 */
object StableDialog {

    // 毛玻璃模糊半径：过小（如 24）时透过弹窗仍能辨认背后文字轮廓
    private const val DIALOG_BLUR_RADIUS_PX = 48

    fun builder(context: Context): AlertDialog.Builder {
        return AlertDialog.Builder(context, R.style.ThemeOverlay_HanFeng_AppCompatDialog)
    }

    /**
     * 返回 MaterialAlertDialogBuilder，用于 setIcon / setMultiChoiceItems 等场景。
     * show 时请配套使用 showMaterialSafely 以获得毛玻璃模糊与统一入场动画。
     */
    fun materialBuilder(context: Context): MaterialAlertDialogBuilder {
        return MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_HanFeng_AppCompatDialog)
    }

    /** 对已创建的 AlertDialog 统一应用毛玻璃窗口效果 */
    fun applyLiquidGlassWindow(dialog: AlertDialog): AlertDialog {
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(R.drawable.bg_dialog_window)
            w.setWindowAnimations(R.style.HanFengDialogAnimation)
            // Android 12+ 对弹窗背后的内容做真实高斯模糊，避免半透明背景与界面字体重叠。
            // 部分省电模式/开发者选项会关闭模糊合成，系统自动回退到不透明底色，无需额外处理
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.attributes = w.attributes.apply { blurBehindRadius = DIALOG_BLUR_RADIUS_PX }
                // dim 与模糊叠加：即使系统拒绝渲染模糊，DIM 也能压暗背后文字保证可读
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.attributes = w.attributes.apply { dimAmount = 0.45f }
            } else {
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.attributes = w.attributes.apply { dimAmount = 0.45f }
            }
        }
        return dialog
    }
}

private fun AlertDialog.applyEnhancedWindow(): AlertDialog {
    return StableDialog.applyLiquidGlassWindow(this)
}

private fun AlertDialog.playEnterAnimation(): AlertDialog {
    runCatching {
        val cardView = findViewById<View>(androidx.appcompat.R.id.parentPanel)
            ?: findViewById<View>(android.R.id.content)
        cardView?.let {
            val anim: Animation = AnimationUtils.loadAnimation(it.context, R.anim.dialog_enter)
            it.startAnimation(anim)
        }
    }
    return this
}

fun AlertDialog.Builder.showSafely(context: Context, logLabel: String): AlertDialog? {
    return runCatching {
        create().applyEnhancedWindow().let { dlg ->
            dlg.show()
            dlg.playEnterAnimation()
            dlg
        }
    }
        .onFailure { Log.w("StableDialog", "$logLabel: ${it.message}", it) }
        .getOrNull()
}

fun AlertDialog.showSafely(context: Context, logLabel: String): AlertDialog? {
    return runCatching {
        show()
        applyEnhancedWindow()
        playEnterAnimation()
        this
    }.onFailure { Log.w("StableDialog", "$logLabel: ${it.message}", it) }.getOrNull()
}

/** MaterialAlertDialogBuilder 统一 show 入口：毛玻璃模糊 + 入场动画，异常时静默降级 */
fun MaterialAlertDialogBuilder.showMaterialSafely(context: Context, logLabel: String): AlertDialog? {
    return runCatching {
        val dlg = show()
        StableDialog.applyLiquidGlassWindow(dlg)
        dlg.playEnterAnimation()
        dlg
    }.onFailure { Log.w("StableDialog", "$logLabel: ${it.message}", it) }.getOrNull()
}
