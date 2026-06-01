package com.netproxy.gateway

import android.app.Application
import com.netproxy.gateway.security.RootDetector
import com.netproxy.gateway.security.DebugDetector
import com.netproxy.gateway.security.EmulatorDetector
import com.netproxy.gateway.security.SecurityManager
import dagger.hilt.android.HiltAndroidApp
import org.slf4j.LoggerFactory

@HiltAndroidApp
class NetProxyApp : Application() {

    companion object {
        private val logger = LoggerFactory.getLogger(NetProxyApp::class.java)
    }

    /**
     * 在应用启动时完成必要的初始化并触发安全检测流程。
     *
     * 调用父类的初始化逻辑后启动应用的安全检查（如 Root、调试器、模拟器检测）。
     */
    override fun onCreate() {
        super.onCreate()
        performSecurityChecks()
    }

    /**
     * 初始化并运行应用启动时的安全检测，识别 Root、调试器和模拟器等风险并统一上报处理。
     *
     * 在检测完成后根据结果记录通过或风险详情，并通过现有的风险处理入口（handleSecurityRisk）对每类风险进行处理。
     */
    private fun performSecurityChecks() {
        val securityManager = SecurityManager.getInstance(this)

        // 设置安全检测回调
        securityManager.setCallback(object : SecurityManager.SecurityCheckCallback {
            /**
             * 处理检测到的 Root 风险。
             *
             * 记录包含触发检测器来源的告警，并将该检测结果交给统一的安全风险处理流程。
             *
             * @param result 包含 Root 检测详情的结果对象，`detectedBy` 指示触发 Root 判定的检测器或规则集合。
             */
            override fun onRootDetected(result: RootDetector.RootCheckResult) {
                logger.warn("Root detected by: ${result.detectedBy}")
                handleSecurityRisk("Root", result.detectedBy)
            }

            /**
             * 在检测到调试器时记录告警并处理对应的安全风险。
             *
             * @param result 包含检测到调试器的信息的结果对象，`detectedBy` 字段列出触发检测的来源或规则。
             */
            override fun onDebugDetected(result: DebugDetector.DebugCheckResult) {
                logger.warn("Debugger detected by: ${result.detectedBy}")
                handleSecurityRisk("Debug", result.detectedBy)
            }

            /**
             * 在检测到设备运行于模拟器时处理该回调并记录风险信息。
             *
             * 记录一条告警日志并将检测到的风险详情传递给统一的安全风险处理流程。
             *
             * @param result 包含触发模拟器判定的检测结果，`detectedBy` 字段列出用于判定的检测器或规则标识。
             */
            override fun onEmulatorDetected(result: EmulatorDetector.EmulatorCheckResult) {
                logger.warn("Emulator detected by: ${result.detectedBy}")
                handleSecurityRisk("Emulator", result.detectedBy)
            }

            /**
             * 在安全检查完成时记录设备安全状态；如果发现风险则记录风险详情。
             *
             * @param result 包含安全检查结果的对象。若 `result.isSecure` 为 `true` 则记录通过信息，否则以警告级别记录 `result.getAllRisks()` 提供的风险列表。
             */
            override fun onSecurityCheckComplete(result: SecurityManager.SecurityCheckResult) {
                if (result.isSecure) {
                    logger.info("Security check passed - device is secure")
                } else {
                    logger.warn("Security risks detected: ${result.getAllRisks()}")
                }
            }
        })

        // 执行安全检测
        securityManager.performSecurityCheck()
    }

    /**
     * 处理检测到的安全风险并记录相关信息。
     *
     * 目前实现将风险类型及详情以告警级别记录到日志，后续可在此处扩展为展示警告、限制功能、上报或退出应用等策略。
     *
     * @param riskType 风险类型的标签（例如 "Root"、"Debug"、"Emulator"）。
     * @param details 导致该风险被标记的检测项或证据列表。
     */
    private fun handleSecurityRisk(riskType: String, details: List<String>) {
        // 记录安全风险日志
        logger.warn("[$riskType] Security risk detected: ${details.joinToString(", ")}")

        // 可选扩展：根据安全策略采取进一步措施，例如：
        // 1. 显示安全警告对话框
        // 2. 限制应用功能
        // 3. 上报安全事件
        // 4. 退出应用（在 release 模式下可考虑）
    }
}
