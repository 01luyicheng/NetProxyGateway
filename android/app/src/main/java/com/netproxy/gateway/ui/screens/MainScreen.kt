package com.netproxy.gateway.ui.screens

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

import com.netproxy.gateway.R
import com.netproxy.gateway.debug.AppAuditLogStore
import com.netproxy.gateway.debug.AuditLogEntry
import com.netproxy.gateway.debug.AuditLogLevel
import com.netproxy.gateway.debug.DebugSettingsStore
import com.netproxy.gateway.i18n.AppLocale
import com.netproxy.gateway.ui.theme.LocalStatusColors
import com.netproxy.gateway.ui.viewmodel.MainViewModel
import com.netproxy.gateway.ui.viewmodel.MqttUiState
import com.netproxy.gateway.ui.viewmodel.UiState
import com.netproxy.gateway.vpn.VpnState
import com.netproxy.gateway.wifi.WifiNetwork

private enum class MainPage {
    Home,
    Settings,
    AuditLogs
}

internal object MainScreenNavigation {
    val homePageName: String = MainPage.Home.name
    val settingsPageName: String = MainPage.Settings.name
    val auditLogsPageName: String = MainPage.AuditLogs.name

    /**
     * 将给定页面名规范化为已知页面名之一，未知时回退到主页面名称。
     *
     * @param pageName 要规范化的页面名称。
     * @return 输入的页面名称（若它等于已知的页面名称之一），否则返回 `homePageName`。
     */
    fun normalizePageName(pageName: String): String = when (pageName) {
        homePageName,
        settingsPageName,
        auditLogsPageName -> pageName
        else -> homePageName
    }

    /**
     * 根据当前页面名称选择应导航到的后退目标页面名称。
     *
     * @param currentPageName 当前页面的名称（可能不是已知页面之一）。
     * @return `settingsPageName` 当当前为 `auditLogsPageName`；`homePageName` 当当前为 `settingsPageName` 或输入无法识别时。
     */
    fun backTargetPageName(currentPageName: String): String = when (normalizePageName(currentPageName)) {
        auditLogsPageName -> settingsPageName
        settingsPageName -> homePageName
        else -> homePageName
    }
}

/**
 * 渲染主界面并在 Home / Settings / AuditLogs 三个页面间进行导航与状态管理。
 *
 * 维护当前页面状态并根据页面展示对应内容；在非 Home 页面启用系统返回处理以触发基于 MainScreenNavigation 的后退目标映射；初始化时写入审计日志 `"Main screen initialized"`；在顶部应用栏根据当前页面显示标题、可选的返回按钮及 Home 的设置入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var currentPageName by rememberSaveable { mutableStateOf(MainScreenNavigation.homePageName) }
    val currentPage = remember(currentPageName) {
        MainPage.valueOf(MainScreenNavigation.normalizePageName(currentPageName))
    }
    val navigateBack: () -> Unit = {
        currentPageName = MainScreenNavigation.backTargetPageName(currentPageName)
    }

    BackHandler(enabled = currentPage != MainPage.Home) {
        navigateBack()
    }

    LaunchedEffect(Unit) {
        AppAuditLogStore.info("UI", "Main screen initialized")
    }

    val titleResId = when (currentPage) {
        MainPage.Home -> R.string.app_name
        MainPage.Settings -> R.string.settings_title
        MainPage.AuditLogs -> R.string.audit_logs_title
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleResId)) },
                navigationIcon = {
                    if (currentPage != MainPage.Home) {
                        IconButton(
                            onClick = navigateBack
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_back)
                            )
                        }
                    }
                },
                actions = {
                    if (currentPage == MainPage.Home) {
                        IconButton(
                            onClick = {
                                AppAuditLogStore.info("UI", "Open settings")
                                currentPageName = MainScreenNavigation.settingsPageName
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.open_settings)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        when (currentPage) {
            MainPage.Home -> {
                MainDashboard(
                    uiState = uiState,
                    viewModel = viewModel,
                    paddingValues = paddingValues
                )
            }
            MainPage.Settings -> {
                SettingsScreen(
                    paddingValues = paddingValues,
                    onOpenAuditLogs = {
                        AppAuditLogStore.info("Settings", "Open audit logs")
                        currentPageName = MainScreenNavigation.auditLogsPageName
                    }
                )
            }
            MainPage.AuditLogs -> {
                AuditLogsScreen(paddingValues = paddingValues)
            }
        }
    }
}

/**
 * 显示主仪表板，按顺序呈现连接状态、VPN 状态、配对或已连接选项、网络信息和诊断卡片。
 *
 * @param uiState 当前界面状态，用于决定各卡片的显示内容与状态（例如配对、VPN、网络和心跳信息）。
 * @param viewModel 用于触发用户操作（例如配对、切换 VPN、扫描 Wi‑Fi、断开等）的视图模型。
 * @param paddingValues 外部布局传入的内边距，会应用到仪表板的整体容器上以避免与 Scaffold 等父容器重叠。
 */
