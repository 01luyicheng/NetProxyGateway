# Task 3E: UI Module - 用户界面

> **任务级别**: 集成任务  
> **前置依赖**: Task 2, Task 3A, Task 3B, Task 3C, Task 3D (所有模块)  
> **后续任务**: Task 4 (服务端，可并行)  
> **预计工作量**: 4 小时

## 任务目标

整合所有模块，创建完整的 Jetpack Compose 用户界面。

## 交付物

1. `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt`
2. `android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt`
3. `android/app/src/main/java/com/netproxy/gateway/ui/components/` (UI 组件)

## 详细步骤

### Step 1: 创建 MainViewModel

创建 `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt`:

```kotlin
package com.netproxy.gateway.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netproxy.gateway.connection.MqttConnectionManager
import com.netproxy.gateway.connection.MqttConnectionState
import com.netproxy.gateway.connection.NetworkStateManager
import com.netproxy.gateway.connection.NetworkType
import com.netproxy.gateway.wifi.WifiManager
import com.netproxy.gateway.wifi.WifiNetwork
import com.netproxy.gateway.vpn.VpnManager
import com.netproxy.gateway.vpn.VpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class UiState(
    val isConnected: Boolean = false,
    val isPaired: Boolean = false,
    val peerId: String = "",
    val deviceId: String = "",
    val authToken: String = "",
    val isVpnEnabled: Boolean = false,
    val vpnPrepared: Boolean = false,
    val wifiConnected: Boolean = false,
    val cellularConnected: Boolean = false,
    val currentWifiSsid: String = "",
    val wifiNetworks: List<WifiNetwork> = emptyList(),
    val errorMessage: String? = null,
    val isLoading: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkStateManager: NetworkStateManager,
    private val mqttConnectionManager: MqttConnectionManager,
    private val wifiManager: WifiManager,
    private val vpnManager: VpnManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // Generate unique device ID
        val deviceId = UUID.randomUUID().toString()
        _uiState.value = _uiState.value.copy(deviceId = deviceId)
        
        // Check VPN preparation status
        checkVpnPrepared()
        
        // Start network monitoring
        observeNetworkState()
        observeMqttState()
    }

    private fun checkVpnPrepared() {
        val prepared = vpnManager.isVpnPrepared()
        _uiState.value = _uiState.value.copy(vpnPrepared = prepared)
    }

    private fun observeNetworkState() {
        viewModelScope.launch {
            networkStateManager.networkState.collect { networkState ->
                _uiState.value = _uiState.value.copy(
                    wifiConnected = networkState.networkType == NetworkType.Wifi,
                    cellularConnected = networkState.networkType == NetworkType.Cellular
                )
                
                // Get current WiFi info
                wifiManager.getCurrentConnection()?.let { info ->
                    _uiState.value = _uiState.value.copy(
                        currentWifiSsid = info.ssid ?: ""
                    )
                }
            }
        }
    }

    private fun observeMqttState() {
        viewModelScope.launch {
            mqttConnectionManager.connectionState.collect { state ->
                when (state) {
                    is MqttConnectionState.Connected -> {
                        _uiState.value = _uiState.value.copy(isConnected = true)
                    }
                    is MqttConnectionState.Disconnected -> {
                        _uiState.value = _uiState.value.copy(isConnected = false)
                    }
                    is MqttConnectionState.Error -> {
                        _uiState.value = _uiState.value.copy(
                            errorMessage = state.message,
                            isConnected = false
                        )
                    }
                    else -> {}
                }
            }
        }
    }

    fun generatePairingCode() {
        viewModelScope.launch {
            val code = (100000..999999).random().toString()
            _uiState.value = _uiState.value.copy(peerId = code)
        }
    }

    fun pairWithCode(code: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                peerId = code,
                isLoading = true
            )
            
            // Connect via MQTT (using cellular)
            if (networkStateManager.isCellularConnected()) {
                mqttConnectionManager.connect(
                    deviceId = _uiState.value.deviceId,
                    authToken = code
                )
                _uiState.value = _uiState.value.copy(
                    isPaired = true,
                    isLoading = false
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "需要蜂窝网络连接",
                    isLoading = false
                )
            }
        }
    }

    fun toggleVpn(enable: Boolean) {
        if (enable) {
            // Check if VPN is prepared first
            if (!vpnManager.isVpnPrepared()) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "需要VPN授权"
                )
                return
            }
            
            vpnManager.startVpn()
            _uiState.value = _uiState.value.copy(isVpnEnabled = true)
        } else {
            vpnManager.stopVpn()
            _uiState.value = _uiState.value.copy(isVpnEnabled = false)
        }
    }

    fun requestVpnPermission() {
        // This should be called from Activity context
        // Returns Intent for VPN permission request
    }

    fun scanWifi() {
        viewModelScope.launch {
            wifiManager.startScan()
            
            // Wait a bit for scan to complete
            kotlinx.coroutines.delay(2000)
            
            val results = wifiManager.getScanResults()
            _uiState.value = _uiState.value.copy(wifiNetworks = results)
        }
    }

    fun connectToWifi(ssid: String, password: String, securityType: com.netproxy.gateway.wifi.WifiSecurityType) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            val success = wifiManager.connectToNetwork(ssid, password, securityType)
            
            if (!success) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "WiFi连接失败",
                    isLoading = false
                )
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun disconnect() {
        mqttConnectionManager.disconnect()
        toggleVpn(false)
        _uiState.value = _uiState.value.copy(
            isConnected = false,
            isPaired = false,
            peerId = ""
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
```

