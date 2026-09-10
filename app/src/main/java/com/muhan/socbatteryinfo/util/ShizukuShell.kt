package com.muhan.socbatteryinfo.util

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * Shizuku（ADB Shell 权限）封装。
 * 通过 Shizuku 获取 shell 身份执行命令，用于普通权限与 Root 都无法获取的数据。
 */
object ShizukuShell {

    /** Shizuku 服务是否正在运行（binder 存活） */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        false
    }

    /** 是否已获得 Shizuku 授权 */
    fun isGranted(): Boolean = try {
        isAvailable() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    /** 请求 Shizuku 授权（需在 binder 存活、且未授权时调用） */
    fun requestPermission(requestCode: Int) {
        try {
            if (isAvailable() && !isGranted()) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (_: Exception) {
        }
    }

    /** 通过 Shizuku 的 ADB Shell 身份执行命令，未授权/失败返回 null */
    fun exec(command: String): String? {
        if (!isGranted()) return null
        return try {
            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            val process = service.newProcess(arrayOf("sh", "-c", command), null, null)
            val out = ParcelFileDescriptor.AutoCloseInputStream(process.getInputStream())
                .bufferedReader()
                .readText()
            try {
                process.getErrorStream().close()
            } catch (_: Exception) {
            }
            try {
                process.waitFor()
            } catch (_: Exception) {
            }
            out.trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}