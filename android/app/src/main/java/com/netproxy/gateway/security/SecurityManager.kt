package com.netproxy.gateway.security

import android.content.Context
import android.util.Log

/**
 * 安全管理器
 * 统一管理 Root、调试、模拟器等安全检测
 */
class SecurityManager(private val context: Context) {

    companion object {
        private const val TAG = "SecurityManager"

        @Volatile private var instance: SecurityManager? = null

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
         * 获取所有检测到的风险列表
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
         * 获取风险摘要
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
        fun onRootDetected(result: RootDetector.RootCheckResult)
        fun onDebugDetected(result: DebugDetector.DebugCheckResult)
        fun onEmulatorDetected(result: EmulatorDetector.EmulatorCheckResult)
        fun onSecurityCheckComplete(result: SecurityCheckResult)
    }

    private var callback: SecurityCheckCallback? = null

    /**
     * 设置检测回调
     */
    fun setCallback(callback: SecurityCheckCallback) {
        this.callback = callback
    }

    /**
     * 执行完整的安全检测
     */
    fun performSecurityCheck(): SecurityCheckResult {
        Log.d(TAG, "Starting security check...")

        // Root 检测
        val rootResult = RootDetector.check(context)
        if (rootResult.isRooted) {
            Log.w(TAG, "Root detected: ${rootResult.detectedBy}")
            callback?.onRootDetected(rootResult)
        }

        // 调试检测
        val debugResult = DebugDetector.check()
        if (debugResult.isDebugged) {
            Log.w(TAG, "Debugger detected: ${debugResult.detectedBy}")
            callback?.onDebugDetected(debugResult)
        }

        // 模拟器检测
        val emulatorResult = EmulatorDetector.check(context)
        if (emulatorResult.isEmulator) {
            Log.w(TAG, "Emulator detected: ${emulatorResult.detectedBy}")
            callback?.onEmulatorDetected(emulatorResult)
        }

        val result = SecurityCheckResult(
            isSecure = !rootResult.isRooted && !debugResult.isDebugged && !emulatorResult.isEmulator,
            rootResult = rootResult,
            debugResult = debugResult,
            emulatorResult = emulatorResult
        )

        Log.d(TAG, "Security check complete: ${result.getRiskSummary()}")
        callback?.onSecurityCheckComplete(result)

        return result
    }

    /**
     * 快速检测 - 只检测最关键的风险
     */
    fun performQuickCheck(): Boolean {
        return !RootDetector.checkSuBinary() &&
                !DebugDetector.checkDebuggerConnected() &&
                !EmulatorDetector.checkHardware()
    }

    /**
     * 仅执行 Root 检测
     */
    fun checkRootOnly(): RootDetector.RootCheckResult {
        return RootDetector.check(context)
    }

    /**
     * 仅执行调试检测
     */
    fun checkDebugOnly(): DebugDetector.DebugCheckResult {
        return DebugDetector.check()
    }

    /**
     * 仅执行模拟器检测
     */
    fun checkEmulatorOnly(): EmulatorDetector.EmulatorCheckResult {
        return EmulatorDetector.check(context)
    }

    /**
     * 获取安全状态报告
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
     * 检查特定模拟器类型
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
