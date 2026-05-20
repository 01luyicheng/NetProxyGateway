package com.netproxy.gateway.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Root 检测器
 * 检测设备是否已被 Root，包括检测 su 文件、Magisk、BusyBox 等
 */
object RootDetector {

    private const val TAG = "RootDetector"
    private const val PROCESS_TIMEOUT_SECONDS = 3L

    // 已知的 Root 管理应用包名
    private val ROOT_PACKAGES = arrayOf(
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "eu.chainfire.supersu",
        "com.koushikdutta.rommanager",
        "com.koushikdutta.rommanager.license",
        "com.dimonvideo.luckypatcher",
        "com.chelpus.lackypatch",
        "com.ramdroid.appquarantine",
        "com.ramdroid.appquarantinepro",
        "com.devadvance.rootcloak",
        "com.devadvance.rootcloakplus",
        "de.robv.android.xposed.installer",
        "com.saurik.substrate",
        "com.zachspong.temprootremovejb",
        "com.amphoras.hidemyroot",
        "com.amphoras.hidemyrootadfree",
        "com.formyhm.hiderootPremium",
        "com.formyhm.hideroot",
        "me.phh.superuser",
        "com.kingouser.com",
        "com.topjohnwu.magisk"
    )

    // 已知的危险路径
    private val ROOT_PATHS = arrayOf(
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/system/bin/su",
        "/system/bin/.ext/su",
        "/system/bin/failsafe/su",
        "/system/sd/xbin/su",
        "/system/usr/we-need-root/su",
        "/system/xbin/su",
        "/cache/su",
        "/data/su",
        "/dev/su",
        "/system/app/Superuser.apk",
        "/system/etc/init.d/99SuperSUDaemon",
        "/system/etc/.has_su_daemon",
        "/system/etc/.installed_su_daemon",
        "/dev/com.koushikdutta.superuser.daemon/",
        "/system/xbin/daemonsu",
        "/system/etc/init.d/99SuperSUDaemon",
        "/system/bin/.ext/.su",
        "/system/etc/.has_su_daemon",
        "/dev/.magisk.unblock",
        "/cache/.disable_magisk",
        "/dev/.magisk",
        "/sbin/.magisk",
        "/data/adb/magisk",
        "/data/adb/magisk/busybox",
        "/data/adb/magisk/magisk",
        "/data/adb/magisk/magisk32",
        "/data/adb/magisk/magisk64"
    )

    // BusyBox 常见路径
    private val BUSYBOX_PATHS = arrayOf(
        "/system/xbin/busybox",
        "/system/bin/busybox",
        "/sbin/busybox",
        "/su/bin/busybox",
        "/data/local/xbin/busybox",
        "/data/local/bin/busybox",
        "/system/sd/xbin/busybox"
    )

    // Magisk 特定文件
    private val MAGISK_FILES = arrayOf(
        "/data/adb/magisk",
        "/sbin/.magisk",
        "/dev/.magisk",
        "/data/adb/.magisk",
        "/data/adb/magisk.img",
        "/data/adb/magisk.db",
        "/data/adb/magisk_simple",
        "/data/magisk",
        "/data/magisk.apk",
        "/data/magisk.img",
        "/data/magisk_merge.img",
        "/data/magisk_debug.log",
        "/cache/magisk.log",
        "/data/adb/magisk/busybox",
        "/data/adb/magisk/magisk32",
        "/data/adb/magisk/magisk64",
        "/data/adb/magisk/magiskpolicy"
    )

    /**
     * Root 检测结果
     */
    data class RootCheckResult(
        val isRooted: Boolean,
        val detectedBy: List<String>
    )

    /**
     * 执行完整的 Root 检测
     */
    fun check(context: Context): RootCheckResult {
        val detectedMethods = mutableListOf<String>()

        if (checkTestKeys()) {
            detectedMethods.add("test-keys")
        }

        if (checkSuperuserApk()) {
            detectedMethods.add("superuser-apk")
        }

        if (checkSuBinary()) {
            detectedMethods.add("su-binary")
        }

        if (checkBusyBox()) {
            detectedMethods.add("busybox")
        }

        if (checkMagisk()) {
            detectedMethods.add("magisk")
        }

        if (checkRootPackages(context)) {
            detectedMethods.add("root-packages")
        }

        if (checkRootCloakingApps(context)) {
            detectedMethods.add("root-cloaking")
        }

        if (checkDangerousProps()) {
            detectedMethods.add("dangerous-props")
        }

        if (checkRWPaths()) {
            detectedMethods.add("rw-paths")
        }

        return RootCheckResult(
            isRooted = detectedMethods.isNotEmpty(),
            detectedBy = detectedMethods
        )
    }

    /**
     * 检查是否包含 test-keys（非官方签名）
     */
    fun checkTestKeys(): Boolean {
        return Build.TAGS?.contains("test-keys") ?: false
    }

