🧹 移除 ConnectionSessionManager.kt 中未使用的 PooledSocks5Connection 导入

🎯 **What:**
移除了 `android/app/src/main/java/com/netproxy/gateway/vpn/ConnectionSessionManager.kt` 文件中未使用的 `com.netproxy.gateway.proxy.PooledSocks5Connection` 导入。

💡 **Why:**
该导入语句在文件中存在，但该类在代码中并未被使用。移除它可以清理死代码，改善代码库的整洁度、可维护性和可读性。

✅ **Verification:**
*   运行了 `cd android && ./gradlew assembleDebug --no-daemon` 确认代码可以正常编译。
*   运行了相关的单元测试 `cd android && ./gradlew :app:testDebugUnitTest --tests "com.netproxy.gateway.vpn.*" --no-daemon`，所有测试均通过，确认修改没有引入任何回退。

✨ **Result:**
代码更加简洁，去除了不必要的依赖引用，提升了整体的代码健康度。
