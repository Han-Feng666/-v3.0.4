package com.HanFeng.data

/**
 * Shizuku server 授权 flags 的纯逻辑解码。
 *
 * 与 fork 服务端 rikka.shizuku.server ConfigManager 的位定义一致：
 *  - FLAG_ALLOWED = 1 shl 1 (2)
 *  - FLAG_DENIED  = 1 shl 2 (4)
 *  - MASK_PERMISSION = ALLOWED or DENIED (6)
 *
 * 授权状态语义（对齐官方 AuthorizationManager）：
 *  - isAllowed  = 已授权，应用可使用 Shizuku 服务
 *  - isDenied   = 已显式拒绝，该应用下次请求会直接被拒（server 端撤销授权即写入此状态）
 *  - 两者都为 false = 从未授权过（应用请求时会弹出授权确认框）
 */
object ShizukuFlags {
    const val FLAG_ALLOWED = 1 shl 1
    const val FLAG_DENIED = 1 shl 2
    const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

    fun isAllowed(flags: Int): Boolean = (flags and FLAG_ALLOWED) == FLAG_ALLOWED

    fun isDenied(flags: Int): Boolean = (flags and FLAG_DENIED) == FLAG_DENIED
}
