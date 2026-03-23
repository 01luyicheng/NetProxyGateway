# Task 1: Project Setup - 项目初始化

> **任务级别**: 基础任务  
> **前置依赖**: 无  
> **后续任务**: Task 2-3E  
> **预计工作量**: 2 小时

## 任务目标

初始化 Android 项目基础结构，创建 Gradle wrapper 和基础构建配置。

## 交付物

1. `android/gradle/wrapper/gradle-wrapper.properties`
2. `android/gradle/wrapper/gradle-wrapper.jar`
3. `android/build.gradle.kts` (root)
4. `android/settings.gradle.kts`
5. `android/gradle.properties`
6. `android/app/build.gradle.kts`
7. `android/app/proguard-rules.pro`
8. `android/app/src/main/AndroidManifest.xml`

## 详细步骤

### Step 1: 创建 Gradle Wrapper 配置

创建 `android/gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.4-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

### Step 2: 生成 Gradle Wrapper

```bash
cd android && gradle wrapper --gradle-version 8.4
```

验证:
```bash
cd android && ./gradlew -v
```

### Step 3: 创建 Root Build 文件

创建 `android/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.21" apply false
    id("com.google.dagger.hilt.android") version "2.50" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.21" apply false
}
```

### Step 4: 创建 Settings 文件

创建 `android/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NetProxyGateway"
include(":app")
```

### Step 5: 创建 Gradle Properties

创建 `android/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

### Step 6: 创建 App Build 文件

创建 `android/app/build.gradle.kts`:

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

### Step 7: 创建 ProGuard 规则

创建 `android/app/proguard-rules.pro`:

```proguard
# Keep MQTT classes
-keep class org.eclipse.paho.** { *; }
-keepclassmembers class org.eclipse.paho.** { *; }

# Keep Netty
-keep class io.netty.** { *; }
-dontwarn io.netty.**

# Keep Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
```

### Step 8: 创建 AndroidManifest

创建 `android/app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Network permissions -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />

    <!-- Location permissions (WiFi scanning required) -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

    <!-- VPN permissions -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

    <!-- Nearby devices (Android 13+) -->
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />

    <application
        android:name=".NetProxyApp"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.NetProxyGateway">
        
        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:theme="@style/Theme.NetProxyGateway">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".vpn.VpnService"
            android:exported="false"
            android:foregroundServiceType="specialUse"
            android:permission="android.permission.BIND_VPN_SERVICE">
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
        </service>

        <service
            android:name=".proxy.Socks5ProxyService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
            
    </application>

</manifest>
```

### Step 9: 创建基础资源文件

创建 `android/app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">NetProxyGateway</string>
</resources>
```

创建 `android/app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.NetProxyGateway" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

## 验证标准

- [ ] `./gradlew assembleDebug` 编译成功
- [ ] 项目结构符合 `docs/CLAUDE.md` 中的定义
- [ ] 生成有效的 debug APK

## 常见问题

### Q: Gradle wrapper 下载失败
A: 检查网络代理设置，或手动下载 gradle-8.4-bin.zip 到本地

### Q: Kotlin version 冲突
A: 确保 root build.gradle.kts 中的 Kotlin 版本与 composeOptions.kotlinCompilerExtensionVersion 兼容

---

## 下一步

完成后请:
1. 运行 `./gradlew assembleDebug` 验证编译
2. 提交代码到 git
3. 通知 Task 2 开发者开始工作
