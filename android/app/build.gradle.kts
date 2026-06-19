plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("jacoco")
    id("org.owasp.dependencycheck")
}

val mqttBrokerUrlTlsDebug = providers.gradleProperty("MQTT_BROKER_URL_TLS_DEBUG")
    .orElse("ssl://localhost:8883")
    .get()
val mqttBrokerUrlPlainDebug = providers.gradleProperty("MQTT_BROKER_URL_PLAIN_DEBUG")
    .orElse("tcp://localhost:1883")
    .get()

// Release配置使用Provider延迟求值
val mqttBrokerUrlTlsRelease = providers.gradleProperty("MQTT_BROKER_URL_TLS_RELEASE")
val mqttBrokerUrlPlainRelease = providers.gradleProperty("MQTT_BROKER_URL_PLAIN_RELEASE")

// 注册release构建校验任务（Configuration Cache兼容）
// 校验在任务执行时进行，避免配置阶段求值问题
val validateReleaseConfig by tasks.registering {
    group = "verification"
    description = "Validate release build configuration"

    doFirst {
        val tlsUrl = mqttBrokerUrlTlsRelease.orNull
        if (tlsUrl.isNullOrBlank()) {
            throw GradleException("MQTT_BROKER_URL_TLS_RELEASE must be configured for release builds. Set it in gradle.properties or via -P flag.")
        }
        val plainUrl = mqttBrokerUrlPlainRelease.orNull
        if (plainUrl.isNullOrBlank()) {
            throw GradleException("MQTT_BROKER_URL_PLAIN_RELEASE must be configured for release builds. Set it in gradle.properties or via -P flag.")
        }
        // C1 修复：构建时静态检查，防止 release 构建误启用信任所有证书
        val trustAllCerts = providers.gradleProperty("MQTT_TRUST_ALL_CERTS").orNull
        if (trustAllCerts == "true") {
            throw GradleException(
                "SECURITY VIOLATION: MQTT_TRUST_ALL_CERTS=true is not allowed in release builds. " +
                "This would bypass all TLS certificate validation and is insecure. " +
                "Remove the property or set it to false."
            )
        }
    }
}

// 让真正的release构建任务依赖校验任务（排除校验任务自身和测试/lint任务）
tasks.matching {
    it.name != "validateReleaseConfig" &&
    (it.name == "assembleRelease" || it.name == "bundleRelease")
}.configureEach {
    dependsOn(validateReleaseConfig)
}

val mqttTlsPublicKeyPinsDebug = providers.gradleProperty("MQTT_TLS_PUBLIC_KEY_PINS_DEBUG")
    .orElse("")
    .get()
val mqttTlsPublicKeyPinsRelease = providers.gradleProperty("MQTT_TLS_PUBLIC_KEY_PINS_RELEASE")
    .orElse(mqttTlsPublicKeyPinsDebug)
    .get()

android {
    namespace = "com.netproxy.gateway"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.netproxy.gateway"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("Boolean", "MQTT_USE_TLS", "true")
        buildConfigField("Boolean", "MQTT_TRUST_ALL_CERTS", "false")
        buildConfigField("String", "MQTT_BROKER_URL_TLS", "\"${mqttBrokerUrlTlsDebug}\"")
        buildConfigField("String", "MQTT_BROKER_URL_PLAIN", "\"${mqttBrokerUrlPlainDebug}\"")
        buildConfigField("String", "MQTT_TLS_PUBLIC_KEY_PINS", "\"${mqttTlsPublicKeyPinsDebug}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("Boolean", "MQTT_USE_TLS", "true")
            buildConfigField("Boolean", "MQTT_TRUST_ALL_CERTS", "false")
            buildConfigField("String", "MQTT_BROKER_URL_TLS", "\"${mqttBrokerUrlTlsRelease.getOrElse("")}\"")
            buildConfigField("String", "MQTT_BROKER_URL_PLAIN", "\"${mqttBrokerUrlPlainRelease.getOrElse(mqttBrokerUrlPlainDebug)}\"")
            buildConfigField("String", "MQTT_TLS_PUBLIC_KEY_PINS", "\"${mqttTlsPublicKeyPinsRelease}\"")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        disable += "ObsoleteLintCustomCheck"
        abortOnError = false
        checkReleaseBuilds = true
        checkAllWarnings = true
        warningsAsErrors = false
        htmlReport = true
        xmlReport = true
        sarifReport = true
    }

    testCoverage {
        jacocoVersion = "0.8.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/license/*"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            pickFirsts += "META-INF/native/*"
            pickFirsts += "META-INF/native-image/**"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")

    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-android-compiler:2.58")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    implementation("io.netty:netty-all:4.2.12.Final")
    implementation("io.netty:netty-codec-socks:4.2.12.Final")

    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("androidx.security:security-crypto:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("com.google.dagger:hilt-android-testing:2.58")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("io.netty:netty-transport:4.2.12.Final")
    testImplementation("io.netty:netty-handler:4.2.12.Final")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // OWASP Dependency Check
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.1.21"))
}

// JaCoCo task configuration
android.applicationVariants.configureEach {
    val variantName = name
    val testTaskName = "test${variantName.replaceFirstChar { it.uppercase() }}UnitTest"

    tasks.register<JacocoReport>("jacoco${testTaskName}Report") {
        dependsOn(testTaskName)

        reports {
            xml.required.set(true)
            html.required.set(true)
        }

        val fileFilter = listOf(
            "**/R.class",
            "**/R$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*Test*.*",
            "android/**/*.*",
            "**/di/**/*.*",
            "**/*_Factory.*",
            "**/*_MembersInjector.*"
        )

        val debugTree = fileTree(
            mapOf(
                "dir" to layout.buildDirectory.dir("intermediates/javac/$variantName").get().asFile,
                "excludes" to fileFilter
            )
        )
        val kotlinDebugTree = fileTree(
            mapOf(
                "dir" to layout.buildDirectory.dir("tmp/kotlin-classes/$variantName").get().asFile,
                "excludes" to fileFilter
            )
        )

        sourceDirectories.setFrom(
            files(
                "src/main/java",
                "src/main/kotlin",
                "src/$variantName/java",
                "src/$variantName/kotlin"
            )
        )
        classDirectories.setFrom(files(debugTree, kotlinDebugTree))
        val testTask = tasks.named<Test>(testTaskName)
        executionData.setFrom(testTask.map { it.extensions.getByType<org.gradle.testing.jacoco.plugins.JacocoTaskExtension>().destinationFile!! })
    }
}

// Task to run all JaCoCo reports
tasks.register("jacocoTestReport") {
    dependsOn("jacocoTestDebugUnitTestReport")
    group = "verification"
    description = "Generate JaCoCo coverage reports for all variants"
}

// OWASP Dependency Check configuration
dependencyCheck {
    analyzers {
        assemblyEnabled = false
        nugetconfEnabled = false
        msbuildEnabled = false
    }
    format = "HTML"
    outputDirectory = layout.buildDirectory.dir("reports").get().asFile.path
    failBuildOnCVSS = 9.0f // Only fail on critical vulnerabilities (CVSS >= 9.0)
    suppressionFile = rootProject.file("dependency-check-suppressions.xml").path
}