@Composable
private fun MainDashboard(
    uiState: UiState,
    viewModel: MainViewModel,
    paddingValues: PaddingValues
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ConnectionStatusCard(
            uiState = uiState,
            onRetry = {
                val code = uiState.peerId
                if (code.isNotEmpty()) {
                    viewModel.pairWithCode(code)
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        VpnStatusCard(uiState)

        Spacer(modifier = Modifier.height(16.dp))

        if (!uiState.isPaired) {
            PairingSection(uiState, viewModel)
        } else {
            ConnectedOptionsSection(uiState, viewModel)
        }

        Spacer(modifier = Modifier.height(16.dp))

        NetworkInfoCard(uiState)

        Spacer(modifier = Modifier.height(16.dp))

        DiagnosticsCard(uiState)
    }
}

/**
 * 显示应用的设置页面，包含语言选择、打开审计日志的入口以及调试区域（可在支持的设备上切换“跳过 MQTT 证书校验”）。
 *
 * 调试开关的更改会通过 DebugSettingsStore 持久化；在设置成功时会向 AppAuditLogStore 写入相应的日志。
 *
 * @param paddingValues Scaffold 或外层布局提供的内边距，用于确保页面内容与系统栏/导航栏等不重叠。
 * @param onOpenAuditLogs 在用户请求查看审计日志时调用的回调。
 */
@Composable
private fun SettingsScreen(
    paddingValues: PaddingValues,
    onOpenAuditLogs: () -> Unit
) {
    val context = LocalContext.current
    var skipMqttCertValidation by rememberSaveable {
        mutableStateOf(DebugSettingsStore.isSkipMqttCertValidationEnabled(context))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LanguageSettingsCard()

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_dev_debug_audit),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onOpenAuditLogs,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_view_logs))
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (DebugSettingsStore.isSkipMqttCertValidationSupported) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = skipMqttCertValidation,
                                role = Role.Switch,
                                onValueChange = { enabled ->
                                    val success = DebugSettingsStore.setSkipMqttCertValidationEnabled(context, enabled)
                                    if (success) {
                                        skipMqttCertValidation = enabled
                                        if (enabled) {
                                            AppAuditLogStore.warn(
                                                "Settings",
                                                "MQTT certificate validation disabled (debug only)"
                                            )
                                        } else {
                                            AppAuditLogStore.info(
                                                "Settings",
                                                "MQTT certificate validation enabled"
                                            )
                                        }
                                    }
                                }
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_skip_mqtt_cert_validation),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.settings_skip_mqtt_cert_validation_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Switch(
                            checked = skipMqttCertValidation,
                            onCheckedChange = null
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.settings_debug_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 显示应用审计日志界面，提供“清空日志”按钮并按时间倒序列出日志条目。
 *
 * @param paddingValues 来自 Scaffold 的内边距，用于确保内容与系统栏或导航栏不重叠。 
 */
@Composable
private fun AuditLogsScreen(
    paddingValues: PaddingValues
) {
    val entries by AppAuditLogStore.entries.collectAsState()
    val visibleEntries = remember(entries) { entries.asReversed() }
    val formatter = remember {
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp)
    ) {
        OutlinedButton(
            onClick = {
                AppAuditLogStore.clear()
            }
        ) {
            Text(stringResource(R.string.clear_logs))
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (visibleEntries.isEmpty()) {
            Text(
                text = stringResource(R.string.audit_logs_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = visibleEntries,
                    key = { entry -> entry.id }
                ) { entry ->
                    AuditLogEntryCard(entry = entry, formatter = formatter)
                }
            }
        }
    }
}

/**
 * 将单条审计日志条目渲染为带时间、日志级别与标签的卡片行。
 *
 * @param entry 要显示的审计日志条目，包含时间戳、级别、标签与消息内容。
 * @param formatter 用于格式化条目时间戳的 `DateTimeFormatter`，用于在卡片顶部显示人类可读的时间。
 */
@Composable
private fun AuditLogEntryCard(
    entry: AuditLogEntry,
    formatter: DateTimeFormatter
) {
    val containerColor = when (entry.level) {
        AuditLogLevel.INFO -> MaterialTheme.colorScheme.surfaceVariant
        AuditLogLevel.WARN -> MaterialTheme.colorScheme.tertiaryContainer
        AuditLogLevel.ERROR -> MaterialTheme.colorScheme.errorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${formatter.format(Instant.ofEpochMilli(entry.timestampMs))} [${entry.level}] ${entry.tag}",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * 显示 MQTT 连接状态的卡片，呈现状态图标/指示、配对码、错误信息并在需要时提供重试按钮。
 *
 * 根据传入的 uiState 展示连接状态文本与配色；当存在配对码时显示配对码；当处于错误且包含错误信息时显示该信息并提供调用 `onRetry` 的重试按钮。
 *
 * @param uiState 当前的界面状态（用于读取 MQTT 状态、peerId 和 mqttErrorMessage 等字段）。
 * @param onRetry 点击“重试”按钮时调用的回调。
 */
@Composable
private fun ConnectionStatusCard(uiState: UiState, onRetry: () -> Unit) {
    val statusColors = LocalStatusColors.current
    val statusData = when (uiState.mqttState) {
        MqttUiState.Connected ->
            StatusCardData(
                containerColor = statusColors.success,
                contentColor = statusColors.onSuccess,
                textRes = R.string.status_connected,
                icon = Icons.Default.CheckCircle
            )
        MqttUiState.Connecting ->
            StatusCardData(
                containerColor = statusColors.warning,
                contentColor = statusColors.onWarning,
                textRes = R.string.status_connecting,
                icon = null
            )
        MqttUiState.Error ->
            StatusCardData(
                containerColor = statusColors.error,
                contentColor = statusColors.onError,
                textRes = R.string.status_error,
                icon = Icons.Default.Error
            )
        MqttUiState.Disconnected ->
            StatusCardData(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                textRes = R.string.status_disconnected,
                icon = Icons.Default.CloudOff
            )
    }
    val containerColor = statusData.containerColor
    val contentColor = statusData.contentColor
    val statusTextRes = statusData.textRes
    val statusIcon = statusData.icon

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.mqttState == MqttUiState.Connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = contentColor,
                        strokeWidth = 2.dp
                    )
                } else if (statusIcon != null) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = contentColor
                    )
                }
                Text(
                    text = stringResource(statusTextRes),
                    style = MaterialTheme.typography.headlineSmall,
                    color = contentColor
                )
            }

            if (uiState.peerId.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.pairing_code_format, uiState.peerId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
            }

            if (uiState.mqttState == MqttUiState.Error && uiState.mqttErrorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.mqttErrorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor)
                ) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

