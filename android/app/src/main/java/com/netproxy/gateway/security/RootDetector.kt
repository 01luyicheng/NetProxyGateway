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
     * 对设备运行一系列启发式检测以判断是否存在 root 风险，并返回触发的检测项。
     *
     * 依次执行多个检测（例如 test-keys、Superuser.apk、su/BusyBox/Magisk 文件、已知 root 管理或隐藏类应用、危险系统属性、可写系统路径以及尝试执行 `su` 命令），将所有命中的检测标识符收集并包含在返回结果中。
     *
     * @param context Android 上下文，用于查询已安装包及访问与包管理相关的系统信息。
     * @return `RootCheckResult`：`isRooted` 在任一检测命中时为 `true`，`detectedBy` 列出触发的检测标识符列表。
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

        if (checkSuExecution()) {
            detectedMethods.add("su-execution")
        }

        return RootCheckResult(
            isRooted = detectedMethods.isNotEmpty(),
            detectedBy = detectedMethods
        )
    }

    /**
     * 检测 Build.TAGS 中是否包含 "test-keys" 标记。
     *
     * @return `true` 如果包含 "test-keys"，`false` 否则。
     */
    fun checkTestKeys(): Boolean {
        return Build.TAGS?.contains("test-keys") ?: false
    }

    /**
     * 检查系统路径 /system/app 下是否存在 Superuser.apk。
     *
     * @return `true` 如果文件存在，`false` 否则。
     */
    fun checkSuperuserApk(): Boolean {
        return File("/system/app/Superuser.apk").exists()
    }

    /**
     * 检查常见系统路径以判断是否存在 su 可执行文件。
     *
     * @return `true` 如果在预定义路径中发现 su 可执行文件，`false` 否则。
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
     * 检测设备上是否存在 BusyBox，先检查常见文件路径，若未发现则尝试通过 `which busybox` 查询。
     *
     * @return `true` 如果在已知路径或 `which` 命令输出中发现 BusyBox，`false` 否则。
     */
    fun checkBusyBox(): Boolean {
        for (path in BUSYBOX_PATHS) {
            if (File(path).exists()) {
                return true
            }
        }

        // 尝试执行 which busybox
        return try {
            val process = ProcessBuilder("which", "busybox")
                .redirectErrorStream(true)
                .start()
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    val result = reader.readLine()
                    val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (!finished) {
                        return@use false
                    }
                    result != null && result.isNotEmpty()
                }
            } finally {
                process.destroyForcibly()
                process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检测设备上是否存在可能表明已安装 Magisk 的文件或系统属性。
     *
     * @return `true` 如果检测到 Magisk 相关文件或系统属性表明设备可能安装了 Magisk，`false` 否则。
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
     * 检测设备系统属性以判断是否存在 Magisk 指示器。
     *
     * 检查一组 Magisk 相关的系统属性（例如 `ro.magisk.version`），当任一属性的值非空且不等于 `"0"` 时视为检测到 Magisk。
     *
     * @return `true` 表示检测到 Magisk 相关属性，`false` 表示未检测到。 
     */
    private fun checkMagiskProps(): Boolean {
        val magiskProps = arrayOf(
            "ro.magisk.version"
        )

        for (prop in magiskProps) {
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
                        if (!value.isNullOrEmpty() && value != "0" && value != "") {
                            return true
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
     * 检查设备上是否安装了已知的 Root 管理或相关应用包。
     *
     * 会查找 `ROOT_PACKAGES` 列表中的包名，若其中任一包已安装则视为检测命中。
     *
     * @return `true` 如果检测到已安装的已知 Root 管理/相关包，`false` 否则。
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
     * 检测设备上是否安装已知的 Root 隐藏或相关工具的应用包。
     *
     * @return `true` 如果发现任一已知的 root-cloaking 或相关应用包已安装，`false` 否则。
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
     * 检测系统属性中是否存在已知的危险值（指示设备可能被 Root 或处于不安全状态）。
     *
     * 检查的属性包括 `ro.debuggable`（期望值 `"1"` 表示可调试）和 `ro.secure`（期望值 `"0"` 表示不安全）。
     *
     * @return `true` 如果任一被检查的属性的值匹配危险值，`false` 否则。
     */
    fun checkDangerousProps(): Boolean {
        val dangerousProps = mapOf(
            "ro.debuggable" to "1",
            "ro.secure" to "0"
        )

        for ((key, badValue) in dangerousProps) {
            try {
                val process = ProcessBuilder("getprop", key)
                    .redirectErrorStream(true)
                    .start()
                try {
                    BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
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
     * 检查一组典型的系统路径是否存在且具有写权限。
     *
     * @return `true` 如果至少有一个路径存在并且可写，`false` 否则。
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
     * 尝试通过执行 `su` 命令判断设备是否可以获得 root 权限。
     *
     * 在执行 `su -c id` 并检测其输出包含 `uid=0` 时视为已获取 root 权限。
     *
     * @return `true` 如果命令输出表明已获得 root（包含 `uid=0`），`false` 否则。
     */
    fun checkSuExecution(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    val output = reader.readLine()
                    val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (!finished) {
                        return@use false
                    }
                    output?.contains("uid=0") ?: false
                }
            } finally {
                process.destroyForcibly()
                process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            false
        }
    }
}
