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
     * 执行完整的调试检测
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
     * 检查是否有调试器连接（Java 层）
     */
    fun checkDebuggerConnected(): Boolean {
        return Debug.isDebuggerConnected()
    }

    /**
     * 检查 /proc/self/status 中的 TracerPid
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
     * 检查 ptrace 状态
     */
    fun checkPtraceStatus(): Boolean {
        return try {
            val statusFile = File("/proc/self/status")
            if (!statusFile.exists()) return false

            parsePtraceStatus(statusFile.readText())
        } catch (e: Exception) {
            false
        }
    }

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

    internal fun parseTracerPid(statusContent: String): Int {
        return statusContent
            .lineSequence()
            .firstOrNull { it.startsWith("TracerPid:") }
            ?.substringAfter(":")
            ?.trim()
            ?.toIntOrNull()
            ?: 0
    }

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
     * 检查调试器进程
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
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查是否为 Debug 构建
     */
    fun checkDebugBuild(): Boolean {
        return resolveDebugBuildState(
            isBuildConfigDebug = BuildConfig.DEBUG,
            applicationInfoFlagsProvider = { currentApplicationInfoFlags() }
        )
    }

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
     * 检查调试属性
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
                }
            } catch (e: Exception) {
                // 忽略异常
            }
        }
        return false
    }

    /**
     * 检查 JDWP（Java Debug Wire Protocol）
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
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查 Frida 框架
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
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查 Xposed 框架
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
     * 检查时间差异常（反动态调试）
     * 在调试器中单步执行时，时间差会异常大
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
     * 使用 ptrace 自我防护
     * 通过 ptrace PTRACE_TRACEME 防止其他进程附加
     *
     * 当前为占位实现，待 JNI 集成后启用
     */
    @Deprecated("占位实现，待 JNI 集成后启用", level = DeprecationLevel.WARNING)
    private fun antiPtrace(): Boolean {
        return false
    }

    /**
     * 检查内存断点
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
