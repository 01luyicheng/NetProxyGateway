package com.netproxy.gateway.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
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
import com.netproxy.gateway.i18n.AppLocale
import com.netproxy.gateway.ui.viewmodel.MainViewModel
import com.netproxy.gateway.ui.viewmodel.UiState
import com.netproxy.gateway.wifi.WifiNetwork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
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

            Spacer(modifier = Modifier.height(16.dp))

            LanguageSettingsCard()
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