/**
 * 显示配对输入与操作区域，允许输入配对码并发起配对或生成配对码。
 *
 * 该组件基于传入的 UI 状态控制控件可用性：配对进行中时禁用输入与按钮并展示进度指示；当输入的配对码长度至少 6 字符且未在配对中时，主按钮可用以调用配对操作。
 *
 * @param uiState 当前界面状态，用于决定控件启用/禁用、是否显示配对进行中的指示等。
 * @param viewModel 用于触发配对相关操作（pairWithCode 和 generatePairingCode）。
 */
@Composable
fun PairingSection(
    uiState: UiState,
    viewModel: MainViewModel
) {
    var pairingCode by rememberSaveable { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.pairing_title),
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = pairingCode,
                onValueChange = { pairingCode = it },
                label = { Text(stringResource(R.string.pairing_code_input_label)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isPairingInProgress
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.pairWithCode(pairingCode) },
                modifier = Modifier.fillMaxWidth(),
                enabled = pairingCode.length >= 6 && !uiState.isPairingInProgress
            ) {
                if (uiState.isPairingInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.pairing_in_progress))
                } else {
                    Text(stringResource(R.string.pair))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.generatePairingCode() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isPairingInProgress
            ) {
                Text(stringResource(R.string.generate_code))
            }
        }
    }
}

/**
 * 显示远程控制相关的操作卡片：VPN 开关、扫描 Wi‑Fi 按钮、可用网络计数和断开连接按钮。
 *
 * 卡片 UI 的呈现由 `uiState` 驱动；用户交互会调用 `viewModel` 上的相应方法（切换 VPN 调用 `toggleVpn`、点击“扫描 Wi‑Fi”调用 `scanWifi`、点击“断开连接”调用 `disconnect`）。
 *
 * @param uiState 当前界面状态，用于决定开关、可用网络计数等显示内容。
 * @param viewModel 主视图模型，用于处理用户触发的操作回调。
 */
