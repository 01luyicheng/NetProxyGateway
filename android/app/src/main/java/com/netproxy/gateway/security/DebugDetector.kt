package com.netproxy.gateway.security

import android.os.Debug
import com.netproxy.gateway.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 反调试检测器
 * 检测调试器附加、ptrace 状态、调试属性等
 */
object DebugDetector {

    private const val TAG = "DebugDetector"
    private const val PROCESS_TIMEOUT_SECONDS = 3L

    // 常见调试器进程名
    private val DEBUGGER_PROCESS_NAMES = arrayOf(
        "gdb",
        "gdbserver",
        "lldb",
        "lldb-server",
        "strace",
        "frida-server",
        "frida",
        "xposed",
        "substrate"
    )

    // 常见调试相关文件
    private val DEBUG_FILES = arrayOf(
        "/proc/self/status",
        "/proc/self/task/%d/status"
    )

    /**
     * 调试检测结果
     */
    data class DebugCheckResult(
        val isDebugged: Boolean,
        val detectedBy: List<String>
    )

    /**
     * 执行一系列反调试与调试环境检测，并将触发的检测项汇总为结果。
     *
     * @return 一个包含检测汇总的 [DebugCheckResult]：
     *         - `isDebugged`：若任一检测触发则为 `true`，否则为 `false`；
     *         - `detectedBy`：触发检测的方法标识字符串列表（按检测顺序收集）。
     */
    fun check(): DebugCheckResult {
        val detectedMethods = mutableListOf<String>()

        if (checkDebuggerConnected()) {
            detectedMethods.add("debugger-connected")
        }

        if (checkBeingDebugged()) {
            detectedMethods.add("being-debugged")
        }

        if (checkPtraceStatus()) {
            detectedMethods.add("ptrace-status")
        }

        if (checkDebuggerProcess()) {
            detectedMethods.add("debugger-process")
        }

        if (checkDebugBuild()) {
            detectedMethods.add("debug-build")
        }

        if (checkDebugProperties()) {
            detectedMethods.add("debug-properties")
        }

        if (checkJDWP()) {
            detectedMethods.add("jdwp")
        }

        if (checkFrida()) {
            detectedMethods.add("frida")
        }

        if (checkXposed()) {
            detectedMethods.add("xposed")
        }

        if (checkTimingAttack()) {
            detectedMethods.add("timing-attack")
        }

        if (antiPtrace()) {
            detectedMethods.add("anti-ptrace")
        }

        if (checkMemoryBreakpoints()) {
            detectedMethods.add("memory-breakpoints")
        }

        return DebugCheckResult(
            isDebugged = detectedMethods.isNotEmpty(),
            detectedBy = detectedMethods
        )
    }

    /**
     * 检查当前进程是否有 Java 层调试器连接。
     *
     * @return `true` 如果检测到 Java 层调试器连接，`false` 否则。
     */
    fun checkDebuggerConnected(): Boolean {
        return Debug.isDebuggerConnected()
    }

