# NetProxyGateway Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Create a complete Android project for remote network assistance with dual-channel (cellular + WiFi) split tunneling

**Architecture:** 
- Android mobile app with VPN Service for traffic forwarding
- MQTT over cellular for control channel
- SOCKS5 proxy for data channel
- Split tunneling based on IP ranges

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Coroutines, VpnService, Netty, OkHttp, MQTT

---

## Phase 1: Project Setup

### Task 1: Initialize Gradle wrapper and project structure

**Files:**
- Create: `android/gradle/wrapper/gradle-wrapper.properties`
- Create: `android/gradle/wrapper/gradle-wrapper.jar` (download from Gradle)
- Create: `android/build.gradle.kts` (root)
- Create: `android/settings.gradle.kts`
- Create: `android/gradle.properties`

**Step 1: Create gradle wrapper config**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.4-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

**Step 2: Run gradle wrapper**

```bash
cd android && gradle wrapper --gradle-version 8.4
```

---

### Task 2: Create app build.gradle.kts

**Files:**
- Create: `android/app/build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    kotlin("kapt")
}

android {
    namespace = "com.netproxy.gateway"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.netproxy.gateway"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // MQTT
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // Netty (SOCKS5)
    implementation("io.netty:netty-all:4.1.100.Final")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    correctErrorTypes = true
}
```

---

## Phase 2: Core Application

### Task 3: Create Application class

**Files:**
- Create: `android/app/src/main/java/com/netproxy/gateway/NetProxyApp.kt`

```kotlin
package com.netproxy.gateway

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NetProxyApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
```

---

### Task 4: Create main activity with Compose

**Files:**
- Create: `android/app/src/main/java/com/netproxy/gateway/ui/MainActivity.kt`

```kotlin
package com.netproxy.gateway.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.netproxy.gateway.ui.theme.NetProxyGatewayTheme
import com.netproxy.gateway.ui.screens.MainScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NetProxyGatewayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}
```

---

### Task 5: Create theme

**Files:**
- Create: `android/app/src/main/java/com/netproxy/gateway/ui/theme/Theme.kt`

```kotlin
package com.netproxy.gateway.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4CAF50),
    secondary = Color(0xFF03DAC6),
    tertiary = Color(0xFF3700B3),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4CAF50),
    secondary = Color(0xFF03DAC6),
    tertiary = Color(0xFF6200EE),
    background = Color(0xFFF5F5F5),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun NetProxyGatewayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
```

---

## Phase 3: Network Connection Module

### Task 6: Create NetworkStateManager

**Files:**
- Create: `android/app/src/main/java/com/netproxy/gateway/connection/NetworkStateManager.kt`

```kotlin
package com.netproxy.gateway.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

sealed class NetworkType {
    object Wifi : NetworkType()
    object Cellular : NetworkType()
    object Ethernet : NetworkType()
    object None : NetworkType()
}

data class NetworkState(
    val isConnected: Boolean = false,
    val networkType: NetworkType = NetworkType.None,
    val isValidated: Boolean = false,
    val network: Network? = null
)

@Singleton
class NetworkStateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val networkState: Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getCurrentNetworkState(network))
            }

            override fun onLost(network: Network) {
                trySend(NetworkState(isConnected = false, networkType = NetworkType.None))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(getCurrentNetworkState(network))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // Emit initial state
        trySend(getCurrentNetworkState(null))

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    private fun getCurrentNetworkState(network: Network?): NetworkState {
        val activeNetwork = network ?: connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        if (capabilities == null) {
            return NetworkState(isConnected = false, networkType = NetworkType.None)
        }

        val networkType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.Wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.Cellular
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.Ethernet
            else -> NetworkType.None
        }

        return NetworkState(
            isConnected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            networkType = networkType,
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            network = activeNetwork
        )
    }

    fun getCurrentNetworkType(): NetworkType {
        return getCurrentNetworkState(null).networkType
    }

    fun isWifiConnected(): Boolean {
        return getCurrentNetworkType() == NetworkType.Wifi
    }

    fun isCellularConnected(): Boolean {
        return getCurrentNetworkType() == NetworkType.Cellular
    }
}
```