@Composable
private fun ConnectedOptionsSection(
    uiState: UiState,
    viewModel: MainViewModel
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.remote_control),
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = uiState.isVpnEnabled,
                        role = Role.Switch,
                        onValueChange = { viewModel.toggleVpn(it) }
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.vpn_tunnel))
                Switch(
                    checked = uiState.isVpnEnabled,
                    onCheckedChange = null
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { viewModel.scanWifi() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.scan_wifi))
            }

            if (uiState.wifiNetworks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.available_networks_format, uiState.wifiNetworks.size),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.disconnect() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.disconnect))
            }
        }
    }
}

private data class StatusCardData(
    val containerColor: Color,
    val contentColor: Color,
    val textRes: Int,
    val icon: ImageVector?
)

/**
 * 显示一个用于展示 VPN 运行状态的卡片，并根据传入的 UI 状态调整文字、图标、配色和右侧指示器。
 *
 * @param uiState 当前界面状态，用于确定 VPN 是否启用以选择相应的文本、颜色和指示器颜色。
 */
@Composable
private fun VpnStatusCard(uiState: UiState) {
    val statusColors = LocalStatusColors.current
    val vpnData = if (uiState.isVpnEnabled) {
        StatusCardData(
            containerColor = statusColors.success,
            contentColor = statusColors.onSuccess,
            textRes = R.string.vpn_running,
            icon = Icons.Default.VpnKey
        )
    } else {
        StatusCardData(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            textRes = R.string.vpn_stopped,
            icon = Icons.Default.VpnKey
        )
    }
    val containerColor = vpnData.containerColor
    val contentColor = vpnData.contentColor
    val statusTextRes = vpnData.textRes
    val icon = vpnData.icon

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = contentColor
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.vpn_tunnel),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor
                )
                Text(
                    text = stringResource(statusTextRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
            }
            Box(
                modifier = Modifier.size(12.dp),
                contentAlignment = Alignment.Center
            ) {
                val indicatorColor = if (uiState.isVpnEnabled) statusColors.success else MaterialTheme.colorScheme.outline
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(color = indicatorColor)
                }
            }
        }
    }
}

/**
 * 展示网络状态卡片，包含 Wi‑Fi 与蜂窝连接的状态指示及（在已连接且可用时）当前 Wi‑Fi 的 SSID。
 *
 * @param uiState 包含网络相关显示数据的 UI 状态对象；本组件使用其中的 `wifiConnected`、`cellularConnected` 与 `currentWifiSsid` 字段来决定各项显示内容。
 */
@Composable
private fun NetworkInfoCard(uiState: UiState) {
    val statusColors = LocalStatusColors.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.network_status_title),
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            NetworkStatusRow(
                label = stringResource(R.string.wifi_label),
                active = uiState.wifiConnected,
                activeIcon = Icons.Default.Wifi,
                inactiveIcon = Icons.Default.WifiOff,
                activeColor = statusColors.success,
                inactiveColor = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(8.dp))

            NetworkStatusRow(
                label = stringResource(R.string.cellular_label),
                active = uiState.cellularConnected,
                activeIcon = Icons.Default.SignalCellularAlt,
                inactiveIcon = Icons.Default.SignalCellularOff,
                activeColor = statusColors.success,
                inactiveColor = MaterialTheme.colorScheme.outline
            )

            if (uiState.wifiConnected && uiState.currentWifiSsid.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Router,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.current_wifi_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = uiState.currentWifiSsid,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * 在一行中展示网络项的状态：图标、标签与“在用/未在用”文本。
 *
 * @param label 显示的文本标签（例如 "Wi‑Fi" 或 "蜂窝"）。
 * @param active 若为 `true` 则表示该网络正在使用，否则表示未使用。
 * @param activeIcon 处于激活状态时显示的图标。
 * @param inactiveIcon 处于未激活状态时显示的图标。
 * @param activeColor 激活状态下用于图标和文本的颜色。
 * @param inactiveColor 未激活状态下用于图标和文本的颜色。
 */
@Composable
private fun NetworkStatusRow(
    label: String,
    active: Boolean,
    activeIcon: androidx.compose.ui.graphics.vector.ImageVector,
    inactiveIcon: androidx.compose.ui.graphics.vector.ImageVector,
    activeColor: Color,
    inactiveColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (active) activeIcon else inactiveIcon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (active) activeColor else inactiveColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(if (active) R.string.network_in_use else R.string.network_not_in_use),
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) activeColor else inactiveColor
        )
    }
}

/**
 * 展开式显示连接与运行时诊断信息卡片，并根据传入的 UI 状态实时展示各项指标。
 *
 * 当卡片展开时会显示：连接时长、距上次心跳的秒数（在有心跳时间时每秒更新）、心跳失败次数、重连次数、VPN 详细状态（及已连接客户端数）和网络校验状态；若存在 VPN 错误信息则另外展示错误文本。
 *
 * @param uiState 用于驱动诊断显示的界面状态，包含 connectionDurationMs、lastHeartbeatTimeMs、heartbeatFailures、reconnectCount、vpnDetailedStatus 和 networkIsValidated 等字段。
 */
