package com.netproxy.gateway.security

import android.content.Context
import org.slf4j.LoggerFactory

/**
 * 安全管理器
 * 统一管理 Root、调试、模拟器等安全检测
 */
class SecurityManager(private val context: Context) {

    companion object {
        private val logger = LoggerFactory.getLogger(SecurityManager::class.java)

        private @Volatile var instance: SecurityManager? = null

        /**
         * 获取 SecurityManager 的线程安全单例实例。
         *
         * 使用传入 Context 的 applicationContext 来创建单例（如尚未创建），并保证在并发场景下只构造一个实例。
         *
         * @param context 任意 Android Context；方法会使用其 `applicationContext` 来初始化单例。
         * @return 单例的 SecurityManager 实例。
         */
        fun getInstance(context: Context): SecurityManager {
            return instance ?: synchronized(this) {
                instance ?: SecurityManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * 安全检测结果
     */
    data class SecurityCheckResult(
        val isSecure: Boolean,
        val rootResult: RootDetector.RootCheckResult,
        val debugResult: DebugDetector.DebugCheckResult,
        val emulatorResult: EmulatorDetector.EmulatorCheckResult,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        /**
         * 汇总并返回被检测到的风险条目。
         *
         * @return 包含按检测类别构造的风险字符串列表（例如 `"ROOT: <detectors>"`、`"DEBUG: <detectors>"`、`"EMULATOR: <detectors>"`）；若未检测到任何风险则返回空列表。
         */
        fun getAllRisks(): List<String> {
            val risks = mutableListOf<String>()
            if (rootResult.isRooted) {
                risks.add("ROOT: ${rootResult.detectedBy.joinToString(", ")}")
            }
            if (debugResult.isDebugged) {
                risks.add("DEBUG: ${debugResult.detectedBy.joinToString(", ")}")
            }
            if (emulatorResult.isEmulator) {
                risks.add("EMULATOR: ${emulatorResult.detectedBy.joinToString(", ")}")
            }
            return risks
        }

        /**
         * 生成包含已检测风险类别的简要摘要。
         *
         * @return `Secure` 如果未检测到任何风险，否则返回以逗号和空格分隔的风险类别字符串（例如 `"Rooted, Debugged"`）。
         */
        fun getRiskSummary(): String {
            val parts = mutableListOf<String>()
            if (rootResult.isRooted) parts.add("Rooted")
            if (debugResult.isDebugged) parts.add("Debugged")
            if (emulatorResult.isEmulator) parts.add("Emulator")
            return if (parts.isEmpty()) "Secure" else parts.joinToString(", ")
        }
    }

    /**
     * 安全检测回调接口
     */
    interface SecurityCheckCallback {
        /**
 * 在检测到设备已获取 Root 权限时被调用。
 *
 * @param result 包含 Root 检测的详细信息，例如是否检测到 root 迹象及相关证据，用于进一步处理或上报。
 */
fun onRootDetected(result: RootDetector.RootCheckResult)
        /**
 * 在发现应用被调试时被调用。
 *
 * @param result 包含调试检测详情的结果对象（例如是否被调试及相关证据）。
 */
fun onDebugDetected(result: DebugDetector.DebugCheckResult)
        /**
 * 当检测到运行环境可能为模拟器时被调用的回调方法。
 *
 * @param result 包含模拟器检测详情的结果对象（例如是否被判断为模拟器、触发的检测标志和相关元信息）。
 */
fun onEmulatorDetected(result: EmulatorDetector.EmulatorCheckResult)
        /**
 * 在完整安全检测完成后接收并处理检测结果回调。
 *
 * @param result 包含整体安全状态、各检测器（Root、Debug、Emulator）具体结果及时间戳的汇总对象。
 */
fun onSecurityCheckComplete(result: SecurityCheckResult)
    }

    private var callback: SecurityCheckCallback? = null

    /**
     * 为 SecurityManager 设置回调以接收 Root、调试、模拟器检测事件及检测完成通知。
     *
     * @param callback 用于接收检测事件的回调实现；在对应检测被触发时会调用相应方法，并在全部检测完成后调用 `onSecurityCheckComplete`。
     */
    fun setCallback(callback: SecurityCheckCallback) {
        this.callback = callback
    }

    /**
     * 执行根（Root）、调试（Debugger）与模拟器（Emulator）三项完整安全检测并返回综合结果。
     *
     * 会在各检测项命中时分别触发对应的回调（若已设置）：`onRootDetected`、`onDebugDetected`、`onEmulatorDetected`，
     * 并在检测完成后始终触发 `onSecurityCheckComplete` 回调。
     *
     * @return 包含整体安全状态 (`isSecure`)、各检测器的详细结果及时间戳的 `SecurityCheckResult`。
     */
    fun performSecurityCheck(): SecurityCheckResult {
        logger.debug("Starting security check...")

        // Root 检测
        val rootResult = RootDetector.check(context)
        if (rootResult.isRooted) {
            logger.warn("Root detected: ${rootResult.detectedBy}")
            callback?.onRootDetected(rootResult)
        }

        // 调试检测
        val debugResult = DebugDetector.check()
        if (debugResult.isDebugged) {
            logger.warn("Debugger detected: ${debugResult.detectedBy}")
            callback?.onDebugDetected(debugResult)
        }

        // 模拟器检测
        val emulatorResult = EmulatorDetector.check(context)
        if (emulatorResult.isEmulator) {
            logger.warn("Emulator detected: ${emulatorResult.detectedBy}")
            callback?.onEmulatorDetected(emulatorResult)
        }

        val result = SecurityCheckResult(
            isSecure = !rootResult.isRooted && !debugResult.isDebugged && !emulatorResult.isEmulator,
            rootResult = rootResult,
            debugResult = debugResult,
            emulatorResult = emulatorResult
        )

        logger.debug("Security check complete: ${result.getRiskSummary()}")
        callback?.onSecurityCheckComplete(result)

        return result
    }

    /**
     * 执行对 su 二进制、调试连接和模拟器硬件的快速安全检查。
     *
     * @return `true` 表示未检测到 su 二进制、调试器连接或模拟器硬件；`false` 表示至少检测到一项风险。
     */
    fun performQuickCheck(): Boolean {
        return !RootDetector.checkSuBinary() &&
                !DebugDetector.checkDebuggerConnected() &&
                !EmulatorDetector.checkHardware()
    }

    /**
     * 执行设备的 Root 检测并返回检测结果。
     *
     * @return `RootDetector.RootCheckResult`，包含是否已 root 以及相关检测细节和证据字段。
     */
    fun checkRootOnly(): RootDetector.RootCheckResult {
        return RootDetector.check(context)
    }

    /**
     * 执行仅针对调试状态的检测并返回检测结果。
     *
     * @return 调试检测的结果对象 `DebugDetector.DebugCheckResult`，其中 `isDebugged` 为 `true` 表示检测到调试器。
     */
    fun checkDebugOnly(): DebugDetector.DebugCheckResult {
        return DebugDetector.check()
    }

    /**
     * 执行模拟器检测并提供检测结果。
     *
     * @return `EmulatorDetector.EmulatorCheckResult`，包含是否为模拟器及相关检测详情。
     */
    fun checkEmulatorOnly(): EmulatorDetector.EmulatorCheckResult {
        return EmulatorDetector.check(context)
    }

    /**
     * 生成一份包含时间戳、整体状态以及 Root、Debug、Emulator 检测详细信息的可读文本报告。
     *
     * 报告为多行文本，包含：时间戳、整体安全状态（SECURE 或 RISK DETECTED），以及每项检测的布尔结果和在命中时列出的触发来源。
     *
     * @return 可直接展示的多行文本报告，包含时间戳、总体状态及各检测项的布尔结果与触发检测来源（若存在）。
     */
    fun getSecurityReport(): String {
        val result = performSecurityCheck()
        val sb = StringBuilder()

        sb.appendLine("=== Security Report ===")
        sb.appendLine("Timestamp: ${result.timestamp}")
        sb.appendLine("Overall Status: ${if (result.isSecure) "SECURE" else "RISK DETECTED"}")
        sb.appendLine()

        sb.appendLine("--- Root Detection ---")
        sb.appendLine("Rooted: ${result.rootResult.isRooted}")
        if (result.rootResult.isRooted) {
            sb.appendLine("Detected by: ${result.rootResult.detectedBy.joinToString(", ")}")
        }
        sb.appendLine()

        sb.appendLine("--- Debug Detection ---")
        sb.appendLine("Debugged: ${result.debugResult.isDebugged}")
        if (result.debugResult.isDebugged) {
            sb.appendLine("Detected by: ${result.debugResult.detectedBy.joinToString(", ")}")
        }
        sb.appendLine()

        sb.appendLine("--- Emulator Detection ---")
        sb.appendLine("Emulator: ${result.emulatorResult.isEmulator}")
        if (result.emulatorResult.isEmulator) {
            sb.appendLine("Detected by: ${result.emulatorResult.detectedBy.joinToString(", ")}")
        }

        return sb.toString()
    }

    /**
     * 判断当前运行环境是否匹配已知的模拟器并返回对应名称。
     *
     * @return 返回已识别的模拟器名称，可能值为 `"BlueStacks"`、`"Nox"`、`"LDPlayer"`、`"MEmu"`、`"Genymotion"`，若未识别则返回 `"Unknown"`。
     */
    fun getEmulatorType(): String {
        return when {
            EmulatorDetector.isBlueStacks() -> "BlueStacks"
            EmulatorDetector.isNox() -> "Nox"
            EmulatorDetector.isLDPlayer() -> "LDPlayer"
            EmulatorDetector.isMEmu() -> "MEmu"
            EmulatorDetector.isGenymotion() -> "Genymotion"
            else -> "Unknown"
        }
    }
}