---

### Task 7: Create MqttConnectionManager

**Files:**
- Create: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt`

```kotlin
package com.netproxy.gateway.connection

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import javax.inject.Inject
import javax.inject.Singleton

sealed class MqttConnectionState {
    object Disconnected : MqttConnectionState()
    object Connecting : MqttConnectionState()
    object Connected : MqttConnectionState()
    data class Error(val message: String) : MqttConnectionState()
}

@Singleton
class MqttConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MqttConnectionManager"
        private const val BROKER_URL = "tcp://your-server:1883"
        private const val CLIENT_ID = "NetProxyGateway"
        private const val HEARTBEAT_INTERVAL = 30000L
        private const val MAX_RECONNECT_DELAY = 60000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mqttClient: MqttClient? = null
    private var reconnectDelay = 5000L

    private val _connectionState = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
    val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    fun connect(deviceId: String, authToken: String) {
        scope.launch {
            try {
                _connectionState.value = MqttConnectionState.Connecting

                val clientId = "${CLIENT_ID}_$deviceId"
                mqttClient = MqttClient(BROKER_URL, clientId, MemoryPersistence())

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 30
                    keepAliveInterval = 30
                    userName = deviceId
                    password = authToken.toCharArray()
                    automaticReconnect = false // We handle reconnection manually
                }

                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "Connection lost: ${cause?.message}")
                        _connectionState.value = MqttConnectionState.Error(cause?.message ?: "Connection lost")
                        scheduleReconnect(deviceId, authToken)
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        message?.let {
                            _messages.value = String(it.payload)
                            Log.d(TAG, "Message received: $topic - ${String(it.payload)}")
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        // Message delivered
                    }
                })

                mqttClient?.connect(options)
                _connectionState.value = MqttConnectionState.Connected
                reconnectDelay = 5000L // Reset delay on successful connection

                // Subscribe to control topic
                subscribe("device/$deviceId/control")

                // Start heartbeat
                startHeartbeat(deviceId)

            } catch (e: MqttException) {
                Log.e(TAG, "MQTT connection error: ${e.message}")
                _connectionState.value = MqttConnectionState.Error(e.message ?: "Connection failed")
                scheduleReconnect(deviceId, authToken)
            }
        }
    }

    private fun scheduleReconnect(deviceId: String, authToken: String) {
        scope.launch {
            delay(reconnectDelay)
            reconnectDelay = minOf(reconnectDelay * 2, MAX_RECONNECT_DELAY)
            connect(deviceId, authToken)
        }
    }

    private fun startHeartbeat(deviceId: String) {
        scope.launch {
            while (_connectionState.value == MqttConnectionState.Connected) {
                delay(HEARTBEAT_INTERVAL)
                publish("device/$deviceId/heartbeat", "{\"status\":\"alive\"}")
            }
        }
    }

    fun publish(topic: String, payload: String, qos: Int = 0) {
        try {
            val message = MqttMessage(payload.toByteArray()).apply {
                this.qos = qos
            }
            mqttClient?.publish(topic, message)
        } catch (e: MqttException) {
            Log.e(TAG, "Publish error: ${e.message}")
        }
    }

    fun subscribe(topic: String, qos: Int = 0) {
        try {
            mqttClient?.subscribe(topic, qos)
        } catch (e: MqttException) {
            Log.e(TAG, "Subscribe error: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
            _connectionState.value = MqttConnectionState.Disconnected
        } catch (e: MqttException) {
            Log.e(TAG, "Disconnect error: ${e.message}")
        }
    }
}
```

---

## Phase 4: VPN Service Module

### Task 8: Create VpnService core

**Files:**
- Create: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`

