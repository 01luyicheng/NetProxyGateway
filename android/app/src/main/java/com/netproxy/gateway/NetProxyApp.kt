package com.netproxy.gateway

import android.app.Application
import android.util.Log
import com.netproxy.gateway.security.RootDetector
import com.netproxy.gateway.security.DebugDetector
import com.netproxy.gateway.security.EmulatorDetector
import com.netproxy.gateway.security.SecurityManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NetProxyApp : Application() {

    companion object {
        private const val TAG = "NetProxyApp"
    }

    override fun onCreate() {
        super.onCreate()
        performSecurityChecks()
    }

    /**
     * 执行安全检测
     * 在应用启动时检测 Root、调试器、模拟器等风险
     */
    private fun performSecurityChecks() {
        val securityManager = SecurityManager.getInstance(this)

        // 设置安全检测回调
        securityManager.setCallback(object : SecurityManager.SecurityCheckCallback {
            override fun onRootDetected(result: RootDetector.RootCheckResult) {
                Log.w(TAG, "Root detected by: ${result.detectedBy}")
                handleSecurityRisk("Root", result.detectedBy)
            }

            override fun onDebugDetected(result: DebugDetector.DebugCheckResult) {
                Log.w(TAG, "Debugger detected by: ${result.detectedBy}")
                handleSecurityRisk("Debug", result.detectedBy)
            }

            override fun onEmulatorDetected(result: EmulatorDetector.EmulatorCheckResult) {
                Log.w(TAG, "Emulator detected by: ${result.detectedBy}")
                handleSecurityRisk("Emulator", result.detectedBy)
            }

            override fun onSecurityCheckComplete(result: SecurityManager.SecurityCheckResult) {
                if (result.isSecure) {
                    Log.i(TAG, "Security check passed - device is secure")
                } else {
                    Log.w(TAG, "Security risks detected: ${result.getAllRisks()}")
                }
            }
        })

        // 执行安全检测
        securityManager.performSecurityCheck()
    }

    /**
     * 处理安全风险
     * 当前实现仅记录日志，可根据需求扩展为显示警告、退出应用等
     */
    private fun handleSecurityRisk(riskType: String, details: List<String>) {
        // 记录安全风险日志
        Log.w(TAG, "[$riskType] Security risk detected: ${details.joinToString(", ")}")

        // TODO: 根据安全策略采取进一步措施，例如：
        // 1. 显示安全警告对话框
        // 2. 限制应用功能
        // 3. 上报安全事件
        // 4. 退出应用（在 release 模式下可考虑）
    }
}
