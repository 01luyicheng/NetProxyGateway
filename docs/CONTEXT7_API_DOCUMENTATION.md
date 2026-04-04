# Context7 API 文档汇总

本文档汇总了从 Context7 获取的项目依赖相关 API 文档和迁移指南。

**生成日期**: 2026-04-04

---

## Gradle 升级指南

### 1. registerFeature 调用变更

**来源**: Gradle 升级指南

在 Gradle 10.0.0 之前，调用 `JavaPluginExtension#registerFeature` 前必须先应用 Java 插件。

```kotlin
// 正确做法：确保 Java 插件已应用
plugins {
    id("java")
}

java {
    registerFeature("myFeature") {
        // ... feature configuration
    }
}
```

### 2. Java 插件配置迁移

将已弃用的 `JavaPluginConvention` 访问替换为现代的 java 扩展块。

```kotlin
plugins {
    id("java")
}

java {
    sourceCompatibility = JavaVersion.VERSION_18
}
```

### 3. Gradle 9.0 最低 Android Gradle Plugin 版本

从 Gradle 9.0.0 开始，最低支持的 Android Gradle Plugin 版本是 8.4.0。

### 4. Gradle 8.0 移除的 API

Gradle 8.0 移除了以下遗留 API：
- 遗留的 `ArtifactTransform` API
- `IncrementalTaskInputs` API

**建议**: 运行 `gradle help --scan` 查看弃用警告，或运行 `gradle help --warning-mode=all` 在控制台查看。

---

## Kotlin 元数据 API 迁移指南

### 1. 包名变更

从稳定版本 2.x.x 开始，Kotlin 元数据包名已变更：

```kotlin
// 旧导入
import kotlinx.metadata
import kotlinx.metadata.jvm

// 新导入
import kotlin.metadata
import kotlin.metadata.jvm
```

### 2. Kotlin 类元数据写入 API 迁移

从 `KotlinClassMetadata.Class.Writer()` 迁移到 `KotlinClassMetadata.writeClass()`：

```kotlin
// 旧方式
fun saveClass(kmClass: KmClass) {
    val writer = KotlinClassMetadata.Class.Writer()
    kmClass.accept(writer)
    val classMetadata: KotlinClassMetadata.Class = writer.write()
    val kotlinClassHeader: KotlinClassHeader = classMetadata.header
    // Write kotlinClassHeader.data1, data2, etc using ASM
}

// 新方式
fun saveClass(kmClass: KmClass) {
    val classMetadata: KotlinClassMetadata.Class = KotlinClassMetadata.writeClass(kmClass)
    val metadata: Metadata = classMetadata.annotationData
    // Write Metadata.data1, data2, etc using ASM
}
```

### 3. 从 Visitor API 迁移到 Nodes API

```kotlin
// 旧方式：使用 Visitor 模式
class TestFunctionFinder : KmClassVisitor() {
    val result = mutableListOf<String>()

    override fun visitFunction(flags: Flags, name: String): KmFunctionVisitor? {
        if (Flag.Common.IS_PUBLIC(flags) && name.startsWith("test")) result.add(name)
        return null
    }
}

fun KmClass.testFunctions(): List<String> = TestFunctionFinder().also { this.accept(it) }.result.toList()

// 新方式：使用 Nodes API
fun KmClass.testFunctions(): List<String> = this.functions.mapNotNull { f ->
    if (Flag.Common.IS_PUBLIC(f.flags) && f.name.startsWith("test")) f.name else null
}
```

### 4. 元数据版本常量变更

```kotlin
// 旧方式
val isCompatible = version == KotlinClassMetadata.COMPATIBLE_METADATA_VERSION

// 新方式
val isCompatible = version == JvmMetadataVersion.LATEST_STABLE_SUPPORTED
```

### 迁移步骤总结

1. 确保不使用任何已弃用的 API（它们在稳定版本中已被完全移除）
2. 更新导入语句指向正确的包
3. 声明本身未改变，只需更新导入

---

## Jetpack Compose BOM 信息

### Compose BOM 2026.03.00+ 配置

**最低 SDK**: API level 21+

**Gradle 配置 (Kotlin DSL)**:
```kotlin
implementation(platform("androidx.compose:compose-bom:2026.03.00"))
```

**Gradle 配置 (Groovy DSL)**:
```groovy
implementation platform('androidx.compose:compose-bom:2026.03.00')
```

**包含的组件**:
- `androidx.compose.foundation` - 基础布局（LazyHorizontalGrid, LazyVerticalGrid）
- `androidx.compose.material` - Material Design 组件
- `androidx.compose.ui` - 核心 UI 组件和修饰符

### 分页库集成

```kotlin
implementation(platform("androidx.compose:compose-bom:2026.03.00"))
implementation("androidx.paging:paging-compose:3.3.0-alpha05")
```

### Coil 图片加载集成

```kotlin
implementation(platform("androidx.compose:compose-bom:2026.03.00"))
implementation("io.coil-kt:coil-compose:2.6.0")
```