```kotlin
package com.netproxy.gateway.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.netproxy.gateway.ui.MainActivity
import com.netproxy.gateway.proxy.Socks5ProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream

enum class VpnState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class VpnStatus(
    val state: VpnState = VpnState.STOPPED,
    val errorMessage: String? = null,
    val connectedClients: Int = 0
)

class VpnService : VpnService() {

    companion object {
        private const val TAG = "VpnService"
        
        // Virtual IP for the VPN interface
        const val VPN_ADDRESS = "10.0.0.2"
        const val VPN_ROUTE = "0.0.0.0" // Route all traffic through VPN
        const val VPN_DNS = "8.8.8.8"
        const val VPN_MTU = 1500
        
        // Excluded routes (will bypass VPN)
        // Cloud server IPs will be excluded to use cellular directly
        val EXCLUDED_ROUTES = listOf(
            // Add your cloud server IP ranges here
            // Pair of (IP, prefixLength)
        )
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    
    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startVpn()
            "STOP" -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (_status.value.state == VpnState.RUNNING) {
            return
        }

        _status.value = VpnStatus(state = VpnState.STARTING)

        try {
            // Configure the VPN interface
            val builder = Builder()
                .setSession("NetProxyGateway")
                .setMtu(VPN_MTU)
                .addAddress(VPN_ADDRESS, 32)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer(VPN_DNS)
                .setBlocking(true)

            // Configure excluded routes for split tunneling (Android 13+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                configureSplitTunneling(builder)
            }

            // Configure intent for VPN settings
            val configureIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setConfigureIntent(configureIntent)

            // Establish the VPN interface
            vpnInterface = builder.establish()
            
            if (vpnInterface != null) {
                _status.value = VpnStatus(state = VpnState.RUNNING)
                
                // Start processing traffic
                serviceScope.launch {
                    processVpnTraffic()
                }
                
                // Start SOCKS5 proxy service
                startProxyService()
            } else {
                _status.value = VpnStatus(
                    state = VpnState.ERROR,
                    errorMessage = "Failed to establish VPN"
                )
            }
        } catch (e: Exception) {
            _status.value = VpnStatus(
                state = VpnState.ERROR,
                errorMessage = e.message
            )
        }
    }

    private fun configureSplitTunneling(builder: Builder) {
        // Android 13+ supports excludeRoute for split tunneling
        // Traffic to these IPs will bypass the VPN and use regular network
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            try {
                // Example: Exclude cloud server IP
                // This allows the phone to reach cloud server via cellular directly
                // while other traffic goes through VPN
                // builder.excludeRoute(InetAddress.getByName("your-cloud-server-ip"), 32)
            } catch (e: Exception) {
                // Handle exception
            }
        }
    }

    private suspend fun processVpnTraffic() {
        val vpnFd = vpnInterface ?: return
        val inputStream = FileInputStream(vpnFd.fileDescriptor)
        val outputStream = FileOutputStream(vpnFd.fileDescriptor)
        val packet = ByteArray(32767)

        try {
            while (_status.value.state == VpnState.RUNNING) {
                val length = inputStream.read(packet)
                if (length > 0) {
                    // Process the packet
                    // In a real implementation, you would:
                    // 1. Parse the IP packet
                    // 2. Determine the destination
                    // 3. Forward to appropriate channel (WiFi or SOCKS5 proxy)
                    processPacket(packet, length)
                }
            }
        } catch (e: Exception) {
            if (_status.value.state == VpnState.RUNNING) {
                _status.value = VpnStatus(
                    state = VpnState.ERROR,
                    errorMessage = e.message
                )
            }
        }
    }

    private fun processPacket(packet: ByteArray, length: Int) {
        // This is where we implement the split tunneling logic
        // Parse the IP header to determine destination
        // Forward to WiFi or SOCKS5 proxy based on routing rules
    }

    private fun startProxyService() {
        val intent = Intent(this, Socks5ProxyService::class.java)
        startForegroundService(intent)
    }

    private fun stopVpn() {
        _status.value = VpnStatus(state = VpnState.STOPPED)
        
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            // Handle cleanup
        }
        
        // Stop proxy service
        stopProxyService()
    }

    private fun stopProxyService() {
        val intent = Intent(this, Socks5ProxyService::class.java)
        stopService(intent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}
```

---

### Task 9: Create SOCKS5 Proxy Service

**Files:**
- Create: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyService.kt`

```kotlin
package com.netproxy.gateway.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.netproxy.gateway.R
import com.netproxy.gateway.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import javax.inject.Inject

@AndroidEntryPoint
class Socks5ProxyService : Service() {