    /**
     * 检查当前进程在 /proc/self/status 中的 `TracerPid` 是否指示被跟踪（调试）。
     *
     * 读取 `/proc/self/status`，查找以 `TracerPid:` 开头的行并解析其数值；若该值大于 0 则视为被跟踪。
     *
     * @return `true` 当 `TracerPid` 的解析值大于 0，`false` 当文件不存在、未找到 `TracerPid` 行、解析失败或发生异常时。 
     */
    fun checkBeingDebugged(): Boolean {
        return try {
            val statusFile = File("/proc/self/status")
            if (!statusFile.exists()) return false

            BufferedReader(FileReader(statusFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line?.startsWith("TracerPid:") == true) {
                        val tracerPid = line?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                        return tracerPid != 0
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查当前进程的 ptrace/追踪状态。
     *
     * 读取 `/proc/self/status` 并解析其中的 `TracerPid` 与 `PPid` 信息；当检测到被其他进程追踪（TracerPid != 0）或父进程为 PID 1 时视为可疑并返回 `true`。
     *
     * @return `true` 如果检测到被 ptrace/追踪 或 父进程为 1，`false` 如果未检测到或在读取/解析过程中发生错误。 */
    fun checkPtraceStatus(): Boolean {
        return try {
            val statusFile = File("/proc/self/status")
            if (!statusFile.exists()) return false

            parsePtraceStatus(statusFile.readText())
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 基于 /proc/[pid]/status 的文本内容判断进程是否可能被调试或由可疑父进程启动。
     *
     * @param statusContent /proc/[pid]/status 的完整文本内容。
     * @param currentPid 当前进程的 PID（默认使用 android.os.Process.myPid()），用于上下文判定。
     * @return `true` 如果 `TracerPid` 不为 0 或 `PPid` 等于 1，`false` 否则。
     */
    internal fun parsePtraceStatus(statusContent: String, currentPid: Int = android.os.Process.myPid()): Boolean {
        val tracerPid = statusContent
            .lineSequence()
            .firstOrNull { it.startsWith("TracerPid:") }
            ?.substringAfter(":")
            ?.trim()
            ?.toIntOrNull() ?: 0

        val ppid = statusContent
            .lineSequence()
            .firstOrNull { it.startsWith("PPid:") }
            ?.substringAfter(":")
            ?.trim()
            ?.toIntOrNull() ?: 0

        // TracerPid 检测：非零表示有调试器附加
        if (tracerPid != 0) return true

        // PPid 检测：仅当 PPid 为异常值时才认为可疑
        // 正常 Android App 的父进程应该是 zygote，PPid 通常 > 100
        // PPid = 1 表示由 init 直接启动，可能是调试器启动的进程
        return ppid == 1
    }

    /**
     * 从给定的 `/proc/<pid>/status` 文本中提取 `TracerPid` 的整数值。
     *
     * @param statusContent `/proc/<pid>/status` 文件的完整文本内容。
     * @return 提取到的 `TracerPid` 的整数值；若未找到或无法解析则返回 `0`。
     */
    internal fun parseTracerPid(statusContent: String): Int {
        return statusContent
            .lineSequence()
            .firstOrNull { it.startsWith("TracerPid:") }
            ?.substringAfter(":")
            ?.trim()
            ?.toIntOrNull()
            ?: 0
    }

    /**
     * 从 `/proc/<pid>/status` 格式的文本中提取 `PPid`（父进程 ID）。
     *
     * @param statusContent 要解析的 `/proc/*/status` 文件内容文本。
     * @return 解析得到的 `PPid`（父进程 ID），若 `PPid` 字段不存在或无法解析为整数则返回 `0`。
     */
    internal fun parsePpid(statusContent: String): Int {
        return statusContent
            .lineSequence()
            .firstOrNull { it.startsWith("PPid:") }
            ?.substringAfter(":")
            ?.trim()
            ?.toIntOrNull()
            ?: 0
    }

    /**
     * 扫描系统进程列表以检测已知调试器或调试工具是否正在运行。
     *
     * @return `true` 如果检测到已知调试器或调试工具进程，`false` 否则。
     */
    fun checkDebuggerProcess(): Boolean {
        return try {
            val process = ProcessBuilder("ps")
                .redirectErrorStream(true)
                .start()
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val lowerLine = line?.lowercase() ?: continue
                        for (debugger in DEBUGGER_PROCESS_NAMES) {
                            if (lowerLine.contains(debugger)) {
                                return true
                            }
                        }
                    }
                }
                false
            } finally {
                process.destroyForcibly()
                process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 确定当前应用是否为可调试的 Debug 构建。
     *
     * @return `true` 当 BuildConfig.DEBUG 为 `true` 或应用的 ApplicationInfo.flags 包含 `FLAG_DEBUGGABLE`，`false` 否则。
     */
    fun checkDebugBuild(): Boolean {
        return resolveDebugBuildState(
            isBuildConfigDebug = BuildConfig.DEBUG,
            applicationInfoFlagsProvider = { currentApplicationInfoFlags() }
        )
    }

    /**
     * 判断当前应用是否被标记为可调试（基于 BuildConfig 和 ApplicationInfo flags）。
     *
     * @param isBuildConfigDebug 来自 BuildConfig.DEBUG 的值；若为 `true` 则直接视为可调试。
     * @param applicationInfoFlagsProvider 延迟获取 `ApplicationInfo.flags` 的函数；可为 null 或抛出异常。
     * @return `true` 当 BuildConfig.DEBUG 为 `true` 或者 `ApplicationInfo.FLAG_DEBUGGABLE` 在 flags 中被设置，`false` 否则（包括 flags 为 null 或提供者抛出异常的情况）。
     */
    internal fun resolveDebugBuildState(
        isBuildConfigDebug: Boolean,
        applicationInfoFlagsProvider: () -> Int?
    ): Boolean {
        if (isBuildConfigDebug) {
            return true
        }

        return try {
            val flags = applicationInfoFlagsProvider()
            (flags?.and(android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) ?: 0) != 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 通过反射获取当前 Application 的 applicationInfo.flags 值。
     *
     * @return 当前 Application 的 `applicationInfo.flags` 的整数值；若无法获取或发生异常则返回 `null`。
     */
    private fun currentApplicationInfoFlags(): Int? {
        return try {
            val application = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.app.Application
            application?.applicationInfo?.flags
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 检测系统属性以判定设备是否处于可调试或允许 ADB 的状态。
     *
     * 检查以下系统属性并基于其值判断是否存在可被利用的调试环境：
     * - `ro.debuggable`：当值为 `"1"` 时视为可调试；
     * - `ro.secure`：当值为 `"0"` 时视为不安全（可疑）；
     * - `persist.sys.usb.config`：当值包含 `"adb"` 时视为启用 ADB。
     * 异常或命令超时会被忽略并继续检查其他属性。
     *
     * @return `true` 若任一属性指示可调试或启用 ADB，`false` 否则。
     */
    fun checkDebugProperties(): Boolean {
        val debugProps = arrayOf(
            "ro.debuggable",
            "ro.secure",
            "persist.sys.usb.config"
        )

        for (prop in debugProps) {
            try {
                val process = ProcessBuilder("getprop", prop)
                    .redirectErrorStream(true)
                    .start()
                try {
                    BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                        val value = reader.readLine()
                        val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        if (!finished) {
                            return@use
                        }

                        when (prop) {
                            "ro.debuggable" -> if (value == "1") return true
                            "ro.secure" -> if (value == "0") return true
                            "persist.sys.usb.config" -> if (value?.contains("adb") == true) return true
                        }
                    }
                } finally {
                    process.destroyForcibly()
                    process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            } catch (e: Exception) {
                // 忽略异常
            }
        }
        return false
    }

    /**
     * 检测系统进程列表中是否存在与 JDWP（Java Debug Wire Protocol）相关的进程条目。
     *
     * @return `true` 如果在进程列表中发现包含 "jdwp" 的行，`false` 否则。
     */
    fun checkJDWP(): Boolean {
        return try {
            val jdwpProcess = ProcessBuilder("ps", "-A")
                .redirectErrorStream(true)
                .start()
            try {
                BufferedReader(InputStreamReader(jdwpProcess.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line?.contains("jdwp") == true) {
                            return true
                        }
                    }
                }
                false
            } finally {
                jdwpProcess.destroyForcibly()
                jdwpProcess.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检测设备上是否存在 Frida 相关痕迹（文件或进程）。
     *
     * 先检查一组常见的 Frida 文件路径，若未命中则读取系统进程列表查找进程名中包含 `frida` 或 `gadget` 的条目。
     * 在发生异常或未发现任何痕迹时返回 `false`。
     *
     * @return `true` 当检测到 Frida 相关文件或进程名包含 `frida` 或 `gadget`，`false` 否则或发生异常。
     */
    fun checkFrida(): Boolean {
        // 检查 Frida 特定文件
        val fridaFiles = arrayOf(
            "/data/local/tmp/frida-server",
            "/data/local/tmp/frida",
            "/data/local/tmp/frida-gadget",
            "/data/local/tmp/re.frida.server",
            "/data/local/tmp/re.frida",
            "/data/data/re.frida.server",
            "/data/data/re.frida",
            "/data/data/re.frida.server/files/frida-server"
        )

        for (file in fridaFiles) {
            if (File(file).exists()) {
                return true
            }
        }

        // 检查 Frida 进程
        return try {
            val process = ProcessBuilder("ps", "-A")
                .redirectErrorStream(true)
                .start()
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val lowerLine = line?.lowercase() ?: continue
                        if (lowerLine.contains("frida") || lowerLine.contains("gadget")) {
                            return true
                        }
                    }
                }
                false
            } finally {
                process.destroyForcibly()
                process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检测设备上是否存在 Xposed 框架的指示器。
     *
     * 通过检查常见的 Xposed 相关文件路径或尝试加载 `de.robv.android.xposed.XposedBridge` 类来判断。
     *
     * @return `true` 表示检测到 Xposed（存在常见文件或能加载 `XposedBridge` 类），`false` 表示未检测到。
     */
    fun checkXposed(): Boolean {
        // 检查 Xposed 特定文件
        val xposedFiles = arrayOf(
            "/data/data/de.robv.android.xposed.installer",
            "/data/app/de.robv.android.xposed.installer",
            "/system/framework/XposedBridge.jar",
            "/system/bin/app_process_xposed",
            "/system/xposed",
            "/data/xposed"
        )

        for (file in xposedFiles) {
            if (File(file).exists()) {
                return true
            }
        }

        // 检查 Xposed 类
        return try {
            Class.forName("de.robv.android.xposed.XposedBridge")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    /**
     * 检测短时间计算任务的执行耗时是否异常以发现可能的动态调试或单步调试行为。
     *
     * @param thresholdMs 触发检测的时间阈值（毫秒），当任务耗时大于此值时被视为可疑。默认值为 1000。
     * @return `true` 如果任务执行耗时大于 thresholdMs，`false` 否则。
     */
    fun checkTimingAttack(thresholdMs: Long = 1000): Boolean {
        val startTime = System.currentTimeMillis()

        // 执行一些简单操作
        var sum = 0
        for (i in 0 until 1000000) {
            sum += i
        }

        val endTime = System.currentTimeMillis()
        val diff = endTime - startTime

        return diff > thresholdMs
    }

    /**
     * 使用 ptrace 进行自我保护以阻止其他进程附加（占位实现）。
     *
     * 当前为占位实现，始终返回 `false`；计划在集成 JNI 后启用真正的 ptrace 防护。
     *
     * @return `true` 表示已成功启用 ptrace 自我防护，`false` 表示未启用（当前始终为 `false`）。
     */
    @Deprecated("占位实现，待 JNI 集成后启用", level = DeprecationLevel.WARNING)
    private fun antiPtrace(): Boolean {
        return false
    }

    /**
     * 检测进程内存映射中可能用于内存断点或代码注入的可执行且可写区域。
     *
     * 扫描 /proc/self/maps 的每一行；若出现包含 "rwx" 或 "rxp" 的映射，且该映射不属于 "[stack]" 或 "[heap]"，则视为可疑并返回 `true`。当映射文件不存在、未发现可疑项或读取/解析发生异常时返回 `false`。
     *
     * @return `true` 表示检测到可疑的可写可执行内存映射，`false` 表示未检测到或在读取/解析期间发生错误。
     */
    fun checkMemoryBreakpoints(): Boolean {
        return try {
            val mapsFile = File("/proc/self/maps")
            if (!mapsFile.exists()) return false

            BufferedReader(FileReader(mapsFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    // 检查是否有可疑的内存映射
                    if (line?.contains("rwx") == true || line?.contains("rxp") == true) {
                        // 进一步检查是否为可疑区域
                        if (line.contains("[stack]") || line.contains("[heap]")) {
                            continue
                        }
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
