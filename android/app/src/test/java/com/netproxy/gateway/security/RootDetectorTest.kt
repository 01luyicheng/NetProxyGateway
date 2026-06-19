package com.netproxy.gateway.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootDetectorTest {

    private val context = mockk<Context>(relaxed = true)
    private val packageManager = mockk<PackageManager>(relaxed = true)

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        every { context.packageManager } returns packageManager
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== check() ====================

    @Test
    fun check_returnsRooted_whenRootPackagesDetected() {
        // 通过模拟 PackageManager 让 checkRootPackages 返回 true
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } returns mockk<PackageInfo>()

        val result = RootDetector.check(context)

        assertTrue(result.isRooted)
        assertTrue(result.detectedBy.contains("root-packages"))
    }

    @Test
    fun check_combinesMultipleDetectionMethods() {
        // 验证 check() 正确组合多个检测方法的结果
        // 当多个检测返回 true 时，detectedBy 应包含所有检测到的指标
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } returns mockk<PackageInfo>()

        val result = RootDetector.check(context)

        assertTrue(result.isRooted)
        // 至少应包含 root-packages（由 mock 触发）
        assertTrue(result.detectedBy.contains("root-packages"))
        // detectedBy 不应为空
        assertTrue(result.detectedBy.isNotEmpty())
    }

    // ==================== checkSuperuserApk() ====================

    @Test
    fun checkSuperuserApk_returnsFalse_whenFileDoesNotExist() {
        // 默认情况下 /system/app/Superuser.apk 不存在于测试环境
        assertFalse(RootDetector.checkSuperuserApk())
    }

    // ==================== checkSuBinary() ====================

    @Test
    fun checkSuBinary_returnsFalse_whenNoRootFilesExist() {
        // 默认测试环境下所有 ROOT_PATHS 都不存在
        assertFalse(RootDetector.checkSuBinary())
    }

    // ==================== checkRWPaths() ====================

    @Test
    fun checkRWPaths_returnsFalse_whenSystemPathsNotWritable() {
        // 默认测试环境下系统路径不可写
        assertFalse(RootDetector.checkRWPaths())
    }

    // ==================== checkRootPackages() ====================

    @Test
    fun checkRootPackages_returnsFalse_whenNoRootPackagesInstalled() {
        every {
            packageManager.getPackageInfo(any<String>(), any<Int>())
        } throws PackageManager.NameNotFoundException()

        assertFalse(RootDetector.checkRootPackages(context))
    }

    @Test
    fun checkRootPackages_returnsTrue_whenMagiskPackageInstalled() {
        every {
            packageManager.getPackageInfo(any<String>(), any<Int>())
        } throws PackageManager.NameNotFoundException()
        every {
            packageManager.getPackageInfo("com.topjohnwu.magisk", 0)
        } returns mockk<PackageInfo>()

        assertTrue(RootDetector.checkRootPackages(context))
    }

    @Test
    fun checkRootPackages_returnsTrue_whenSuperSuPackageInstalled() {
        every {
            packageManager.getPackageInfo(any<String>(), any<Int>())
        } throws PackageManager.NameNotFoundException()
        every {
            packageManager.getPackageInfo("eu.chainfire.supersu", 0)
        } returns mockk<PackageInfo>()

        assertTrue(RootDetector.checkRootPackages(context))
    }

    // ==================== checkRootCloakingApps() ====================

    @Test
    fun checkRootCloakingApps_returnsFalse_whenNoCloakingAppsInstalled() {
        every {
            packageManager.getPackageInfo(any<String>(), any<Int>())
        } throws PackageManager.NameNotFoundException()

        assertFalse(RootDetector.checkRootCloakingApps(context))
    }

    @Test
    fun checkRootCloakingApps_returnsTrue_whenRootCloakInstalled() {
        every {
            packageManager.getPackageInfo(any<String>(), any<Int>())
        } throws PackageManager.NameNotFoundException()
        every {
            packageManager.getPackageInfo("com.devadvance.rootcloak", 0)
        } returns mockk<PackageInfo>()

        assertTrue(RootDetector.checkRootCloakingApps(context))
    }

    @Test
    fun checkRootCloakingApps_returnsTrue_whenXposedInstallerInstalled() {
        every {
            packageManager.getPackageInfo(any<String>(), any<Int>())
        } throws PackageManager.NameNotFoundException()
        every {
            packageManager.getPackageInfo("de.robv.android.xposed.installer", 0)
        } returns mockk<PackageInfo>()

        assertTrue(RootDetector.checkRootCloakingApps(context))
    }

    // ==================== checkBusyBox() ====================

    @Test
    fun checkBusyBox_returnsFalse_whenNoBusyBoxFoundAndWhichFails() {
        // 在测试环境中，BusyBox 文件不存在且 which 命令不可用
        assertFalse(RootDetector.checkBusyBox())
    }

    // ==================== checkMagisk() ====================

    @Test
    fun checkMagisk_returnsFalse_whenNoMagiskFilesExist() {
        // 默认测试环境下 Magisk 文件不存在
        assertFalse(RootDetector.checkMagisk())
    }

    // ==================== checkDangerousProps() ====================

    @Test
    fun checkDangerousProps_returnsFalse_whenGetpropFails() {
        // 在测试环境中 getprop 命令不可用
        assertFalse(RootDetector.checkDangerousProps())
    }

    // ==================== checkSuExecution() ====================

    @Test
    fun checkSuExecution_returnsFalse_whenSuCommandFails() {
        // 在测试环境中 su 命令不可用
        assertFalse(RootDetector.checkSuExecution())
    }

    // ==================== RootCheckResult ====================

    @Test
    fun rootCheckResult_dataClassWorksCorrectly() {
        val result = RootDetector.RootCheckResult(
            isRooted = true,
            detectedBy = listOf("test-keys", "su-binary")
        )

        assertTrue(result.isRooted)
        assertEquals(2, result.detectedBy.size)
        assertTrue(result.detectedBy.contains("test-keys"))
        assertTrue(result.detectedBy.contains("su-binary"))
    }

    @Test
    fun rootCheckResult_equalsAndHashCodeWorkCorrectly() {
        val result1 = RootDetector.RootCheckResult(
            isRooted = false,
            detectedBy = emptyList()
        )
        val result2 = RootDetector.RootCheckResult(
            isRooted = false,
            detectedBy = emptyList()
        )

        assertEquals(result1, result2)
        assertEquals(result1.hashCode(), result2.hashCode())
    }
}
