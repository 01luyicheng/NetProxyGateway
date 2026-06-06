package com.netproxy.gateway.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 模拟器检测器
 * 检测常见模拟器特征，包括硬件信息、文件特征、属性等
 */
object EmulatorDetector {

    private const val TAG = "EmulatorDetector"
    private const val PROCESS_TIMEOUT_SECONDS = 3L

    private val EMULATOR_PHONE_NUMBERS = setOf(
        "15555215554",
        "15555215556",
        "15555215558",
        "15555215560",
        "15555215562",
        "15555215564",
        "15555215566",
        "15555215568",
        "15555215570",
        "15555215572",
        "15555215574",
        "15555215576",
        "15555215578",
        "15555215580",
        "15555215582",
        "15555215584"
    )

    // 已知模拟器硬件标识
    private val EMULATOR_HARDWARE = arrayOf(
        "goldfish",          // Android SDK 模拟器
        "ranchu",            // 新版 Android 模拟器
        "vbox86",            // VirtualBox
        "ttVM",              // 天天模拟器
        "nox",               // 夜神模拟器
        "ldplayer",          // 雷电模拟器
        "ldmnq",             // 雷电模拟器
        "memu",              // 逍遥模拟器
        "droid4x",           // 海马玩模拟器
        "genymotion",        // Genymotion
        "sdk_phone",         // SDK 手机模拟器
        "sdk_gphone",        // Google Play 模拟器
        "generic",           // 通用模拟器
        "unknown"            // 未知硬件
    )

    // 已知模拟器产品型号
    private val EMULATOR_PRODUCTS = arrayOf(
        "sdk",
        "google_sdk",
        "sdk_x86",
        "sdk_google",
        "vbox86p",
        "nox",
        "ldplayer",
        "ldmnq",
        "memu",
        "droid4x",
        "genymotion",
        "google_sdk_x86"
    )

    // 已知模拟器制造商
    private val EMULATOR_MANUFACTURERS = arrayOf(
        "Genymotion",
        "nox",
        "ldplayer",
        "memu",
        "droid4x",
        "unknown",
        "Google",           // 有时模拟器使用 Google
        "generic"           // 通用制造商
    )

    // 已知模拟器设备名
    private val EMULATOR_DEVICES = arrayOf(
        "generic",
        "generic_x86",
        "generic_x86_64",
        "vbox86p",
        "nox",
        "ldplayer",
        "ldmnq",
        "memu",
        "droid4x",
        "sdk",
        "sdk_x86",
        "sdk_google"
    )

    // 模拟器特定文件
    private val EMULATOR_FILES = arrayOf(
        "/dev/socket/qemud",
        "/dev/qemu_pipe",
        "/system/lib/libc_malloc_debug_qemu.so",
        "/sys/qemu_trace",
        "/system/bin/qemu-props",
        "/data/misc/emu/updatecheck",
        "/sys/devices/virtual/misc/goldfish_pipe",
        "/sys/kernel/debug/goldfish_fb",
        "/dev/goldfish_pipe",
        "/dev/socket/baseband",
        "/sys/class/leds/goldfish",
        "/sys/bus/platform/drivers/goldfish",
        "/proc/tty/drivers",
        "/proc/cpuinfo"
    )

    // 模拟器特定属性
    private val EMULATOR_PROPS = mapOf(
        "init.svc.qemud" to null,
        "init.svc.qemu-props" to null,
        "qemu.hw.mainkeys" to null,
        "qemu.sf.fake_camera" to null,
        "qemu.sf.lcd_density" to null,
        "ro.bootloader" to "unknown",
        "ro.boot.hardware" to "goldfish",
        "ro.hardware" to "goldfish",
        "ro.hardware.vm" to "qemu",
        "ro.kernel.qemu" to "1",
        "ro.kernel.qemu.gles" to null,
        "ro.product.device" to "generic",
        "ro.product.manufacturer" to "unknown",
        "ro.product.model" to "sdk",
        "ro.product.name" to "sdk",
        "ro.product.board" to "unknown",
        "ro.serialno" to null
    )

    // 常见模拟器包名
    private val EMULATOR_PACKAGES = arrayOf(
        "com.bluestacks",
        "com.bluestacks.appmart",
        "com.bignox.app",
        "com.bignox.app.store.hd",
        "com.vphone.launcher",
        "com.mumu.launcher",
        "com.ldmnq.launcher",
        "com.droid4x.app",
        "com.genymotion.genyd",
        "com.genymotion.launcher"
    )

