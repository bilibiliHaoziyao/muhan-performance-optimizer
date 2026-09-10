package com.muhan.socbatteryinfo.util

/**
 * 统一的 Shell 命令执行：按 普通 sh → Shizuku（ADB Shell）→ Root su 依次尝试。
 * 用于执行 top / dumpsys cpuinfo 等需要 shell 的命令。
 */
object ShellExec {

    fun exec(command: String): String? {
        execSh(command)?.let { return it }
        ShizukuShell.exec(command)?.let { return it }
        RootShell.exec(command)?.let { return it }
        return null
    }

    private fun execSh(command: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = process.inputStream.bufferedReader().readText()
            process.errorStream.close()
            process.waitFor()
            out.trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}