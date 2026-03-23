# Task 3B-1: VPN 基础框架

> **任务级别**: 基础任务  
> **前置依赖**: Task 2 (Core Application)  
> **后续任务**: Task 3B-2, Task 3B-3  
> **预计工作量**: 1.5 小时

## 任务目标

创建 VPN 模块的基础框架，包括常量定义和 VpnConfig。

## 交付物

1. `android/app/src/main/java/com/netproxy/gateway/vpn/VpnConfig.kt` - VPN 常量配置
2. `android/app/src/main/java/com/netproxy/gateway/vpn/VpnState.kt` - VPN 状态枚举
3. `android/app/src/main/java/com/netproxy/gateway/vpn/VpnStatus.kt` - VPN 状态数据类

## 详细步骤

### Step 1: 创建 VpnConfig.kt

创建 VPN 常量配置文件：

```kotlin
package com.netproxy.gateway.vpn

object VpnConfig {
    // Virtual IP for the VPN interface
    const val VPN_ADDRESS = "10.0.0.2"
    const val VPN_ROUTE = "0.0.0.0" // Route all traffic through VPN
    const val VPN_DNS = "8.8.8.8"
    const val VPN_MTU = 1500
    
    // Split tunneling configuration
    // Cloud server IPs will be excluded to use cellular directly
    val EXCLUDED_ROUTES = listOf(
        // Add your cloud server IP ranges here
        // Pair of (IP, prefixLength)
    )
}
```

### Step 2: 创建 VpnState.kt

创建 VPN 状态枚举：

```kotlin
package com.netproxy.gateway.vpn

enum class VpnState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}
```

### Step 3: 创建 VpnStatus.kt

创建 VPN 状态数据类：

```kotlin
package com.netproxy.gateway.vpn

data class VpnStatus(
    val state: VpnState = VpnState.STOPPED,
    val errorMessage: String? = null,
    val connectedClients: Int = 0
)
```

## 验证标准

- [ ] VpnConfig.kt 包含所有必要常量
- [ ] VpnState.kt 定义正确的状态枚举
- [ ] VpnStatus.kt 包含完整的状态数据类
- [ ] 代码遵循项目编码规范

## 下一步

完成后请:
1. 提交代码到 git
2. 通知 Task 3B-2 开发者开始工作
