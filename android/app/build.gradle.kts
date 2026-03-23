plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.kapt")
}

val mqttBrokerUrlTlsDebug = providers.gradleProperty("MQTT_BROKER_URL_TLS_DEBUG")
    .orElse("ssl://localhost:8883")
    .get()
val mqttBrokerUrlPlainDebug = providers.gradleProperty("MQTT_BROKER_URL_PLAIN_DEBUG")
    .orElse("tcp://localhost:1883")
    .get()
val mqttBrokerUrlTlsRelease = providers.gradleProperty("MQTT_BROKER_URL_TLS_RELEASE")
    .orElse("ssl://localhost:8883")
    .get()
val mqttBrokerUrlPlainRelease = providers.gradleProperty("MQTT_BROKER_URL_PLAIN_RELEASE")
    .orElse("")
    .get()

android {
    namespace = "com.netproxy.gateway"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.netproxy.gateway"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("Boolean", "MQTT_USE_TLS", "false")
        buildConfigField("String", "MQTT_BROKER_URL_TLS", "\"${mqttBrokerUrlTlsDebug}\"")
        buildConfigField("String", "MQTT_BROKER_URL_PLAIN", "\"${mqttBrokerUrlPlainDebug}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("Boolean", "MQTT_USE_TLS", "true")
            buildConfigField("String", "MQTT_BROKER_URL_TLS", "\"${mqttBrokerUrlTlsRelease}\"")
            buildConfigField("String", "MQTT_BROKER_URL_PLAIN", "\"${mqttBrokerUrlPlainRelease}\"")
        }
        debug {
            isMinifyEnabled = false
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    implementation("io.netty:netty-all:4.1.108.Final")
    implementation("io.netty:netty-codec-socks:4.1.108.Final")

    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("io.netty:netty-transport:4.1.108.Final")
    testImplementation("io.netty:netty-handler:4.1.108.Final")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.01.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    correctErrorTypes = true
}
