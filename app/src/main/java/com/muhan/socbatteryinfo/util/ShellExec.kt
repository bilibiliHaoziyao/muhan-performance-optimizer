package com.muhan.socbatteryinfo.util

import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 统一的 Shell 命令执行：按 普通 sh → Shizuku（ADB Shell）→ Root su 依次尝试。
 * 用于执行 top / dumpsys cpuinfo 等需要 shell 的命令。
 *
 * 所有执行均带超时（默认 4 秒）。输出在独立线程中持续读取，避免 stdout/stderr
 * 管道缓冲写满导致子进程阻塞、最终误判为超时。
 */
object ShellExec {

    const val DEFAULT_TIMEOUT_MS = 4000L

    /** 命令执行结果 */
    data class ShellResult(
        val exitCode: Int?,
        val stdout: String,
        val timedOut: Boolean
    )

    fun exec(command: String): String? {
        execSh(command)?.let { return it }
        ShizukuShell.exec(command)?.let { return it }
        RootShell.exec(command)?.let { return it }
        return null
    }

    /** 执行 sh -c 命令（stderr 合并到 stdout），最多等待 [timeoutMs]，超时销毁进程返回 null */
    fun execSh(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? {
        val result = execShResult(command, timeoutMs)
        if (result.timedOut) return null
        return result.stdout.trim().ifEmpty { null }
    }

    /**
     * 执行 sh 命令并返回完整结果。stdout 在独立线程中流式读取，
     * 防止输出较大时管道缓冲写满导致子进程阻塞。
     */
    fun execShResult(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult {
        var process: Process? = null
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$command 2>&1"))
            process = p
            val output = AtomicReference("")
            val readDone = CountDownLatch(1)
            Thread {
                try {
                    output.set(p.inputStream.bufferedReader().readText())
                } catch (_: Exception) {
                } finally {
                    readDone.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }

            if (!waitForTimeout(p, timeoutMs)) {
                destroyForcibly(p)
                readDone.await(500, TimeUnit.MILLISECONDS)
                return ShellResult(null, output.get(), timedOut = true)
            }
            readDone.await(1000, TimeUnit.MILLISECONDS)
            ShellResult(p.exitValue(), output.get(), timedOut = false)
        } catch (_: Exception) {
            process?.let { destroyForcibly(it) }
            ShellResult(null, "", timedOut = false)
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
