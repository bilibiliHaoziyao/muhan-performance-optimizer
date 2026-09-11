package com.muhan.socbatteryinfo.util

/** RAM 数据：物理内存与虚拟内存 */
data class RamData(
    val totalBytes: Long?,
    val availableBytes: Long?,
    val swapTotalBytes: Long?,
    val swapFreeBytes: Long?,
    val viaRoot: Boolean,
    val viaShizuku: Boolean
)

/** 存储分区数据 */
data class StoragePartition(
    val mountPoint: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long,
    val viaRoot: Boolean,
    val viaShizuku: Boolean
)

/** 内存与存储读取 */
object StorageReader {

    private const val KB = 1024L

    fun readRam(): RamData {
        var meminfo = readProcMeminfo()
        var viaRoot = false
        var viaShizuku = false

        if (meminfo == null) {
            ShizukuShell.exec("cat /proc/meminfo")?.let {
                meminfo = it
                viaShizuku = true
            }
        }
        if (meminfo == null && RootShell.granted) {
            RootShell.exec("cat /proc/meminfo")?.let {
                meminfo = it
                viaRoot = true
            }
        }

        if (meminfo == null) {
            return RamData(null, null, null, null, false, false)
        }

        val lines = meminfo.lines()
        val total = findMemValue(lines, "MemTotal:")
        val available = findMemValue(lines, "MemAvailable:")
        val swapTotal = findMemValue(lines, "SwapTotal:")
        val swapFree = findMemValue(lines, "SwapFree:")

        return RamData(
            totalBytes = total?.times(KB),
            availableBytes = available?.times(KB),
            swapTotalBytes = swapTotal?.times(KB),
            swapFreeBytes = swapFree?.times(KB),
            viaRoot = viaRoot,
            viaShizuku = viaShizuku
        )
    }

    private fun findMemValue(lines: List<String>, key: String): Long? {
        val line = lines.firstOrNull { it.startsWith(key) } ?: return null
        val num = line.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()
        return num
    }

    private fun readProcMeminfo(): String? {
        return ThermalReader.readFile("/proc/meminfo")
    }

    fun readPartitions(): List<StoragePartition> {
        val result = mutableListOf<StoragePartition>()
        var viaRoot = false
        var viaShizuku = false

        var dfOutput = execDf()
        if (dfOutput == null) {
            ShizukuShell.exec("df -k")?.let {
                dfOutput = it
                viaShizuku = true
            }
        }
        if (dfOutput == null && RootShell.granted) {
            RootShell.exec("df -k")?.let {
                dfOutput = it
                viaRoot = true
            }
        }

        if (dfOutput == null) return result

        val lines = dfOutput.lines()
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 6) continue
            val filesystem = parts[0]
            val totalKb = parts[1].toLongOrNull() ?: continue
            val usedKb = parts[2].toLongOrNull() ?: continue
            val availableKb = parts[3].toLongOrNull() ?: continue
            val mountPoint = parts[5]

            if (!shouldShowPartition(filesystem, mountPoint)) continue

            result.add(
                StoragePartition(
                    mountPoint = mountPoint,
                    totalBytes = totalKb * KB,
                    usedBytes = usedKb * KB,
                    availableBytes = availableKb * KB,
                    viaRoot = viaRoot,
                    viaShizuku = viaShizuku
                )
            )
        }

        result.sortBy { p ->
            when {
                p.mountPoint == "/data" -> 0
                p.mountPoint.startsWith("/mnt") -> 1
                else -> 2
            }
        }
        return result
    }

    private fun execDf(): String? = ShellExec.execSh("df -k")

    private fun shouldShowPartition(filesystem: String, mountPoint: String): Boolean {
        if (mountPoint.isEmpty()) return false
        val skipPrefixes = listOf(
            "/proc", "/sys", "/dev", "/system", "/vendor",
            "/product", "/odm", "/cache", "/metadata"
        )
        for (skip in skipPrefixes) {
            if (mountPoint.startsWith(skip)) return false
        }
        // 仅精确跳过根挂载点 /mnt，放行 /mnt/expand（外置 SD 卡）
        if (mountPoint == "/mnt") return false
        val skipFs = listOf("tmpfs", "devtmpfs", "overlay", "squashfs", "fuse")
        for (fs in skipFs) {
            if (filesystem.startsWith(fs)) return false
        }
        return true
    }

    fun formatBytes(bytes: Long?): String {
        if (bytes == null) return "--"
        return when {
            bytes >= 1_073_741_824L -> String.format("%.2f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> String.format("%.2f MB", bytes / 1_048_576.0)
            bytes >= 1024L -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    fun formatPercent(used: Long, total: Long): String {
        if (total <= 0) return "--"
        return String.format("%.1f%%", used.toDouble() / total * 100)
    }
}
