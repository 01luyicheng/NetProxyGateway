package com.netproxy.gateway.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
                title = { Text("NetProxyGateway") },
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
            Text(
                text = if (uiState.isConnected) "已连接" else "未连接",
                style = MaterialTheme.typography.headlineMedium
            )
            if (uiState.peerId.isNotEmpty()) {
                Text(
                    text = "配对码: ${uiState.peerId}",
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
    var pairingCode by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "配对",
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = pairingCode,
                onValueChange = { pairingCode = it },
                label = { Text("输入识别码") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = { viewModel.pairWithCode(pairingCode) },
                modifier = Modifier.fillMaxWidth(),
                enabled = pairingCode.length >= 6
            ) {
                Text("配对")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = { viewModel.generatePairingCode() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("生成识别码")
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
                text = "远程控制",
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("VPN隧道")
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
                Text("扫描WiFi")
            }
            
            if (uiState.wifiNetworks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "可用网络: ${uiState.wifiNetworks.size}",
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
                Text("断开连接")
            }
        }
    }
}

@Composable
fun NetworkInfoCard(uiState: UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "网络状态",
                style = MaterialTheme.typography.titleMedium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("WiFi:")
                Text(uiState.wifiConnected.toString())
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("蜂窝网络:")
                Text(uiState.cellularConnected.toString())
            }
            
            if (uiState.currentWifiSsid.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("当前WiFi:")
                    Text(uiState.currentWifiSsid)
                }
            }
        }
    }
}