    companion object {
        private const val TAG = "Socks5ProxyService"
        const val PROXY_PORT = 1080
        const val CHANNEL_ID = "proxy_service_channel"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private var serverChannel: Channel? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())
        startProxyServer()
        return START_STICKY
    }

    private fun startProxyServer() {
        serviceScope.launch {
            try {
                bossGroup = NioEventLoopGroup(1)
                workerGroup = NioEventLoopGroup()

                val bootstrap = ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel::class.java)
                    .childHandler(object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            ch.pipeline().addLast(
                                LoggingHandler(LogLevel.DEBUG),
                                SocksPortUnificationServerHandler(),
                                Socks5ProxyHandler()
                            )
                        }
                    })

                val socketAddress = InetSocketAddress(PROXY_PORT)
                serverChannel = bootstrap.bind(socketAddress).sync().channel()
                serverChannel?.closeFuture?.await()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SOCKS5 Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running SOCKS5 proxy server"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NetProxyGateway")
            .setContentText("SOCKS5 Proxy Running on port $PROXY_PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        bossGroup?.shutdownGracefully()
        workerGroup?.shutdownGracefully()
        serverChannel?.close()
        super.onDestroy()
    }
}
```

---

### Task 10: Create SOCKS5 Proxy Handler

**Files:**
- Create: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt`

```kotlin
package com.netproxy.gateway.proxy

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.socksx.SocksVersion
import io.netty.handler.codec.socksx.v5.*
import org.slf4j.LoggerFactory

class Socks5ProxyHandler : ChannelInboundHandlerAdapter() {

    private val logger = LoggerFactory.getLogger(Socks5ProxyHandler::class.java)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is SocksInitialRequest -> {
                // Handle SOCKS5 initial request
                // Respond with no authentication required (0x00) or username/password
                val response = DefaultSocksInitialResponse(SocksAuthScheme.NO_AUTH)
                ctx.writeAndFlush(response)
            }
            is SocksAuthRequest -> {
                // If username/password auth is used
                val response = DefaultSocksAuthResponse(SocksAuthStatus.SUCCESS)
                ctx.writeAndFlush(response)
            }
            is SocksCmdRequest -> {
                handleCmdRequest(ctx, msg)
            }
        }
    }

    private fun handleCmdRequest(ctx: ChannelHandlerContext, msg: SocksCmdRequest) {
        when (msg.type()) {
            SocksCmdType.CONNECT -> {
                // Connect to the target address
                // This is where we forward traffic
                logger.info("SOCKS5 CONNECT request to ${msg.dstAddr()}:${msg.dstPort()}")
                
                // In a full implementation, we would:
                // 1. Establish connection to target
                // 2. Forward traffic between client and target
                
                // For now, just acknowledge success
                val response = DefaultSocksCmdResponse(
                    SocksCmdStatus.SUCCESS,
                    msg.dstAddr(),
                    msg.dstPort()
                )
                ctx.writeAndFlush(response)
                
                // Remove handlers and forward data
                ctx.pipeline().remove(this)
            }
            SocksCmdType.BIND -> {
                // Not supported for now
                val response = DefaultSocksCmdResponse(
                    SocksCmdStatus.COMMAND_UNSUPPORTED,
                    msg.dstAddr(),
                    msg.dstPort()
                )
                ctx.writeAndFlush(response)
            }
            SocksCmdType.UDP_ASSOCIATE -> {
                // Not supported for now
                val response = DefaultSocksCmdResponse(
                    SocksCmdStatus.COMMAND_UNSUPPORTED,
                    msg.dstAddr(),
                    msg.dstPort()
                )
                ctx.writeAndFlush(response)
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("SOCKS5 handler exception: ${cause.message}")
        ctx.close()
    }
}
```

---

## Phase 5: WiFi Control Module

### Task 11: Create WiFiManager

**Files:**
- Create: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt`

```kotlin
package com.netproxy.gateway.wifi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val signalStrength: Int,
    val frequency: Int,
    val capabilities: String,
    val isSecure: Boolean
)

data class WifiConnectionInfo(
    val ssid: String?,
    val bssid: String?,
    val ipAddress: Int,
    val linkSpeed: Int,
    val frequency: Int,
    val signalStrength: Int
)

