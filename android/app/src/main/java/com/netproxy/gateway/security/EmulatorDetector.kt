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
     * 执行多指标模拟器检测并汇总检测到的证据。
     *
     * 使用多种独立检测器（如 Build 字段匹配、特定文件存在、getprop 属性、已知模拟器包、/proc 信息等），
     * 将每项命中的检测方法记录下来；当命中指标数大于等于 2 时判定为模拟器，以降低单一弱指标的误报风险。
     *
     * @param context Android 上下文，用于执行需要 PackageManager 或系统服务的检查。
     * @return `EmulatorCheckResult`，包含 `isEmulator`（当命中指标数 ≥ 2 时为 `true`）和 `detectedBy`（记录命中检测方法标识的列表）。
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
     * 基于 Build.HARDWARE 字段判断设备是否匹配已知的模拟器硬件标识。
     *
     * @return `true` 如果 `Build.HARDWARE` 包含任一已知模拟器硬件标识，`false` 否则。
     */
    fun checkHardware(): Boolean {
        val hardware = Build.HARDWARE?.lowercase() ?: return false
        return EMULATOR_HARDWARE.any { hardware.contains(it.lowercase()) }
    }

    /**
     * 判断当前设备的 Build.PRODUCT 是否匹配已知模拟器产品标识。
     *
     * @return `true` 当 Build.PRODUCT（小写）包含任一已知模拟器产品标识，`false` 否则或 Build.PRODUCT 为空。
     */
    fun checkProduct(): Boolean {
        val product = Build.PRODUCT?.lowercase() ?: return false
        return EMULATOR_PRODUCTS.any { product.contains(it.lowercase()) }
    }

    /**
     * 判断设备制造商是否匹配已知的模拟器制造商标识。
     *
     * @return `true` 如果 `Build.MANUFACTURER` 包含任一已知模拟器制造商标识，`false` 否则。
     */
    fun checkManufacturer(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: return false
        return EMULATOR_MANUFACTURERS.any { manufacturer.contains(it.lowercase()) }
    }

    /**
     * 基于 Build.DEVICE 字段判断设备是否匹配常见模拟器设备标识。
     *
     * @return `true` 如果 `Build.DEVICE` 字段包含任一已知模拟器标识，`false` 否则。
     */
    fun checkDevice(): Boolean {
        val device = Build.DEVICE?.lowercase() ?: return false
        return EMULATOR_DEVICES.any { device.contains(it.lowercase()) }
    }

    /**
     * 确定设备指纹是否包含常见的模拟器特征。
     *
     * @return `true` 如果 `Build.FINGERPRINT` 以 "generic" 开头或包含 "test-keys"、"sdk"、"emulator"，否则 `false`。
     */
    fun checkFingerprint(): Boolean {
        val fingerprint = Build.FINGERPRINT?.lowercase() ?: return false
        return fingerprint.startsWith("generic") ||
                fingerprint.contains("test-keys") ||
                fingerprint.contains("sdk") ||
                fingerprint.contains("emulator")
    }

    /**
     * 检查设备文件系统中是否存在预定义的、指示模拟器运行环境的文件路径。
     *
     * 遍历内部的 `EMULATOR_FILES` 列表，若任意路径对应的文件存在则视为命中。
     *
     * @return `true` 如果检测到任一已知模拟器文件存在，`false` 否则。
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
     * 检查一组预定义的系统属性（getprop），判断设备是否显示出模拟器相关的属性特征。
     *
     * 对于每个配置的属性键，若配置的期望值为 null 则只要该属性存在且非空即视为命中；否则当属性值与期望值（不区分大小写）完全相等时视为命中。只要任一属性命中则返回命中结果。
     *
     * @return `true` 如果任一配置的系统属性存在或与其期望值匹配，`false` 否则。
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
                    process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            } catch (e: Exception) {
                // 忽略异常
            }
        }
        return false
    }

    /**
     * 检测设备上是否安装了已知的模拟器应用包。
     *
     * @return `true` 如果在设备上发现任一已知的模拟器包名，`false` 否则。
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
     * 检测 /proc/cpuinfo 中是否存在表明运行在虚拟化或模拟器环境的标识符。
     *
     * @return `true` 如果在 /proc/cpuinfo 中找到虚拟化或模拟器相关关键词（例如 `hypervisor`、`qemu`、`goldfish` 等），`false` 否则。
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
     * 检查设备的主叫号码是否为已知的模拟器默认号码。
     *
     * 当缺少 `READ_PHONE_STATE` 权限、读取发生异常或号码为空时返回 `false`。注意：在 Android 10+ 上 `line1Number` 常为空或不可用，因此该检查可能不可靠。
     *
     * @return `true` 如果号码匹配已知模拟器默认号码，`false` 否则（权限缺失或出错时也为 `false`）。
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

    /**
     * 判断给定电话号码是否为已知的模拟器占位号码。
     *
     * @param phoneNumber 要检查的电话号码，可能为 `null` 或空字符串。
     * @return `true` 如果电话号码在已知模拟器号码集合中，`false` 否则。
     */
    internal fun isKnownEmulatorPhoneNumber(phoneNumber: String?): Boolean {
        if (phoneNumber.isNullOrBlank()) {
            return false
        }
        return EMULATOR_PHONE_NUMBERS.contains(phoneNumber)
    }

    /**
     * 检查设备 ID 是否指示模拟器。
     *
     * 在 Android 10+ getDeviceId 可能受限且真实设备也可能返回空或占位值；该方法未纳入主检测聚合（check）。
     *
     * @param context 用于获取 TelephonyManager 的 Android 上下文（需要 READ_PHONE_STATE 权限）。
     * @return `true` 如果设备 ID 为空或等于 `"000000000000000"` 或 `"012345678912345"`，`false` 否则；当缺少权限或发生异常时也返回 `false`。
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
     * 检查设备 IMEI 是否为已知的模拟器或占位值。
     *
     * 使用 Context 获取 TelephonyManager 并在有 READ_PHONE_STATE 权限时读取 IMEI（或在较旧 SDK 上的 deviceId）。
     *
     * @param context 用于访问 TelephonyManager 和权限检查的 Android Context。
     * @return `true` 如果 IMEI 为空、等于常见占位值（"000000000000000", "012345678912345", "00000000000000"）或以 `"0000000000"` 开头；`false` 如果 IMEI 看起来有效，或在缺少权限或发生错误时。
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

    /**
     * 检查应用是否被授予 `READ_PHONE_STATE` 权限。
     *
     * @param context 用于执行权限检查的 Android `Context`。
     * @return `true` 如果权限已被授予，`false` 否则。
     */
    private fun hasReadPhoneStatePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查 /proc/tty/drivers 中是否存在 QEMU 或 goldfish 驱动标识。
     *
     * @return `true` 如果文件存在且包含 "goldfish" 或 "qemu" 字样，`false` 否则。
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
     * 判断设备主板标识是否匹配常见模拟器的板载标识。
     *
     * @return `true` if the board equals "unknown", "goldfish", or "ranchu", or contains "generic"; `false` otherwise.
     */
    fun checkBoard(): Boolean {
        val board = Build.BOARD?.lowercase() ?: return false
        return board == "unknown" ||
                board == "goldfish" ||
                board == "ranchu" ||
                board.contains("generic")
    }

    /**
     * 判断运行环境是否为 BlueStacks 模拟器。
     *
     * @return `true` 如果制造商或型号字符串包含 "BlueStacks"，或存在 BlueStacks 应用目录 `/data/app/com.bluestacks.appmart`，`false` 否则。
     */
    fun isBlueStacks(): Boolean {
        return Build.MANUFACTURER?.contains("BlueStacks") == true ||
                Build.MODEL?.contains("BlueStacks") == true ||
                File("/data/app/com.bluestacks.appmart").exists()
    }

    /**
     * 判断设备是否为 Nox 模拟器。
     *
     * @return `true` 如果设备很可能是 Nox 模拟器，`false` 否则。
     */
    fun isNox(): Boolean {
        return Build.MANUFACTURER?.contains("nox") == true ||
                Build.MODEL?.contains("nox") == true ||
                File("/data/app/com.bignox.app.store.hd").exists()
    }

    /**
     * 判断当前设备是否运行 LDPlayer 模拟器。
     *
     * @return `true` 如果检测到 LDPlayer 的设备模型或特定安装路径，`false` 否则。
     */
    fun isLDPlayer(): Boolean {
        return Build.MODEL?.contains("ldplayer") == true ||
                Build.MODEL?.contains("ldmnq") == true ||
                File("/data/app/com.ldmnq.launcher").exists()
    }

    /**
     * 判断设备是否为 MEmu 模拟器。
     *
     * @return `true` 如果设备被识别为 MEmu 模拟器，`false` 否则。
     */
    fun isMEmu(): Boolean {
        return Build.MANUFACTURER?.contains("memu") == true ||
                Build.MODEL?.contains("memu") == true ||
                File("/data/app/com.mumu.launcher").exists()
    }

    /**
     * 确定设备是否属于 Genymotion 模拟器。
     *
     * @return `true` 如果设备被判定为 Genymotion 模拟器，`false` 否则。
     */
    fun isGenymotion(): Boolean {
        return Build.MANUFACTURER?.contains("Genymotion") == true ||
                Build.MODEL?.contains("Genymotion") == true ||
                File("/data/app/com.genymotion.genyd").exists()
    }
}
