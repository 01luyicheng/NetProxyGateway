# Task 2: Core Application - 核心应用

> **任务级别**: 核心任务  
> **前置依赖**: Task 1 (Project Setup)  
> **后续任务**: Task 3A-3E  
> **预计工作量**: 2 小时

## 任务目标

创建 Application 类、MainActivity 和 Compose 主题基础。

## 交付物

1. `android/app/src/main/java/com/netproxy/gateway/NetProxyApp.kt`
2. `android/app/src/main/java/com/netproxy/gateway/ui/MainActivity.kt`
3. `android/app/src/main/java/com/netproxy/gateway/ui/theme/Theme.kt`
4. `android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt` (占位)
5. `android/app/src/main/java/com/netproxy/gateway/di/AppModule.kt` (基础 DI)

## 详细步骤

### Step 1: 创建 Application 类

创建 `android/app/src/main/java/com/netproxy/gateway/NetProxyApp.kt`:

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

### Step 2: 创建 Theme 文件

创建 `android/app/src/main/java/com/netproxy/gateway/ui/theme/Theme.kt`:

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

### Step 3: 创建 MainActivity

创建 `android/app/src/main/java/com/netproxy/gateway/ui/MainActivity.kt`:

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

### Step 4: 创建基础 DI Module

创建 `android/app/src/main/java/com/netproxy/gateway/di/AppModule.kt`:

```kotlin
package com.netproxy.gateway.di

import android.content.Context
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
    fun provideApplicationContext(@ApplicationContext context: Context): Context {
        return context
    }
}
```

### Step 5: 创建 MainScreen 占位

创建 `android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt`:

```kotlin
package com.netproxy.gateway.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "NetProxyGateway",
            style = MaterialTheme.typography.headlineMedium
        )
    }
}
```

## 验证标准

- [ ] `./gradlew assembleDebug` 编译成功
- [ ] 应用可以启动并显示基础界面
- [ ] Hilt 依赖注入正常工作

## 下一步

完成后请:
1. 运行 `./gradlew assembleDebug` 验证编译
2. 提交代码到 git
3. 通知所有 Task 3 开发者开始工作
