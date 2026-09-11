package com.muhan.socbatteryinfo.util

import java.io.DataOutputStream
import java.io.File

/** Root 权限获取与命令执行（阻塞调用，应在后台线程执行） */
object RootShell {

    @Volatile
    var granted: Boolean = false
        private set

    /** 是否存在 su 可执行文件 */
    private fun hasSuBinary(): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sbin/su", "/vendor/bin/su", "/su/bin/su"
        )
        for (p in paths) {
            if (File(p).exists()) return true
        }
        return false
    }

    /** 申请 root 权限并检查是否获得（返回是否成功） */
    fun requestRoot(): Boolean {
        if (!hasSuBinary()) {
            granted = false
            return false
        }
        granted = try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            if (!ShellExec.waitForTimeout(process, 4000L)) {
                ShellExec.destroyForcibly(process)
                false
            } else {
                val out = process.inputStream.bufferedReader().readText()
                process.errorStream.bufferedReader().readText()
                out.contains("uid=0")
            }
        } catch (_: Exception) {
            false
        }
        return granted
    }

    /** 以 root 身份执行命令并返回 stdout，未授权或失败返回 null */
    fun exec(command: String): String? {
        if (!granted) return null
        return try {
            // 某些 su 实现不支持 `su -c`，这里统一通过 stdin 管道传入命令，兼容性更好
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            // 每次调用使用随机标记，避免命令自身输出包含固定标记导致解析错乱
            val token = "ROOT_READ_${System.nanoTime()}_${(Math.random() * 1e6).toInt()}"
            os.writeBytes("echo $token\n")
            os.writeBytes("$command 2>&1\n")
            os.writeBytes("echo $token\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            if (!ShellExec.waitForTimeout(process, 4000L)) {
                ShellExec.destroyForcibly(process)
                return null
            }
            val out = process.inputStream.bufferedReader().readText()
            val begin = out.indexOf(token)
            if (begin < 0) return null
            val rest = out.substring(begin + token.length)
            val end = rest.indexOf(token)
            val result = (if (end >= 0) rest.substring(0, end) else rest).trim()
            result.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}