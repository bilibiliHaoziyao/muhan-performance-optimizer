package com.muhan.socbatteryinfo.util

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shizuku（ADB Shell 权限）封装。
 * 通过 Shizuku 获取 shell 身份执行命令，用于普通权限与 Root 都无法获取的数据。
 *
 * stdout 在独立线程中流式读取，避免输出较大时管道缓冲写满导致远端进程阻塞、
 * 被误判为超时。
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

    /** 通过 Shizuku 的 ADB Shell 身份执行命令，未授权/失败/超时返回 null */
    fun exec(command: String): String? {
        if (!isGranted()) return null
        return try {
            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            // stderr 合并到 stdout；输出异步读取，避免管道缓冲写满阻塞远端进程
            val process = service.newProcess(arrayOf("sh", "-c", "$command 2>&1"), null, null)
            val output = AtomicReference("")
            val readDone = CountDownLatch(1)
            Thread {
                try {
                    output.set(
                        ParcelFileDescriptor.AutoCloseInputStream(process.getInputStream())
                            .bufferedReader()
                            .readText()
                    )
                } catch (_: Exception) {
                } finally {
                    readDone.countDown()
                }
            }.apply { isDaemon = true }.start()

            if (!waitForTimeout(process, ShellExec.DEFAULT_TIMEOUT_MS)) {
                destroyProcess(process)
                readDone.await(500, TimeUnit.MILLISECONDS)
                return null
            }
            readDone.await(1000, TimeUnit.MILLISECONDS)
            try {
                process.getErrorStream().close()
            } catch (_: Exception) {
            }
            output.get().trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /** 等待 Shizuku 远端进程结束（IRemoteProcess 仅提供阻塞 waitFor()，这里用 exitValue 轮询实现超时） */
    private fun waitForTimeout(process: IRemoteProcess, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive(process)) return true
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return !isAlive(process)
            }
        }
        return !isAlive(process)
    }

    private fun isAlive(process: IRemoteProcess): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    } catch (_: Exception) {
        false
    }

    private fun destroyProcess(process: IRemoteProcess) {
        try {
            process.destroy()
        } catch (_: Exception) {
        }
    }
}