---

## Netty 4.2 迁移指南

### 1. 默认内存分配器变更

Netty 4.2 默认使用 `adaptive` 分配器替代 `pooled` 分配器。

如需恢复 4.1 的 pooled 分配器行为：
```properties
io.netty.allocator.type=pooled
```

### 2. io_uring 传输包和类名变更

```java
// 旧包 (Netty 4.1 incubator)
// import io.netty.incubator.channel.uring.*

// 新包 (Netty 4.2 stabilized)
import io.netty.channel.uring.*

// 旧类命名
// IOUringEventLoopGroup
// IOUringIoHandler

// 新类命名
// MultiThreadIoEventLoopGroup
// IoUringIoHandler
```

### 3. EventLoopGroup 初始化方式变更

```java
// 旧方式
EventLoopGroup group = new NioEventLoopGroup(); // ❌

// 新方式
EventLoopGroup group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory()); // ✅
```

### 4. TLS 默认端点验证变更

Netty 4.2 默认启用客户端 TLS 连接的主机名验证。

如需恢复 4.1 的禁用行为（仅作为临时迁移措施）：
```properties
io.netty.handler.ssl.defaultEndpointVerificationAlgorithm=NONE
```

### 5. 其他兼容性变更

- **protobuf-java**: 升级到 3.25.5
- **netty-codec**: 模块拆分为多个子模块
- **WebSocket**: 移除了对过时草案规范的支持
- **ALPN/NPN**: 移除了 Jetty ALPN/NPN 支持（Java 8 原生支持 ALPN）
- **管道调用栈**: 已扁平化以减少深度
- **最低 GLibC 版本**: 2.17
- **tcnative OpenSSL**: 测试迁移到 OpenSSL 1.0.2k
- **JPMS 支持**: 从自动模块切换到真实模块

---

## Dagger Hilt 迁移指南

### 1. 一般迁移步骤

从 Dagger 迁移到 Hilt 的代码变更概览：

```java
// 移除 @Component / @Subcomponent 或 @ContributesAndroidInjector
// 添加 @InstallIn 到 @Module 类
// 添加 @AndroidEntryPoint 到 Application, Activity, Fragment 等
// 移除组件实例化和传播代码
// 移除 dagger.android 引用
```

### 2. 使用 EntryPoints 访问组件接口

**Java 版本**:
```java
public final class MyApplication extends Application {
  MySingletonComponent component() {
    return component;
  }
}

@InstallIn(SingletonComponent.class)
@EntryPoint
interface AggregatorEntryPoint extends LegacyInterface, ... {
}

@HiltAndroidApp
public final class MyApplication extends Application {
  // 返回类型变为 AggregatorEntryPoint，但实现了旧组件的所有接口
  AggregatorEntryPoint component() {
    // 使用 EntryPoints 获取 AggregatorEntryPoint 实例
    return EntryPoints.get(this, AggregatorEntryPoint.class);
  }
}
```

**Kotlin 版本**:
```kotlin
class MyApplication : Application() {
  fun component(): MySingletonComponent {
    return component
  }
}

@InstallIn(SingletonComponent::class)
@EntryPoint
interface AggregatorEntryPoint : LegacyInterface, ... {
}

@HiltAndroidApp
class MyApplication : Application() {
  fun component(): AggregatorEntryPoint {
    return EntryPoints.get(this, AggregatorEntryPoint::class.java)
  }
}
```

### 3. 迁移组件入口点到 Hilt

**Java 版本**:
```java
// 原始组件
@Component
@Singleton
interface MySingletonComponent extends FooInjector, BarInjector {
    void inject(MyApplication myApplication);
    Foo getFoo();
}

// 迁移后
@InstallIn(SingletonComponent.class)
@EntryPoint
interface AggregatorEntryPoint extends FooInjector, BarInjector {
  void inject(MyApplication myApplication);
  Foo getFoo();
}
```

**Kotlin 版本**:
```kotlin
// 原始组件
@Component
@Singleton
interface MySingletonComponent : FooInjector, BarInjector {
    fun inject(myApplication: MyApplication)
    fun getFoo() : Foo
}

// 迁移后
@InstallIn(SingletonComponent::class)
@EntryPoint
interface AggregatorEntryPoint : FooInjector, BarInjector {
  fun inject(myApplication: MyApplication)
  fun getFoo() : Foo
}
```

---

## 参考链接

- Gradle 升级指南: https://docs.gradle.org/current/userguide/upgrading_version_8.html
- Kotlin 元数据迁移: https://github.com/JetBrains/kotlin/blob/master/libraries/kotlinx-metadata/jvm/Migration.md
- Jetpack Compose 文档: https://developer.android.com/develop/ui/compose
- Netty 4.2 迁移指南: https://github.com/netty/netty/wiki/Netty-4.2-Migration-Guide
- Dagger Hilt 迁移指南: https://dagger.dev/hilt/migration-guide
