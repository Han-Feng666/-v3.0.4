package com.HanFeng.data

import android.content.Context
import com.HanFeng.adblocker.shizuku.SuSession

data class SocProfile(
    val name: String,
    val platform: String,
    val vendor: String,
    val fmaxCap: String = "auto",
    val thermalGuard: String = "49500",
    val hispdLoad: String = "90",
    val adaptive: String = "0",
    val disableMigt: String = "1",
    val schedFmaxCap: String = "auto"
)

object SocDatabase {

    private val socProfiles = listOf(
        // ===== Snapdragon 8 系列 =====
        SocProfile("骁龙 8 Elite", "sun", "QCOM",
            fmaxCap = "2800000", thermalGuard = "52000", hispdLoad = "85"),
        SocProfile("骁龙 8 Gen 3", "pineapple", "QCOM",
            fmaxCap = "2800000", thermalGuard = "52000", hispdLoad = "85"),
        SocProfile("骁龙 8s Gen 3", "pineapple", "QCOM",
            fmaxCap = "2600000", thermalGuard = "50000", hispdLoad = "88"),
        SocProfile("骁龙 8 Gen 2", "kalama", "QCOM",
            fmaxCap = "2800000", thermalGuard = "51000", hispdLoad = "85"),
        SocProfile("骁龙 8+ Gen 1", "sm8475", "QCOM",
            fmaxCap = "2800000", thermalGuard = "50000", hispdLoad = "85"),
        SocProfile("骁龙 8 Gen 1", "taro", "QCOM",
            fmaxCap = "2700000", thermalGuard = "49000", hispdLoad = "85"),
        SocProfile("骁龙 888", "lahaina", "QCOM",
            fmaxCap = "2600000", thermalGuard = "48000", hispdLoad = "85"),
        SocProfile("骁龙 888+", "lahainap", "QCOM",
            fmaxCap = "2700000", thermalGuard = "48000", hispdLoad = "85"),
        SocProfile("骁龙 870", "kona", "QCOM",
            fmaxCap = "2800000", thermalGuard = "52000", hispdLoad = "90"),
        SocProfile("骁龙 865", "kona", "QCOM",
            fmaxCap = "2700000", thermalGuard = "51000", hispdLoad = "90"),

        // ===== Snapdragon 7 系列 =====
        SocProfile("骁龙 7+ Gen 3", "pitti", "QCOM",
            fmaxCap = "2600000", thermalGuard = "50000", hispdLoad = "85"),
        SocProfile("骁龙 7 Gen 4", "volcano", "QCOM",
            fmaxCap = "2500000", thermalGuard = "50000", hispdLoad = "88"),
        SocProfile("骁龙 7 Gen 3", "crow", "QCOM",
            fmaxCap = "2400000", thermalGuard = "49000", hispdLoad = "88"),
        SocProfile("骁龙 7+ Gen 2", "sm7475", "QCOM",
            fmaxCap = "2600000", thermalGuard = "50000", hispdLoad = "85"),
        SocProfile("骁龙 7s Gen 3", "sm7635", "QCOM",
            fmaxCap = "2400000", thermalGuard = "48000", hispdLoad = "90"),
        SocProfile("骁龙 7s Gen 2", "sm7435", "QCOM",
            fmaxCap = "2200000", thermalGuard = "48000", hispdLoad = "90"),
        SocProfile("骁龙 7 Gen 1", "waipio", "QCOM",
            fmaxCap = "2400000", thermalGuard = "48000", hispdLoad = "88"),

        // ===== Dimensity 9000 系列 =====
        SocProfile("天玑 9400", "mt6991", "MTK",
            fmaxCap = "2800000", thermalGuard = "51000", hispdLoad = "85"),
        SocProfile("天玑 9300+", "mt6989", "MTK",
            fmaxCap = "2850000", thermalGuard = "51000", hispdLoad = "83"),
        SocProfile("天玑 9300", "mt6989", "MTK",
            fmaxCap = "2800000", thermalGuard = "51000", hispdLoad = "85"),
        SocProfile("天玑 9200+", "mt6985", "MTK",
            fmaxCap = "2800000", thermalGuard = "50000", hispdLoad = "85"),
        SocProfile("天玑 9200", "mt6985", "MTK",
            fmaxCap = "2700000", thermalGuard = "50000", hispdLoad = "85"),
        SocProfile("天玑 9000", "mt6983", "MTK",
            fmaxCap = "2700000", thermalGuard = "50000", hispdLoad = "85"),

        // ===== Dimensity 8000 系列 =====
        SocProfile("天玑 8400", "mt6899", "MTK",
            fmaxCap = "2700000", thermalGuard = "50000", hispdLoad = "85"),
        SocProfile("天玑 8350", "mt6897", "MTK",
            fmaxCap = "2600000", thermalGuard = "49000", hispdLoad = "88"),
        SocProfile("天玑 8300", "mt6897", "MTK",
            fmaxCap = "2600000", thermalGuard = "49000", hispdLoad = "88"),
        SocProfile("天玑 8250", "mt6896", "MTK",
            fmaxCap = "2600000", thermalGuard = "49000", hispdLoad = "88"),
        SocProfile("天玑 8200", "mt6896", "MTK",
            fmaxCap = "2600000", thermalGuard = "49000", hispdLoad = "88"),
        SocProfile("天玑 8100", "mt6895", "MTK",
            fmaxCap = "2500000", thermalGuard = "48000", hispdLoad = "88"),
        SocProfile("天玑 8000", "mt6895", "MTK",
            fmaxCap = "2500000", thermalGuard = "48000", hispdLoad = "88"),

        // ===== Dimensity 7000 系列 =====
        SocProfile("天玑 7400", "mt6886", "MTK",
            fmaxCap = "2400000", thermalGuard = "48000", hispdLoad = "90"),
        SocProfile("天玑 7350", "mt6886", "MTK",
            fmaxCap = "2400000", thermalGuard = "48000", hispdLoad = "90"),
        SocProfile("天玑 7300", "mt6879", "MTK",
            fmaxCap = "2300000", thermalGuard = "48000", hispdLoad = "90"),
        SocProfile("天玑 7200", "mt6886", "MTK",
            fmaxCap = "2400000", thermalGuard = "48000", hispdLoad = "88"),

        // ===== Dimensity 6000 系列 =====
        SocProfile("天玑 6400", "mt6885", "MTK",
            fmaxCap = "2200000", thermalGuard = "47000", hispdLoad = "90"),
        SocProfile("天玑 6300", "mt6885", "MTK",
            fmaxCap = "2200000", thermalGuard = "47000", hispdLoad = "90"),

        // ===== Dimensity 1000 系列 =====
        SocProfile("天玑 1200", "mt6893", "MTK",
            fmaxCap = "2500000", thermalGuard = "48000", hispdLoad = "88"),
        SocProfile("天玑 1100", "mt6891", "MTK",
            fmaxCap = "2500000", thermalGuard = "48000", hispdLoad = "88"),
        SocProfile("天玑 1000+", "mt6889", "MTK",
            fmaxCap = "2400000", thermalGuard = "47000", hispdLoad = "88"),
    )

    private val platformToProfile: Map<String, List<SocProfile>> by lazy {
        socProfiles.groupBy { it.platform }
    }

    fun detectSoc(context: Context): SocProfile {
        val session = SuSession.getInstance()
        if (!session.isSessionOpen()) {
            return SocProfile("未知处理器", "unknown", "QCOM")
        }
        val platform = session.execute("getprop ro.board.platform 2>/dev/null || echo unknown", 5).output.trim()
        if (platform == "unknown" || platform.isBlank()) {
            return SocProfile("未知处理器", "unknown", "QCOM")
        }
        val candidates = platformToProfile[platform]
        if (candidates != null) {
            return candidates.first()
        }
        val vendor = if (platform.startsWith("mt") || platform.startsWith("MT")) "MTK" else "QCOM"
        return SocProfile("$platform (未收录)", platform, vendor)
    }

    fun detectSocName(context: Context): String {
        return detectSoc(context).name
    }

    fun detectVendor(context: Context): String {
        return detectSoc(context).vendor
    }
}