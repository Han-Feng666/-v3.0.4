package com.HanFeng.data

import android.content.Context
import android.os.Build
import android.os.IBinder
import com.HanFeng.shizuku.ConnectionOwnerUserService
import com.HanFeng.shizuku.IConnectionOwnerService
import rikka.shizuku.Shizuku

object ShizukuConnectionOwnerRepository : ShizukuServiceBinder<IConnectionOwnerService>() {
    override val serviceLabel: String = "Shizuku connection owner"

    override fun isReady(context: Context): Boolean {
        return AppSettingsRepository.isShizukuEnabled(context) &&
            ShizukuRepository.canAttemptUserService(context) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    override fun createUserServiceArgs(context: Context): Shizuku.UserServiceArgs {
        return Shizuku.UserServiceArgs(
            android.content.ComponentName(context.packageName, ConnectionOwnerUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("conn-owner")
            .debuggable(false)
            .version(1)
    }

    override fun asService(binder: IBinder?): IConnectionOwnerService? =
        IConnectionOwnerService.Stub.asInterface(binder)

    fun getConnectionOwnerUid(
        context: Context,
        protocol: Int,
        localHost: String,
        localPort: Int,
        remoteHost: String,
        remotePort: Int
    ): Int {
        val connectedService = getService(context) ?: return -1
        return runCatching {
            connectedService.getConnectionOwnerUid(protocol, localHost, localPort, remoteHost, remotePort)
        }.getOrElse {
            invalidateService()
            -1
        }
    }

    fun getConnectionOwnerUidIfBound(
        protocol: Int,
        localHost: String,
        localPort: Int,
        remoteHost: String,
        remotePort: Int
    ): Int {
        val connectedService = liveService() ?: return -1
        return runCatching {
            connectedService.getConnectionOwnerUid(protocol, localHost, localPort, remoteHost, remotePort)
        }.getOrElse {
            invalidateService()
            -1
        }
    }
}