### Step 2: 创建 MainScreen

创建 `android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt`:

```kotlin
package com.netproxy.gateway.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.netproxy.gateway.ui.viewmodel.MainViewModel

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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection Status Card
            item {
                ConnectionStatusCard(uiState)
            }
            
            // Pairing Section or Connected Options
            item {
                if (!uiState.isPaired) {
                    PairingSection(viewModel)
                } else {
                    ConnectedOptionsSection(viewModel, uiState)
                }
            }
            
            // Network Info
            item {
                NetworkInfoCard(uiState)
            }
            
            // Error Message
            uiState.errorMessage?.let { error ->
                item {
                    ErrorCard(error) {
                        viewModel.clearError()
                    }
                }
            }
            
            // Loading Indicator
            if (uiState.isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(uiState: com.netproxy.gateway.ui.viewmodel.UiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (uiState.isConnected && uiState.isPaired)
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
                text = when {
                    uiState.isConnected && uiState.isPaired -> "已连接"
                    uiState.isConnected -> "已配对"
                    else -> "未连接"
                },
                style = MaterialTheme.typography.headlineMedium
            )
            if (uiState.peerId.isNotEmpty()) {
                Text(
                    text = "配对码: ${uiState.peerId}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = "设备ID: ${uiState.deviceId.take(8)}...",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun PairingSection(viewModel: MainViewModel) {
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
            
            OutlinedButton(
                onClick = { viewModel.generatePairingCode() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("生成识别码")
            }
        }
    }
}

@Composable
fun ConnectedOptionsSection(viewModel: MainViewModel, uiState: com.netproxy.gateway.ui.viewmodel.UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "远程控制",
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // VPN Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("VPN隧道")
                Switch(
                    checked = uiState.isVpnEnabled,
                    onCheckedChange = { viewModel.toggleVpn(it) },
                    enabled = uiState.vpnPrepared
                )
            }
            
            if (!uiState.vpnPrepared) {
                Text(
                    text = "需要VPN授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // WiFi Scan Button
            OutlinedButton(
                onClick = { viewModel.scanWifi() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("扫描WiFi")
            }
            
            // Show WiFi list
            if (uiState.wifiNetworks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "可用网络: ${uiState.wifiNetworks.size}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Disconnect Button
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
fun NetworkInfoCard(uiState: com.netproxy.gateway.ui.viewmodel.UiState) {
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
                Text(if (uiState.wifiConnected) "已连接" else "未连接")
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("蜂窝网络:")
                Text(if (uiState.cellularConnected) "已连接" else "未连接")
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

@Composable
fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    }
}
```

## 验证标准

- [ ] `./gradlew assembleDebug` 编译成功
- [ ] UI 可以显示网络状态
- [ ] 配对功能正常工作
- [ ] VPN 开关可以控制
- [ ] WiFi 扫描可以工作

## 注意事项

1. VPN 授权需要在 Activity 中处理
2. 需要处理权限请求回调

## 下一步

完成后请:
1. 运行 `./gradlew assembleDebug` 验证编译
2. 提交代码到 git
3. 通知 Task 4 (服务端) 开发者可以开始工作