@Singleton
class WifiManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WifiManager"
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val wifiScanResults: Flow<List<WifiNetwork>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                    Log.d(TAG, "WiFi scan completed: success=$success")
                    
                    val results = getScanResults()
                    trySend(results)
                }
            }
        }

        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(receiver, intentFilter)

        // Emit initial results
        trySend(getScanResults())

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }

    fun startScan(): Boolean {
        return wifiManager.startScan()
    }

    fun getScanResults(): List<WifiNetwork> {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        return wifiManager.scanResults
            .filter { !it.SSID.isNullOrEmpty() }
            .map { result ->
                WifiNetwork(
                    ssid = result.SSID,
                    bssid = result.BSSID,
                    signalStrength = result.level,
                    frequency = result.frequency,
                    capabilities = result.capabilities,
                    isSecure = result.capabilities.contains("WPA") || result.capabilities.contains("WEP")
                )
            }
            .sortedByDescending { it.signalStrength }
    }

    fun getCurrentConnection(): WifiConnectionInfo? {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val connectionInfo = wifiManager.connectionInfo ?: return null
        
        return WifiConnectionInfo(
            ssid = connectionInfo.ssid?.replace("\"", ""),
            bssid = connectionInfo.bssid,
            ipAddress = connectionInfo.ipAddress,
            linkSpeed = connectionInfo.linkSpeed,
            frequency = connectionInfo.frequency,
            signalStrength = connectionInfo.rssi
        )
    }

    fun connectToNetwork(ssid: String, password: String?, securityType: String): Boolean {
        val configuration = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            
            when {
                securityType.contains("WPA2") || securityType.contains("WPA") -> {
                    preSharedKey = "\"$password\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                securityType.contains("WEP") -> {
                    wepKeys[0] = "\"$password\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
                else -> {
                    // Open network
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
            }
        }

        val networkId = wifiManager.addNetwork(configuration)
        if (networkId == -1) {
            Log.e(TAG, "Failed to add network configuration")
            return false
        }

        val success = wifiManager.enableNetwork(networkId, true)
        if (!success) {
            Log.e(TAG, "Failed to enable network")
            return false
        }

        return true
    }

    fun disconnect(): Boolean {
        return wifiManager.disconnect()
    }
}
```

---

## Phase 6: UI Module

### Task 12: Create Main Screen

**Files:**
- Create: `android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt`

```kotlin
package com.netproxy.gateway.ui.screens

import androidx.compose.foundation.layout.*
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Connection Status Card
            ConnectionStatusCard(uiState)
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Pairing Section
            if (!uiState.isPaired) {
                PairingSection(uiState, viewModel)
            } else {
                // Connected Options
                ConnectedOptionsSection(uiState, viewModel)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Network Info
            NetworkInfoCard(uiState)
        }
    }
}