    /**
     * 检查 Superuser.apk 是否存在
     */
    fun checkSuperuserApk(): Boolean {
        return File("/system/app/Superuser.apk").exists()
    }

    /**
     * 检查 su 二进制文件
     */
    fun checkSuBinary(): Boolean {
        for (path in ROOT_PATHS) {
            if (File(path).exists()) {
                return true
            }
        }
        return false
    }

    /**
     * 检查 BusyBox
     */
    fun checkBusyBox(): Boolean {
        for (path in BUSYBOX_PATHS) {
            if (File(path).exists()) {
                return true
            }
        }

        // 尝试执行 which busybox
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "busybox"))
            try {
                BufferedReader(InputStreamReader(process.errorStream)).use { errorReader ->
                    while (errorReader.readLine() != null) {
                        // 消费错误输出，避免阻塞
                    }
                }
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    val result = reader.readLine()
                    val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (!finished) {
                        return@use false
                    }
                    result != null && result.isNotEmpty()
                }
            } finally {
                process.destroy()
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查 Magisk
     */
    fun checkMagisk(): Boolean {
        for (path in MAGISK_FILES) {
            if (File(path).exists()) {
                return true
            }
        }

        // 检查 Magisk 特定属性
        return checkMagiskProps()
    }

    /**
     * 检查 Magisk 属性
     */
    private fun checkMagiskProps(): Boolean {
        val magiskProps = arrayOf(
            "init.svc.zygote",
            "persist.sys.isUsbOtgEnabled",
            "ro.magisk.version"
        )

        for (prop in magiskProps) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("getprop", prop))
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { errorReader ->
                        while (errorReader.readLine() != null) {
                            // 消费错误输出，避免阻塞
                        }
                    }
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        val value = reader.readLine()
                        val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        if (!finished) {
                            return@use
                        }
                        if (!value.isNullOrEmpty() && value != "0" && value != "") {
                            return true
                        }
                    }
                } finally {
                    process.destroy()
                }
            } catch (e: Exception) {
                // 忽略异常
            }
        }
        return false
    }

    /**
     * 检查已安装的 Root 管理应用
     */
    fun checkRootPackages(context: Context): Boolean {
        val pm = context.packageManager
        for (packageName in ROOT_PACKAGES) {
            try {
                pm.getPackageInfo(packageName, 0)
                return true
            } catch (e: PackageManager.NameNotFoundException) {
                // 包不存在，继续检查下一个
            }
        }
        return false
    }

    /**
     * 检查 Root 隐藏应用
     */
    fun checkRootCloakingApps(context: Context): Boolean {
        val cloakingPackages = arrayOf(
            "com.devadvance.rootcloak",
            "com.devadvance.rootcloakplus",
            "de.robv.android.xposed.installer",
            "com.saurik.substrate",
            "com.zachspong.temprootremovejb",
            "com.amphoras.hidemyroot",
            "com.amphoras.hidemyrootadfree",
            "com.formyhm.hiderootPremium",
            "com.formyhm.hideroot"
        )

        val pm = context.packageManager
        for (packageName in cloakingPackages) {
            try {
                pm.getPackageInfo(packageName, 0)
                return true
            } catch (e: PackageManager.NameNotFoundException) {
                // 包不存在，继续检查下一个
            }
        }
        return false
    }

    /**
     * 检查危险属性
     */
    fun checkDangerousProps(): Boolean {
        val dangerousProps = mapOf(
            "ro.debuggable" to "1",
            "ro.secure" to "0"
        )

        for ((key, badValue) in dangerousProps) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { errorReader ->
                        while (errorReader.readLine() != null) {
                            // 消费错误输出，避免阻塞
                        }
                    }
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        val value = reader.readLine()
                        val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        if (!finished) {
                            return@use
                        }
                        if (value == badValue) {
                            return true
                        }
                    }
                } finally {
                    process.destroy()
                }
            } catch (e: Exception) {
                // 忽略异常
            }
        }
        return false
    }

    /**
     * 检查可写系统路径
     */
    fun checkRWPaths(): Boolean {
        val paths = arrayOf(
            "/system",
            "/system/bin",
            "/system/sbin",
            "/system/xbin",
            "/vendor/bin",
            "/sbin",
            "/etc"
        )

        for (path in paths) {
            val file = File(path)
            if (file.exists() && file.canWrite()) {
                return true
            }
        }
        return false
    }

    /**
     * 尝试执行 su 命令（主动检测）
     */
    fun checkSuExecution(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            try {
                BufferedReader(InputStreamReader(process.errorStream)).use { errorReader ->
                    while (errorReader.readLine() != null) {
                        // 消费错误输出，避免阻塞
                    }
                }
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    val output = reader.readLine()
                    val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (!finished) {
                        return@use false
                    }
                    output?.contains("uid=0") ?: false
                }
            } finally {
                process.destroy()
            }
        } catch (e: Exception) {
            false
        }
    }
}