    /**
     * 模拟器检测结果
     */
    data class EmulatorCheckResult(
        val isEmulator: Boolean,
        val detectedBy: List<String>
    )

    /**
     * 执行完整的模拟器检测
     *
     * 判定策略：采用多指标联合阈值机制（至少命中 2 个指标才判定为模拟器），
     * 避免单一弱指标（如 hardware="generic"、manufacturer="unknown"、
     * fingerprint="generic..."、board="unknown" 等）在真机上产生误报。
     */
    fun check(context: Context): EmulatorCheckResult {
        val detectedMethods = mutableListOf<String>()

        if (checkHardware()) {
            detectedMethods.add("hardware")
        }

        if (checkProduct()) {
            detectedMethods.add("product")
        }

        if (checkManufacturer()) {
            detectedMethods.add("manufacturer")
        }

        if (checkDevice()) {
            detectedMethods.add("device")
        }

        if (checkFingerprint()) {
            detectedMethods.add("fingerprint")
        }

        if (checkEmulatorFiles()) {
            detectedMethods.add("emulator-files")
        }

        if (checkEmulatorProps()) {
            detectedMethods.add("emulator-props")
        }

        if (checkEmulatorPackages(context)) {
            detectedMethods.add("emulator-packages")
        }

        if (checkCpuInfo()) {
            detectedMethods.add("cpuinfo")
        }

        // Telephony-based checks omitted: unreliable on Android 10+ (restricted identifiers).

        if (checkQemuDrivers()) {
            detectedMethods.add("qemu-drivers")
        }

        if (checkBoard()) {
            detectedMethods.add("board")
        }

        return EmulatorCheckResult(
            isEmulator = detectedMethods.size >= 2,
            detectedBy = detectedMethods
        )
    }

    /**
     * 检查硬件信息
     */
    fun checkHardware(): Boolean {
        val hardware = Build.HARDWARE?.lowercase() ?: return false
        return EMULATOR_HARDWARE.any { hardware.contains(it.lowercase()) }
    }

    /**
     * 检查产品信息
     */
    fun checkProduct(): Boolean {
        val product = Build.PRODUCT?.lowercase() ?: return false
        return EMULATOR_PRODUCTS.any { product.contains(it.lowercase()) }
    }

    /**
     * 检查制造商信息
     */
    fun checkManufacturer(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: return false
        return EMULATOR_MANUFACTURERS.any { manufacturer.contains(it.lowercase()) }
    }

    /**
     * 检查设备信息
     */
    fun checkDevice(): Boolean {
        val device = Build.DEVICE?.lowercase() ?: return false
        return EMULATOR_DEVICES.any { device.contains(it.lowercase()) }
    }

    /**
     * 检查指纹信息
     */
    fun checkFingerprint(): Boolean {
        val fingerprint = Build.FINGERPRINT?.lowercase() ?: return false
        return fingerprint.startsWith("generic") ||
                fingerprint.contains("test-keys") ||
                fingerprint.contains("sdk") ||
                fingerprint.contains("emulator")
    }

    /**
     * 检查模拟器特定文件
     */
    fun checkEmulatorFiles(): Boolean {
        for (file in EMULATOR_FILES) {
            if (File(file).exists()) {
                return true
            }
        }
        return false
    }

