package com.netproxy.gateway.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.netproxy.gateway.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.netproxy.gateway.R
import com.netproxy.gateway.debug.AppAuditLogStore
import com.netproxy.gateway.debug.AuditLogEntry
import com.netproxy.gateway.debug.AuditLogLevel
import com.netproxy.gateway.debug.DebugSettingsStore
import com.netproxy.gateway.i18n.AppLocale
import com.netproxy.gateway.ui.viewmodel.MainViewModel
import com.netproxy.gateway.ui.viewmodel.UiState
import com.netproxy.gateway.wifi.WifiNetwork
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class MainPage {
    Home,
    Settings,
    AuditLogs
}

internal object MainScreenNavigation {
    val homePageName: String = MainPage.Home.name
    val settingsPageName: String = MainPage.Settings.name
    val auditLogsPageName: String = MainPage.AuditLogs.name

    fun normalizePageName(pageName: String): String = when (pageName) {
        homePageName,
        settingsPageName,
        auditLogsPageName -> pageName
        else -> homePageName
    }

    fun backTargetPageName(currentPageName: String): String = when (normalizePageName(currentPageName)) {
        auditLogsPageName -> settingsPageName
        settingsPageName -> homePageName
        else -> homePageName
    }
}

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
        ConnectionStatusCard(uiState)

        Spacer(modifier = Modifier.height(16.dp))

        if (!uiState.isPaired) {
            PairingSection(uiState, viewModel)
        } else {
            ConnectedOptionsSection(uiState, viewModel)
        }

        Spacer(modifier = Modifier.height(16.dp))

        NetworkInfoCard(uiState)
    }
}

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
                        modifier = Modifier.fillMaxWidth(),
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
                            onCheckedChange = { enabled ->
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

@Composable
fun ConnectionStatusCard(uiState: UiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (uiState.isConnected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val connectionText = if (uiState.isConnected) {
                stringResource(R.string.connected)
            } else {
                stringResource(R.string.disconnected)
            }
            Text(
                text = connectionText,
                style = MaterialTheme.typography.headlineMedium
            )
            if (uiState.peerId.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.pairing_code_format, uiState.peerId),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

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
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = { viewModel.pairWithCode(pairingCode) },
                modifier = Modifier.fillMaxWidth(),
                enabled = pairingCode.length >= 6
            ) {
                Text(stringResource(R.string.pair))
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = { viewModel.generatePairingCode() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.generate_code))
            }
        }
    }
}

@Composable
fun ConnectedOptionsSection(
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.vpn_tunnel))
                Switch(
                    checked = uiState.isVpnEnabled,
                    onCheckedChange = { viewModel.toggleVpn(it) }
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

@Composable
fun NetworkInfoCard(uiState: UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.network_status_title),
                style = MaterialTheme.typography.titleMedium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.wifi_label))
                Text(stringResource(if (uiState.wifiConnected) R.string.network_in_use else R.string.network_not_in_use))
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.cellular_label))
                Text(stringResource(if (uiState.cellularConnected) R.string.network_in_use else R.string.network_not_in_use))
            }
            
            if (uiState.wifiConnected && uiState.currentWifiSsid.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.current_wifi_label))
                    Text(uiState.currentWifiSsid)
                }
            }
        }
    }
}

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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedLanguageTag == option.languageTag,
                        onClick = {
                            val newLanguageTag = option.languageTag
                            if (newLanguageTag == selectedLanguageTag) {
                                return@RadioButton
                            }
                            selectedLanguageTag = newLanguageTag
                            activity?.let {
                                AppLocale.applyLanguage(it, newLanguageTag)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(text = stringResource(option.displayNameResId))
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