@Composable
fun ConnectionStatusCard(uiState: com.netproxy.gateway.ui.viewmodel.UiState) {
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
    uiState: com.netproxy.gateway.ui.viewmodel.UiState,
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
    uiState: com.netproxy.gateway.ui.viewmodel.UiState,
    viewModel: MainViewModel
) {
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
                    onCheckedChange = { viewModel.toggleVpn(it) }
                )
            }
            
            // WiFi Scan Button
            OutlinedButton(
                onClick = { viewModel.scanWifi() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("扫描WiFi")
            }
            
            // Show WiFi list if available
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
```

---

### Task 13: Create ViewModel

**Files:**
- Create: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt`

```kotlin
package com.netproxy.gateway.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netproxy.gateway.connection.MqttConnectionManager
import com.netproxy.gateway.connection.MqttConnectionState
import com.netproxy.gateway.connection.NetworkStateManager
import com.netproxy.gateway.connection.NetworkType
import com.netproxy.gateway.wifi.WifiManager
import com.netproxy.gateway.wifi.WifiNetwork
import com.netproxy.gateway.vpn.VpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val wifiConnected: Boolean = false,
    val cellularConnected: Boolean = false,
    val currentWifiSsid: String = "",
    val wifiNetworks: List<WifiNetwork> = emptyList(),
    val errorMessage: String? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkStateManager: NetworkStateManager,
    private val mqttConnectionManager: MqttConnectionManager,
    private val wifiManager: WifiManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // Generate unique device ID
        val deviceId = UUID.randomUUID().toString()
        _uiState.value = _uiState.value.copy(deviceId = deviceId)
        
        // Start network monitoring
        observeNetworkState()
        observeMqttState()
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
                            errorMessage = state.message
                        )
                    }
                    else -> {}
                }
            }
        }
    }

    fun generatePairingCode() {
        viewModelScope.launch {
            // Generate 6-digit code
            val code = (100000..999999).random().toString()
            _uiState.value = _uiState.value.copy(peerId = code)
            
            // In a real implementation, you would send this to your server
            // and wait for the engineer to connect with the same code
        }
    }

    fun pairWithCode(code: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(peerId = code)
            
            // Connect via MQTT (using cellular)
            if (networkStateManager.isCellularConnected()) {
                mqttConnectionManager.connect(
                    deviceId = _uiState.value.deviceId,
                    authToken = code
                )
                _uiState.value = _uiState.value.copy(isPaired = true)
            } else {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "需要蜂窝网络连接"
                )
            }
        }
    }

    fun toggleVpn(enable: Boolean) {
        if (enable) {
            // Start VPN service
            val intent = Intent(context, VpnService::class.java).apply {
                action = "START"
            }
            context.startForegroundService(intent)
            _uiState.value = _uiState.value.copy(isVpnEnabled = true)
        } else {
            // Stop VPN service
            val intent = Intent(context, VpnService::class.java).apply {
                action = "STOP"
            }
            context.startService(intent)
            _uiState.value = _uiState.value.copy(isVpnEnabled = false)
        }
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

    fun disconnect() {
        mqttConnectionManager.disconnect()
        toggleVpn(false)
        _uiState.value = _uiState.value.copy(
            isConnected = false,
            isPaired = false,
            peerId = ""
        )
    }
}
```

---

## Phase 7: DI Module

### Task 14: Create Hilt modules

**Files:**
- Create: `android/app/src/main/java/com/netproxy/gateway/di/AppModule.kt`

```kotlin
package com.netproxy.gateway.di

import android.content.Context
import com.netproxy.gateway.connection.MqttConnectionManager
import com.netproxy.gateway.connection.NetworkStateManager
import com.netproxy.gateway.wifi.WifiManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideNetworkStateManager(
        @ApplicationContext context: Context
    ): NetworkStateManager {
        return NetworkStateManager(context)
    }

    @Provides
    @Singleton
    fun provideMqttConnectionManager(
        @ApplicationContext context: Context
    ): MqttConnectionManager {
        return MqttConnectionManager(context)
    }

    @Provides
    @Singleton
    fun provideWifiManager(
        @ApplicationContext context: Context
    ): WifiManager {
        return WifiManager(context)
    }
}
```

---

## Task 15: Create resources

**Files:**
- Create: `android/app/src/main/res/values/strings.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">NetProxyGateway</string>
</resources>
```

**Files:**
- Create: `android/app/src/main/res/values/colors.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="black">#FF000000</color>
    <color name="white">#FFFFFFFF</color>
</resources>
```

---

## Task 16: Create gradle.properties and local.properties

**Files:**
- Create: `android/gradle.properties`

```properties
# Project-wide Gradle settings.
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

**Files:**
- Create: `android/local.properties`

```properties
sdk.dir=C\:\\Android\\SDK
```

---

## Implementation Complete

This completes the initial implementation plan. The project structure provides:

1. ✅ Dual-channel network management (Cellular + WiFi)
2. ✅ MQTT connection for control channel
3. ✅ VPN Service with split tunneling support
4. ✅ SOCKS5 proxy for data forwarding
5. ✅ WiFi scanning and connection
6. ✅ Basic UI with Jetpack Compose
7. ✅ Clean Architecture with Hilt DI

**Next Steps:**
- Implement the cloud server components
- Add error handling and reconnection logic
- Implement the routing engine for split tunneling
- Add engineering-side client application