    /**
     * 检查模拟器属性
     */
    fun checkEmulatorProps(): Boolean {
        for ((prop, expectedValue) in EMULATOR_PROPS) {
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

                        if (expectedValue == null) {
                            // 只要属性存在就算检测到
                            if (!value.isNullOrEmpty()) {
                                return true
                            }
                        } else {
                            // 检查属性值是否匹配
                            if (value?.lowercase() == expectedValue.lowercase()) {
                                return true
                            }
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
     * 检查模拟器应用包
     */
    fun checkEmulatorPackages(context: Context): Boolean {
        val pm = context.packageManager
        for (packageName in EMULATOR_PACKAGES) {
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
     * 检查 CPU 信息
     */
    fun checkCpuInfo(): Boolean {
        return try {
            val cpuInfo = File("/proc/cpuinfo")
            if (!cpuInfo.exists()) return false

            BufferedReader(FileReader(cpuInfo)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lowerLine = line?.lowercase() ?: continue
                    if (lowerLine.contains("hypervisor") ||
                        lowerLine.contains("vmware") ||
                        lowerLine.contains("virtualbox") ||
                        lowerLine.contains("kvm") ||
                        lowerLine.contains("qemu") ||
                        lowerLine.contains("goldfish") ||
                        lowerLine.contains("ranchu")
                    ) {
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查电话号码（仅匹配已知模拟器默认号码）。
     * 未纳入 [check]：Android 10+ 上 line1Number 常为空或不可信。
     */
    fun checkPhoneNumber(context: Context): Boolean {
        if (!hasReadPhoneStatePermission(context)) {
            return false
        }

        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val phoneNumber = telephonyManager?.line1Number
            isKnownEmulatorPhoneNumber(phoneNumber)
        } catch (e: Exception) {
            false
        }
    }

    internal fun isKnownEmulatorPhoneNumber(phoneNumber: String?): Boolean {
        if (phoneNumber.isNullOrBlank()) {
            return false
        }
        return EMULATOR_PHONE_NUMBERS.contains(phoneNumber)
    }

    /**
     * 检查设备 ID。
     * 未纳入 [check]：Android 10+ 上 getDeviceId 受限，真机亦可能为空/占位值。
     */
    @Suppress("DEPRECATION")
    fun checkDeviceId(context: Context): Boolean {
        if (!hasReadPhoneStatePermission(context)) {
            return false
        }

        // Note: getDeviceId() is deprecated in API 26+. Use getImei() for API 26+.
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val deviceId = telephonyManager?.deviceId
            deviceId.isNullOrEmpty() ||
                    deviceId == "000000000000000" ||
                    deviceId == "012345678912345"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查 IMEI。
     * 未纳入 [check]：Android 10+ 上 IMEI 访问受限，易产生真机误报。
     */
    fun checkImei(context: Context): Boolean {
        if (!hasReadPhoneStatePermission(context)) {
            return false
        }

        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telephonyManager?.imei
            } else {
                @Suppress("DEPRECATION")
                telephonyManager?.deviceId
            }
            imei.isNullOrEmpty() ||
                    imei == "000000000000000" ||
                    imei == "012345678912345" ||
                    imei == "00000000000000" ||
                    imei.startsWith("0000000000")
        } catch (e: Exception) {
            false
        }
    }

    private fun hasReadPhoneStatePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查 QEMU 驱动
     */
    fun checkQemuDrivers(): Boolean {
        return try {
            val driversFile = File("/proc/tty/drivers")
            if (!driversFile.exists()) return false

            BufferedReader(FileReader(driversFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line?.contains("goldfish") == true ||
                        line?.contains("qemu") == true
                    ) {
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查主板信息
     */
    fun checkBoard(): Boolean {
        val board = Build.BOARD?.lowercase() ?: return false
        return board == "unknown" ||
                board == "goldfish" ||
                board == "ranchu" ||
                board.contains("generic")
    }

    /**
     * 检查是否为 BlueStacks 模拟器
     */
    fun isBlueStacks(): Boolean {
        return Build.MANUFACTURER?.contains("BlueStacks") == true ||
                Build.MODEL?.contains("BlueStacks") == true ||
                File("/data/app/com.bluestacks.appmart").exists()
    }

    /**
     * 检查是否为 Nox 模拟器
     */
    fun isNox(): Boolean {
        return Build.MANUFACTURER?.contains("nox") == true ||
                Build.MODEL?.contains("nox") == true ||
                File("/data/app/com.bignox.app.store.hd").exists()
    }

    /**
     * 检查是否为 LDPlayer 模拟器
     */
    fun isLDPlayer(): Boolean {
        return Build.MODEL?.contains("ldplayer") == true ||
                Build.MODEL?.contains("ldmnq") == true ||
                File("/data/app/com.ldmnq.launcher").exists()
    }

    /**
     * 检查是否为 MEmu 模拟器
     */
    fun isMEmu(): Boolean {
        return Build.MANUFACTURER?.contains("memu") == true ||
                Build.MODEL?.contains("memu") == true ||
                File("/data/app/com.mumu.launcher").exists()
    }

    /**
     * 检查是否为 Genymotion 模拟器
     */
    fun isGenymotion(): Boolean {
        return Build.MANUFACTURER?.contains("Genymotion") == true ||
                Build.MODEL?.contains("Genymotion") == true ||
                File("/data/app/com.genymotion.genyd").exists()
    }
}
