package com.muhan.socbatteryinfo.util

import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * 统一的 Shell 命令执行：按 普通 sh → Shizuku（ADB Shell）→ Root su 依次尝试。
 * 用于执行 top / dumpsys cpuinfo 等需要 shell 的命令。
 *
 * 所有执行均带超时（默认 4 秒），stderr 通过 `2>&1` 合并到 stdout，
 * 避免子进程 stderr 写满管道缓冲导致死锁、以及授权弹窗不响应导致永久挂起。
 */
object ShellExec {

    const val DEFAULT_TIMEOUT_MS = 4000L

    fun exec(command: String): String? {
        execSh(command)?.let { return it }
        ShizukuShell.exec(command)?.let { return it }
        RootShell.exec(command)?.let { return it }
        return null
    }

    /** 执行 sh -c 命令（stderr 合并到 stdout），最多等待 [timeoutMs]，超时销毁进程返回 null */
    fun execSh(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$command 2>&1"))
            if (!waitForTimeout(process, timeoutMs)) {
                destroyForcibly(process)
                return null
            }
            process.inputStream.bufferedReader().readText().trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /** 等待进程结束，兼容 API 26 以下没有 waitFor(timeout) 的情况 */
    fun waitForTimeout(process: Process, timeoutMs: Long): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    if (!isAliveCompat(process)) return true
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        return !isAliveCompat(process)
                    }
                }
                !isAliveCompat(process)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 兼容 API 26 以下的 isAlive() */
    private fun isAliveCompat(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    } catch (_: Exception) {
        false
    }

    /** 兼容 API 26 以下的 destroyForcibly() */
    fun destroyForcibly(process: Process) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.destroyForcibly()
            } else {
                process.destroy()
            }
        } catch (_: Exception) {
            try {
                process.destroy()
            } catch (_: Exception) {
            }
        }
    }
}