@Composable
private fun DiagnosticsCard(uiState: UiState) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    // 使用独立定时器每秒更新心跳相对时间，避免每次重组都调用 System.currentTimeMillis()
    var heartbeatAgoSec by remember { mutableLongStateOf(0L) }
    LaunchedEffect(uiState.lastHeartbeatTimeMs) {
        heartbeatAgoSec = if (uiState.lastHeartbeatTimeMs > 0) {
            (System.currentTimeMillis() - uiState.lastHeartbeatTimeMs) / 1000
        } else 0L
        while (uiState.lastHeartbeatTimeMs > 0) {
            kotlinx.coroutines.delay(1000)
            heartbeatAgoSec = (System.currentTimeMillis() - uiState.lastHeartbeatTimeMs) / 1000
        }
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = expanded,
                        role = Role.Button,
                        onValueChange = { expanded = it }
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))

                val durationSec = uiState.connectionDurationMs / 1000
                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_connection_duration),
                    value = if (durationSec > 0) "$durationSec s" else "-"
                )

                Spacer(modifier = Modifier.height(8.dp))

                val lastHeartbeatAgo = if (uiState.lastHeartbeatTimeMs > 0 && heartbeatAgoSec >= 0) {
                    stringResource(R.string.diagnostics_time_ago_seconds, heartbeatAgoSec)
                } else "-"
                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_last_heartbeat),
                    value = lastHeartbeatAgo
                )

                Spacer(modifier = Modifier.height(8.dp))

                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_heartbeat_failures),
                    value = uiState.heartbeatFailures.toString()
                )

                Spacer(modifier = Modifier.height(8.dp))

                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_reconnect_count),
                    value = uiState.reconnectCount.toString()
                )

                Spacer(modifier = Modifier.height(8.dp))

                val vpnStateText = when (uiState.vpnDetailedStatus.state) {
                    VpnState.STOPPED -> stringResource(R.string.vpn_stopped)
                    VpnState.STARTING -> stringResource(R.string.status_connecting)
                    VpnState.RUNNING -> stringResource(R.string.vpn_running)
                    VpnState.STOPPING -> stringResource(R.string.status_connecting)
                    VpnState.ERROR -> stringResource(R.string.status_error)
                }
                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_vpn_state),
                    value = vpnStateText +
                        if (uiState.vpnDetailedStatus.connectedClients > 0) " (${uiState.vpnDetailedStatus.connectedClients})" else ""
                )

                if (uiState.vpnDetailedStatus.errorMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = uiState.vpnDetailedStatus.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_network_validated),
                    value = stringResource(
                        if (uiState.networkIsValidated) R.string.network_in_use else R.string.network_not_in_use
                    )
                )
            }
        }
    }
}

/**
 * 在一行中左右对齐显示诊断项的标签和对应值，用于展示键-值类型的信息。
 *
 * @param label 左侧显示的标签文本（描述项名）。
 * @param value 右侧显示的值文本（描述项的当前值）。
 */
@Composable
private fun DiagnosticsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * 显示用于选择应用语言的卡片，列出 AppLocale.supportedLanguages 并允许用户切换语言。
 *
 * 选中某项后会将选择保存在可保存状态中，并在能获取到宿主 Activity 时立即通过 AppLocale.applyLanguage 应用新语言；如果选择未改变则不作处理。
 */
@Composable
fun LanguageSettingsCard() {
    val context = LocalContext.current
    val activity = context.findActivity()
    val initialLanguageTag = remember { AppLocale.getSelectedLanguageTag(context) }
    var selectedLanguageTag by rememberSaveable { mutableStateOf(initialLanguageTag) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.app_language_title),
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            AppLocale.supportedLanguages.forEach { option ->
                val isSelected = selectedLanguageTag == option.languageTag
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = {
                                val newLanguageTag = option.languageTag
                                if (newLanguageTag == selectedLanguageTag) {
                                    return@selectable
                                }
                                selectedLanguageTag = newLanguageTag
                                activity?.let {
                                    AppLocale.applyLanguage(it, newLanguageTag)
                                }
                            }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(text = stringResource(option.displayNameResId))
                }
            }
        }
    }
}

/**
 * 在 Context 链中查找并返回最近的 Activity 实例。
 *
 * @return 最近的 `Activity`，如果未找到则为 `null`。
 */
private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
