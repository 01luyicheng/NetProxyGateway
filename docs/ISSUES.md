# NetProxyGateway 缺陷清单（待修复）
## Critical

### C1: SSL信任所有证书配置风险 [已降级为Medium]
- **状态**: 已降级至Medium优先级
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L98-L114)
- **问题**: 生产环境已有强制检查机制，当`DEBUG=false`且`MQTT_TRUST_ALL_CERTS=true`时会抛出`IllegalStateException`阻止应用启动。建议增加构建时静态检查作为额外防护
- **风险**: 配置错误导致应用无法启动（已实现运行时防护），建议增强构建时检查
- **代码**:
  ```kotlin
  private fun createSecureSocketFactory(): SSLSocketFactory {
      // 安全检查：生产环境 (DEBUG=false) 不允许启用信任所有证书
      if (!BuildConfig.DEBUG && BuildConfig.MQTT_TRUST_ALL_CERTS) {
          throw IllegalStateException(
              "TRUST_ALL_CERTS is not allowed in production builds. " +
              "Please set MQTT_TRUST_ALL_CERTS to false in build configuration."
          )
      }
      return if (BuildConfig.MQTT_TRUST_ALL_CERTS) {
          createDevSocketFactory()
      } else {
          createProductionSocketFactory()
      }
  }
  ```
- **建议修复**: 添加构建时Lint静态检查或Gradle插件验证，确保release构建配置中`MQTT_TRUST_ALL_CERTS=false`

### C4: SOCKS5代理JSON注入风险 [待修复]
- **状态**: 待修复
- **位置**: `server/socks5-proxy/main.go` (L140)
- **问题描述**: 使用 `fmt.Sprintf` 直接拼接JSON字符串，如果 `deviceID` 或 `token` 包含特殊字符（如 `"`、换行符等），会导致JSON格式错误或注入攻击
- **风险**: Critical。可能导致API请求格式错误，或在极端情况下存在注入风险
- **代码**:
  ```go
  // L140: 问题代码
  reqBody := fmt.Sprintf(`{"device_id":"%s","token":"%s"}`, deviceID, token)
  ```
- **建议修复**:
  ```go
  payload := map[string]string{"device_id": deviceID, "token": token}
  reqBodyBytes, err := json.Marshal(payload)
  if err != nil {
      return false, fmt.Errorf("failed to marshal request: %w", err)
  }
  req, err := http.NewRequest("POST", u.String(), bytes.NewReader(reqBodyBytes))
  ```

### C5: API服务updatePairingSessionDB错误被忽略 [已修复]
- **状态**: 已修复
- **位置**: `server/api/main.go` (L688, L735)
- **问题描述**: 在`getPairingSession`和`updatePairingSession`函数中，更新session状态为"expired"时，`s.updatePairingSessionDB(session)`的错误被忽略。如果数据库写入失败，session状态可能不一致
- **风险**: Medium。数据库写入失败时状态不一致，可能导致过期session仍被视为有效
- **代码**:
  ```go
  // L688-690: 问题代码（getPairingSession）
  if time.Now().After(session.ExpiresAt) {
      session.Status = "expired"
      s.updatePairingSessionDB(session)  // 错误被忽略！
      c.JSON(http.StatusGone, gin.H{"error": ErrSessionExpired})
      return
  }
  
  // L735-737: 问题代码（updatePairingSession）
  if time.Now().After(session.ExpiresAt) {
      session.Status = "expired"
      s.updatePairingSessionDB(session)  // 错误被忽略！
      c.JSON(http.StatusGone, gin.H{"error": ErrSessionExpired})
      return
  }
  ```
- **修复方案**:
  1. 新增 `markSessionExpired` 方法提取重复逻辑
  2. 统一错误处理：数据库更新失败时返回 500 错误
  3. 两处过期检查现在都使用新方法
  
  ```go
  // 新增方法
  func (s *Server) markSessionExpired(session *PairingSession) error {
      session.Status = "expired"
      return s.updatePairingSessionDB(session)
  }
  
  // 统一错误处理
  if time.Now().After(session.ExpiresAt) {
      if err := s.markSessionExpired(session); err != nil {
          log.Printf("Failed to mark session %s as expired: %v", session.Code, err)
          c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToUpdateSession})
          return
      }
      c.JSON(http.StatusGone, gin.H{"error": ErrSessionExpired})
      return
  }
  ```
- **验证结果**:
  - `go build` 构建成功
  - `go vet` 静态检查通过
  - Subagents交叉审查通过
- **相关提交**: 修复API服务updatePairingSessionDB错误处理不一致问题

### C6: API服务generateRandomString错误处理缺失 [已修复]
- **状态**: 已修复
- **位置**: `server/api/main.go` (L265)
- **问题描述**: `generateRandomString`函数中`rand.Read(b)`的错误被忽略。虽然该函数已被标记为Deprecated，但在极端情况下（如系统熵池耗尽），可能产生不安全的随机数。
- **修复方案**: 采用方案2 - 删除废弃函数
  - 全局搜索确认`generateRandomString`无任何调用方
  - 删除整个函数（约17行代码）
  - 保留`generateSecureRandomString`作为唯一安全的随机字符串生成函数
- **验证结果**: 
  - `go build` 构建成功
  - `go test -v ./...` 所有测试通过
- **相关提交**: 删除未使用的generateRandomString函数

---

## High

### H4: SOCKS5代理DNS重绑定攻击风险
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt` (L107-131)
- **问题**: 代码已有IP范围验证（拒绝回环、链路本地、广播、保留地址，仅允许RFC1918私有地址），但DNS重绑定风险仍然存在。攻击者可能通过快速切换DNS记录绕过IP验证窗口
- **风险**: 攻击者可能通过DNS重绑定绕过IP验证，访问内网资源
- **建议修复**:
  1. 使用DNS缓存并验证解析结果
  2. 检查解析后的IP是否与目标域名匹配
  3. 考虑使用DNS-over-HTTPS (DoH)
- **代码**:
  ```kotlin
  private fun validateTargetAddress(host: String, port: Int): Boolean {
      // ... 端口验证 ...
      val inetAddr = java.net.InetAddress.getByName(host)
      val ip = inetAddr.hostAddress ?: return false
      // IP范围验证：拒绝127.x, 169.254.x, 0.0.0.0, 255.255.255.255, 224.x
      // 仅允许RFC1918私有地址
      IpAddressUtils.isPrivateIpv4Rfc1918(ip)
  }
  ```

### H5: 连接池清理竞争条件
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L355-378)
- **问题**: read锁和write锁之间连接状态可能变化
- **风险**: 清理过期连接时可能误删有效连接，或漏删无效连接
- **建议修复**:
  1. 在write锁内重新验证连接状态
  2. 或使用CopyOnWriteArrayList简化并发控制
  3. 添加单元测试验证竞争条件处理

### H7: SOCKS5连接池读取未设置超时
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L327-341)
- **问题**: `readFully`方法没有设置超时，可能永久阻塞
- **风险**: 线程被永久阻塞，连接池资源耗尽
- **建议修复**:
  1. 为socket读取操作设置超时
  2. 使用带超时的读取方法
  3. 添加心跳检测机制

### H8: MQTT TLS证书固定配置可能为空
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L131-138)
- **问题**: 当`MQTT_TLS_PUBLIC_KEY_PINS`为空时，仅记录警告，仍使用默认CA验证
- **风险**: 生产环境可能意外使用不安全的证书验证方式
- **建议修复**:
  1. 生产环境强制要求配置证书固定
  2. 空配置时抛出异常而非仅警告
  3. 添加构建时检查确保配置正确

### S1: SOCKS5代理relay函数goroutine泄漏 [已修复]
- **状态**: 已修复
- **位置**: `server/socks5-proxy/main.go` (`relay`)
- **修复内容**:
  1. `relay` 改为等待两个方向的转发 goroutine 都退出后再返回
  2. 使用 `sync.Once` 在首个方向结束时统一关闭两端连接，确保另一个方向可退出
  3. 增加 `isExpectedRelayError`，过滤连接主动关闭场景下的预期错误
- **验证结果**:
  - 在 `server/socks5-proxy` 目录执行：`go test -run TestRelay_ClosesPeerConnectionOnHalfClose -count=1 ./...` 通过
  - 在 `server/socks5-proxy` 目录执行：`go test -race ./...` 通过（前置：Windows 下已安装并配置 gcc）
  - 在 `server/socks5-proxy` 目录执行：`staticcheck ./...` 通过
- **相关测试**: `server/socks5-proxy/main_test.go` 新增 `TestRelay_ClosesPeerConnectionOnHalfClose`

### H20: API服务JWT令牌验证不完善 [待修复]
- **状态**: 待修复
- **位置**: `server/api/main.go` (L528-537)
- **问题描述**: JWT解析后未明确验证 `exp`（过期时间）、`iat`（签发时间）、`nbf`（生效时间）等声明。虽然 `jwt.Parse` 默认会验证 `exp`，但代码没有明确检查验证失败的具体原因，可能混淆不同类型的认证错误
- **风险**: 高。无法区分令牌过期、无效签名、格式错误等不同错误类型，不利于调试和安全审计
- **代码**:
  ```go
  // L469-484: 问题代码
  token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
      if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
          return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
      }
      return s.jwtSecret, nil
  })

  if err != nil || !token.Valid {
      c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
      return
  }
  ```
- **建议修复**:
  ```go
  if claims, ok := token.Claims.(jwt.MapClaims); ok {
      // 验证过期时间
      if exp, ok := claims["exp"].(float64); ok {
          if time.Now().Unix() > int64(exp) {
              c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "token expired"})
              return
          }
      }
      // 验证生效时间
      if nbf, ok := claims["nbf"].(float64); ok {
          if time.Now().Unix() < int64(nbf) {
              c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "token not yet valid"})
              return
          }
      }
  }
  ```

### H21: API服务登录限流器内存泄漏 [待修复]
- **状态**: 待修复
- **位置**: `server/api/main.go` (L71-72)
- **问题描述**: `loginAttempts` 映射表没有定期清理机制。如果攻击者使用大量不同IP进行尝试，可能导致内存无限增长
- **风险**: 高。潜在的DoS攻击向量，可能导致服务OOM
- **代码**:
  ```go
  // L71-72
  loginAttempts   map[string]*LoginAttempt // ip -> attempts
  loginAttemptsMu sync.RWMutex
  ```
- **建议修复**: 添加定期清理协程，类似于 `cleanupExpiredSessions()`:
  ```go
  func (s *Server) cleanupExpiredLoginAttempts() {
      s.loginAttemptsMu.Lock()
      defer s.loginAttemptsMu.Unlock()
      
      now := time.Now()
      for ip, attempt := range s.loginAttempts {
          if now.Sub(attempt.LastAttempt) > 24*time.Hour {
              delete(s.loginAttempts, ip)
          }
      }
  }
  ```

### H22: SOCKS5代理WebSocket读取无超时 [已修复]
- **状态**: 已修复
- **位置**: `server/socks5-proxy/main.go` (`readLoop`)
- **修复内容**:
  1. 在 `readLoop` 中增加 `SetReadDeadline`（初始与每次循环刷新）
  2. 增加 `SetPongHandler` 续期读超时
  3. 对读超时错误进行显式分支处理并退出连接循环，避免无限挂起
- **验证结果**:
  - 在 `server/socks5-proxy` 目录执行：`go test ./...` 通过
  - 在 `server/socks5-proxy` 目录执行：`go test -race ./...` 通过（前置：Windows 下已安装并配置 gcc）
  - 在 `server/socks5-proxy` 目录执行：`staticcheck ./...` 通过
  - 注：`readLoop` 超时分支专项回归测试待补充

### H23: SOCKS5代理StreamConn双重锁嵌套 [已修复]
- **状态**: 已修复
- **位置**: `server/socks5-proxy/main.go` (`StreamConn.Write`, `StreamConn.Close`, `ConnectThroughTunnel`)
- **修复内容**:
  1. `StreamConn.Write` 不再在持有 `s.mu` 时执行网络写操作
  2. 先在 `s.mu` 内复制连接/锁引用后释放，再进入写锁与 IO，降低锁嵌套风险
  3. 为 `Write` 与 `Close` 的 websocket 写入增加 `SetWriteDeadline`，避免锁持有期间无限阻塞
  4. **修复锁释放问题**: 使用 `defer` 确保 `writeMu` 在 panic 时也能释放，避免死锁
- **验证结果**:
  - 在 `server/socks5-proxy` 目录执行：`go test ./...` 通过
  - 在 `server/socks5-proxy` 目录执行：`go test -race ./...` 通过（前置：Windows 下已安装并配置 gcc）
  - 在 `server/socks5-proxy` 目录执行：`staticcheck ./...` 通过
  - 注：`StreamConn.Write/Close` 并发竞争专项回归测试待补充

### H24: Tunnel服务CheckOrigin允许所有来源 [待修复]
- **状态**: 待修复
- **位置**: `server/tunnel/main.go` (L222-225)
- **问题描述**: `CheckOrigin` 返回 `true` 允许所有来源，可能导致CSRF攻击
- **风险**: 高。WebSocket连接可能被恶意网站利用
- **代码**:
  ```go
  // L222-225
  upgrader: websocket.Upgrader{
      CheckOrigin: func(r *http.Request) bool {
          // 在生产环境中应该检查来源
          return true
      },
  ```
- **建议修复**:
  ```go
  CheckOrigin: func(r *http.Request) bool {
      origin := r.Header.Get("Origin")
      allowedOrigins := []string{"https://trusted-domain.com", "https://app.example.com"}
      for _, allowed := range allowedOrigins {
          if origin == allowed {
              return true
          }
      }
      return false
  },
  ```

### H25: Tunnel服务心跳检测竞态条件 [待修复]
- **状态**: 待修复
- **位置**: `server/tunnel/main.go` (L336-359)
- **问题描述**: `tunnel.Conn` 可能在 `WriteControl` 调用期间被其他goroutine设置为 `nil` 或关闭，导致panic
- **风险**: 中-高。可能导致服务panic崩溃
- **代码**:
  ```go
  // L336-359
  if err := tunnel.Conn.WriteControl(websocket.PingMessage, []byte{}, time.Now().Add(10*time.Second)); err != nil {
      log.Printf("Failed to send ping: %v", err)
      tunnel.Close()
      return
  }
  ```
- **建议修复**: 在调用前检查并加锁保护

### H26: Android 13+ 语言状态双数据源导致回显不一致 [已修复]
- **状态**: 已修复
- **位置**:
  - `android/app/src/main/java/com/netproxy/gateway/i18n/AppLocale.kt` (`applyLanguage`, `getSelectedLanguageTag`, `wrap`)
  - `android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt` (`LanguageSettingsCard`)
- **问题描述**: Android 13+ 分支使用 `AppCompatDelegate.setApplicationLocales()` 切换语言，但最初未同步写入 SharedPreferences，导致设置页回显与实际语言可能不一致。
- **修复方案**:
  1. `AppLocale.applyLanguage()` 中统一先归一化并持久化 language tag，再执行平台分支逻辑
  2. Android 13+ 与低版本统一通过 `getSelectedLanguageTag()` 回读同一状态源
  3. 新增 `AppLocaleTest` 验证 Android 13+/低版本下持久化与回读一致
- **验证结果**:
  - `:app:testDebugUnitTest --tests com.netproxy.gateway.i18n.AppLocaleTest` 通过
  - `:app:testDebugUnitTest` 全量通过
  - `assembleDebug` 通过

### H27: 运行中前台服务通知不随语言切换即时刷新 [已修复]
- **状态**: 已修复
- **位置**:
  - `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
  - `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyService.kt`
  - `android/app/src/main/java/com/netproxy/gateway/i18n/AppLocale.kt`
- **问题描述**: 当前语言切换主要触发 Activity 层更新；运行中的前台服务通知文案在服务启动时创建后未主动刷新，导致 UI 语言切换后通知仍显示旧语言。
- **修复方案**:
  1. 在 `AppLocale` 中添加语言变更回调机制：`registerLanguageChangeListener(listenerId, ...)` 和 `unregisterLanguageChangeListener(listenerId)`
  2. 在 `applyLanguage()` 执行后触发回调，通知所有监听者语言已变更
  3. `VpnService.onCreate()` 中注册监听，收到回调时调用 `updateNotification()` 刷新前台通知
  4. `Socks5ProxyService.onCreate()` 中注册监听，收到回调时刷新前台通知
  5. 两个服务的 `onDestroy()` 中按 listenerId 注销监听，确保资源正确释放
  6. 仅在服务 RUNNING 状态时刷新通知，避免启动/停止过程中的不必要操作
  7. 通知文案改为 `AppLocale.getString(...)` 动态读取，避免依赖旧 Service Context locale
  8. 回调派发增加异常隔离，单个监听器异常不会中断其他监听器与主流程
- **代码改动**:
  - `AppLocale.kt`: 添加多监听器管理与异常隔离；`applyLanguage()` 末尾触发回调
  - `VpnService.kt`: `onCreate()` 注册监听、添加 `registerLanguageChangeListener()`、`unregisterLanguageChangeListener()`、`updateNotification()` 方法、`onDestroy()` 注销监听
  - `Socks5ProxyService.kt`: `onCreate()` 注册监听、添加 `registerLanguageChangeListener()`、`updateNotification()` 方法、`onDestroy()` 注销监听
- **验证结果**:
  - 单元测试：329/329 通过（新增 2 个回调测试）
  - APK 构建：BUILD SUCCESSFUL
  - 修复风险：低。使用简单回调机制，最小改动，避免复杂的事件总线或观察者模式

---

## Medium

### M21: i18n关键路径测试覆盖不足 [已修复]
- **状态**: 已修复
- **位置**:
  - `android/app/src/main/java/com/netproxy/gateway/i18n/AppLocale.kt`
  - `android/app/src/test/java/com/netproxy/gateway/i18n/AppLocaleTest.kt`
- **问题描述**: 新增测试主要覆盖 ViewModel 的文案读取，缺少语言切换关键路径测试（tag 正规化、多次切换、服务通知刷新链路）。
- **修复方案**:
  1. 增加 `AppLocale` 单元测试覆盖关键路径：
     - `normalizeLanguageTag_rejectedUnsupportedTag()`: 验证非支持语言被正规化为 null
     - `normalizeLanguageTag_trimsWhitespaceAndNormalizes()`: 验证空格清理和正规化
     - `normalizeLanguageTag_emptyStringBecomesNull()`: 验证空字符串处理
     - `multipleSwitches_sequentialCalls_persists()`: 验证多次切换持久化（关键路径）
     - `setSelectedLanguageTag_directCall_persists()`: 验证直接调用持久化
     - `wrap_withoutLanguageTag_returnsOriginalContext()`: 验证无设置时返回原始 context
     - `wrap_withValidLanguageTag_wrapsContext()`: 验证有效语言标签时包装 context
     - `languageChangeCallback_triggersOnApplyLanguage()`: 验证语言变更触发回调（H27 关键链路）
    - `languageChangeCallback_multipleCallbacks()`: 验证多次切换多次触发回调
    - `languageChangeCallback_multipleListeners_allReceiveEvents()`: 验证多监听器并存时都能收到事件
    - `languageChangeCallback_oneListenerThrows_othersStillRun()`: 验证单监听器异常不影响其他监听器
    - `wrap_usesLatestPreferenceWithoutContextRecreation()`: 验证语言偏好更新后 wrap 可读取最新 locale（不依赖 Context 重建）
  2. 覆盖 normalization、persistence、wrap 和回调机制的所有关键分支
- **代码改动**:
  - `AppLocaleTest.kt`: 扩展关键路径测试到 15 个测试方法，覆盖回调、多监听器、异常隔离与 locale wrap 场景
- **验证结果**:
  - 单元测试：329/329 通过（新增 8 个测试）
  - 覆盖率：所有关键路径都有对应测试，包括 Android 13+ 和低版本分支
  - 测试质量：使用 TDD 方式编写，先失败后通过，确保测试有效性

### M1: 边界条件：IP地址解析验证
- **位置**: `android/app/src/main/java/com/netproxy/gateway/utils/IpAddressUtils.kt` (L6-L18)
- **问题**: `isPrivateIpv4Rfc1918`方法本身没有验证每个octet是否在0-255范围内
- **实际情况**: `validateIpv4WithResult`方法已实现完整的octet范围验证（0-255），可供调用方使用
- **建议修复**: 确保调用方在使用`isPrivateIpv4Rfc1918`前先调用`validateIpv4WithResult`进行验证，或统一使用带验证的方法

### M2: WiFi管理器权限检查不一致
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/GatewayWifiManager.kt` (L211-L226)
- **问题**: 同一功能有两个版本，一个静默失败，一个返回错误
- **风险**: 调用方无法统一处理错误，可能导致未预期的行为
- **建议修复**: 统一错误处理方式，移除静默失败版本

### M3: Root检测执行命令未超时
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/RootDetector.kt` (L204-214, L240-L253)
- **问题**: `process.waitFor()`没有设置超时，如果命令挂起会阻塞线程
- **风险**: 线程被永久阻塞，影响应用响应
- **建议修复**: 使用`waitFor(timeout, TimeUnit)`替代

### M9: TCP回包状态管理不完整
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L668-L687)
- **问题**: 序列号和确认号固定为0，不符合TCP协议
- **风险**: 与某些TCP实现不兼容，可能导致连接异常
- **建议修复**: 正确管理TCP序列号和确认号

### M11: 连接池状态检查与清理的竞态条件
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L119-L148)
- **问题**: 代码在 read 锁内收集无效连接列表，然后在 write 锁外执行清理操作。在 read 锁释放后到 write 锁获取前的时间窗口内，连接状态可能已发生变化，导致清理操作基于过期的状态信息
- **风险**: 可能清理有效连接或保留无效连接
- **建议修复**: 在write锁内重新验证连接状态

### L2: TODO注释未处理
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L458)
- **问题**: 存在未处理的TODO注释，涉及安全配置
- **风险**: 已知问题被遗漏
- **建议修复**: 处理TODO或创建正式issue跟踪

### L3: EmulatorDetector权限检查重复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/EmulatorDetector.kt`
- **问题**: 多个方法重复检查`READ_PHONE_STATE`权限
- **建议修复**: 提取权限检查为统一方法

### L5: 缺少集成测试
- **问题**: 测试主要集中在单元测试，缺少组件间集成测试
- **风险**: 组件间交互问题难以发现
- **建议修复**: 添加集成测试套件

### L13: 硬编码延迟
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` (L165-L173)
- **问题**: 使用 `delay(2000)` 等待扫描完成是脆弱的设计
- **风险**: 在不同设备上表现不一致
- **建议修复**: 使用回调或状态监听替代固定延迟

---

## 新增问题（待分类）

### N1: 双版本API增加维护负担
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/GatewayWifiManager.kt`, `AuthSessionStore.kt`
- **问题**: 每个主要操作都有两个版本（如 `startScan()` 和 `startScanWithResult()`），维护成本翻倍，容易出现版本间行为不一致
- **风险**: 中。代码冗余，维护困难
- **建议修复**: 统一使用 `AppResult` 模式，移除静默失败版本

### N2: VpnService过于庞大
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (1054行)
- **问题**: 包含 VPN 服务、数据包解析、连接管理、状态机等多个职责；`processPacket()`、`forwardViaSocks5()` 等函数超过 50 行
- **风险**: 中。代码难以理解和维护
- **建议修复**: 提取数据包解析为 `PacketParser`，提取连接管理为 `ConnectionManager`

### N3: 过度使用 @Synchronized
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/AuthSessionStore.kt`
- **问题**: 所有方法都标记 `@Synchronized`，即使只是读取操作；使用类实例作为锁，粒度太粗
- **风险**: 低。可能影响并发性能
- **建议修复**: 使用 `ReentrantReadWriteLock` 区分读写锁，或使用 `ConcurrentHashMap` 等并发集合

### N4: DI模块接口设计混乱
- **位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleInterfaces.kt`, `AppModule.kt`
- **问题**: 大量接口被标记 `@Deprecated`，但 `AppModule.kt` 中绑定的仍是已弃用接口
- **风险**: 中。编译器警告噪音，技术债务累积
- **建议修复**: 清理已弃用接口，更新 DI 绑定使用新接口

### N5: 状态管理分散
- **位置**: 多处
- **问题**: VPN 状态多处定义 - `VpnState` 在 `VpnService.kt`，`MqttConnectionState` 在 `MqttConnectionManager.kt`，`WiFiState` 在 `ModuleCoordinator.kt`
- **风险**: 低。状态定义分散，不利于统一管理
- **建议修复**: 统一状态定义到 `result` 包或专门的状态管理模块

### N6: 测试命名不一致
- **位置**: `android/app/src/test/java/`
- **问题**: 测试命名风格不一致，有的使用下划线命名（`vpnState_values()`），有的使用驼峰命名（`socks5Integration_connectionPoolConfig_defaults()`）
- **风险**: 低。影响代码可读性
- **建议修复**: 统一使用一种命名规范（推荐下划线命名法）

### N7: 测试质量不高
- **位置**: `android/app/src/test/java/com/netproxy/gateway/vpn/VpnServiceTest.kt`
- **问题**: 存在大量测试数据类自动生成方法（`equals()`、`hashCode()`、`toString()`）的测试，对业务价值贡献极低
- **风险**: 低。增加维护成本
- **建议修复**: 移除对自动生成方法的测试，专注于业务逻辑测试

### N8: 核心业务逻辑测试缺失
- **位置**: 测试目录
- **问题**: `processVpnTraffic()`、`forwardViaSocks5()`、`startHeartbeat()` 等核心业务逻辑缺乏测试
- **风险**: 高。回归风险大
- **建议修复**: 添加核心业务逻辑的单元测试和集成测试

### N9: 日志级别使用不当
- **位置**: 多处
- **问题**: 权限检查失败使用 `warn`，连接池正常清理也使用 `warn`；缺乏结构化日志
- **风险**: 低。日志噪音，不利于问题排查
- **建议修复**: 调整日志级别，使用结构化日志或 MDC

### N10: 监控指标缺失
- **位置**: 全局
- **问题**: 没有性能指标收集（连接建立时间、流量统计等），没有健康检查端点，没有错误上报机制
- **风险**: 中。难以发现和诊断线上问题
- **建议修复**: 添加关键指标收集和上报机制

### N11: 硬编码默认值不安全
- **位置**: `android/app/build.gradle.kts`
- **问题**: `mqttBrokerUrlTlsDebug` 等配置使用 `localhost` 作为默认值，可能意外连接到错误服务器
- **风险**: 低。仅影响 debug 构建
- **建议修复**: 移除默认值，强制在构建时配置

### N12: 运行时配置缺失
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L96-99)
- **问题**: DNS 服务器列表硬编码，连接池参数硬编码，无法动态调整
- **风险**: 低。灵活性不足
- **建议修复**: 将配置提取到配置文件或远程配置中心

### N13: 已弃用API使用
- **位置**: 多处
- **问题**: 编译警告显示大量使用已弃用 API（`EncryptedSharedPreferences`、`WifiConfiguration`、`NioEventLoopGroup`、`hiltViewModel()` 等）
- **风险**: 中。未来 Android 版本可能移除这些 API
- **建议修复**: 逐步迁移到新 API

### N14: gorilla/websocket 已归档
- **位置**: `server/socks5-proxy/go.mod`, `server/tunnel/go.mod`
- **问题**: `gorilla/websocket` 库已被归档不再维护，存在技术债务
- **风险**: 中。安全漏洞无法及时修复
- **建议修复**: 迁移到 `nhooyr/websocket` 或 `gobwas/ws`

### N15: Paho MQTT 维护不活跃
- **位置**: `android/app/build.gradle.kts`
- **问题**: Eclipse Paho MQTT 项目维护不活跃
- **风险**: 中。新功能和 bug 修复可能延迟
- **建议修复**: 评估迁移到 HiveMQ MQTT Client 或 KMQTT

---

## Medium Severity

### M14: Android 10+ WiFi连接限制（API废弃）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/GatewayWifiManager.kt` (L322-361)
- **问题描述**: 使用`WifiConfiguration` API在Android 10+上已被废弃，且Android 10+对后台应用启动WiFi连接有限制，可能导致连接失败或需要用户手动确认
- **风险**: 中。代码已实现适配，影响有限，但需要引导用户手动操作
- **建议修复**:
  - 引导用户手动连接WiFi
  - 使用Suggestion API（需要用户批准）
  - 使用NetworkSpecifier进行请求（Android 10+）

### M15: 5G网络切换问题（系统行为）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/NetworkStateManager.kt`
- **问题描述**: 5G NSA/SA模式切换、5G与4G切换时，网络接口可能发生变化，当前代码仅检测基础网络类型（WiFi/Cellular/Ethernet），未针对5G网络变化做特殊处理，可能导致VPN隧道中断
- **风险**: 中。网络切换时可能导致连接中断
- **建议修复**:
  - 监听网络变化并自动重建VPN连接
  - 实现连接保活和快速恢复机制
  - 提示用户在远程协助期间保持网络稳定

---

## High Severity

### H19: 电池优化和后台执行限制
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **问题描述**: 
  - Android Doze模式和App Standby可能限制后台网络活动
  - 在某些厂商ROM（如小米、华为）上VPN服务可能被强制停止或限制网络访问
  - 未检测是否已被用户加入电池优化白名单
- **风险**: 高。影响VPN服务持续运行，在省电模式下可能导致连接中断
- **建议修复**:
  - 检测电池优化白名单状态并提示用户
  - 引导用户将应用加入白名单
  - 实现连接状态监控和自动重连
  - 检测被杀死后由系统广播唤醒

---

## Low Severity

### L6: 系统私有DNS设置未检测
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnDnsConfig.kt`
- **问题描述**: VpnDnsConfig仅提供基础DNS服务器解析和路由判断功能，未检测Android系统的"私有DNS"(DNS over HTTPS/TLS)设置。当用户启用此功能时，系统的DNS查询可能被强制重定向到加密DNS服务器，影响VPN的DNS分流逻辑
- **风险**: 低。可引导用户解决
- **建议修复**:
  - 检测私有DNS设置状态并提示用户
  - 引导用户关闭私有DNS或设置为自动
  - 在VPN中强制指定DNS服务器

### L7: 随机MAC地址功能未处理
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/GatewayWifiManager.kt`
- **问题描述**: WifiManager未检测或处理Android的随机MAC地址功能。当系统使用随机MAC连接WiFi时，某些企业级AP可能基于MAC地址实施访问控制，导致内网访问受限
- **风险**: 低。特定企业场景下可能出现问题
- **建议修复**:
  - 检测随机MAC设置并提示用户
  - 引导用户为特定WiFi网络关闭随机MAC
  - 在企业场景下提供相关说明文档

---

## Medium Severity

### M12: MQTT 发布和订阅未检查连接状态
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L352-364, L370-388)
- **问题**: `publishWithResult` 和 `subscribeWithResult` 方法只检查 `mqttClient != null`，但不检查连接状态（`_connectionState.value == MqttConnectionState.Connected`）。这可能导致在客户端正在连接或断开时尝试发布/订阅消息，操作会失败但错误信息不明确
- **风险**: 中。在连接不稳定或重连过程中，可能导致消息发布/订阅失败，增加调试难度
- **建议修复**:
  1. 在 `publishWithResult` 和 `subscribeWithResult` 中添加连接状态检查
  2. 如果未连接，返回明确的错误信息或等待连接完成
  3. 考虑添加超时机制，避免无限等待
- **代码示例**:
  ```kotlin
  fun publishWithResult(topic: String, payload: String, qos: Int = 0): AppResult<Unit> {
      val client = mqttClient ?: return AppResult.error(IllegalStateException("MQTT client is not connected"))
      
      // 添加连接状态检查
      if (_connectionState.value != MqttConnectionState.Connected) {
          return AppResult.error(IllegalStateException("MQTT client is not connected, current state: ${_connectionState.value}"))
      }
      
      // ... 其余代码
  }
  ```

### M13: MQTT 重连延迟递增逻辑问题
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L311-L321)
- **问题**: `scheduleReconnect` 方法在**重连前**就增加延迟（`reconnectDelay = minOf(reconnectDelay * 2, MAX_RECONNECT_DELAY)`），导致第一次重连的延迟是 10 秒而不是 5 秒。正确的逻辑应该是在重连**失败后**再增加延迟
- **风险**: 低。会导致重连时间比预期更长，影响用户体验
- **建议修复**:
  1. 将延迟递增逻辑移到重连尝试之后
  2. 或者在重连成功后重置延迟为初始值
  3. 考虑使用指数退避算法的标准实现
- **代码示例**:
  ```kotlin
  private fun scheduleReconnect(deviceId: String, authToken: String, generation: Long) {
      reconnectJob?.cancel()
      reconnectJob = scope.launch {
          delay(reconnectDelay)
          if (!shouldStayConnected || generation != connectionGeneration.get()) {
              return@launch
          }
          
          // 先重连
          connect(deviceId, authToken)
          
          // 如果重连失败，再增加延迟（在 connect 方法中处理）
          // 或者在重连成功后重置延迟
          if (_connectionState.value == MqttConnectionState.Connected) {
              reconnectDelay = INITIAL_RECONNECT_DELAY
          }
      }
  }
  ```

### H10: VpnService stopVpn() 竞态条件
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L930-972)
- **问题验证**:
  - `isStopping` 原子标志与 `_status` StateFlow 是两个独立的状态源
  - 线程A通过CAS设置`isStopping=true`后，线程B可能修改`_status`状态
  - 在L933读取isStopping和L939读取_status之间存在时间窗口
  - `finally`块中重置`isStopping`，但状态可能已被其他线程改变
- **竞态场景**:
  ```
  T1: 线程A CAS成功 isStopping=true, _status=RUNNING
  T2: 线程B CAS失败返回
  T3: 线程A在L939前被挂起
  T4: 其他代码修改 _status=STOPPING
  T5: 线程A读取 currentState=STOPPING，重置isStopping=false并返回
  T6: 线程B现在可以CAS成功，重复执行停止逻辑
  ```
- **风险**: 中。可能导致重复执行停止逻辑，状态不一致
- **触发条件**: 快速连续调用stopVpn()、onRevoke()和手动停止并发、系统回收与手动停止并发
- **代码分析**:
  ```kotlin
  // L930-972: 问题代码
  private fun stopVpn() {
      if (!isStopping.compareAndSet(false, true)) return  // L933: 获取标志
      
      val currentState = _status.value.state  // L939: 读取状态 - 可能已被其他线程修改
      if (currentState == VpnState.STOPPED || currentState == VpnState.STOPPING) {
          isStopping.set(false)
          return
      }
      // ... 清理操作
  }
  ```
- **建议修复**:
  ```kotlin
  private fun stopVpn() {
      // 先读取当前状态
      val currentState = _status.value.state
      if (currentState == VpnState.STOPPED || currentState == VpnState.STOPPING) {
          return
      }
      
      // 再尝试设置停止标志
      if (!isStopping.compareAndSet(false, true)) {
          return
      }
      
      // 双重检查
      if (_status.value.state == VpnState.STOPPED) {
          isStopping.set(false)
          return
      }
      // ...
  }
  ```

### H11: writeBufferPool 线程安全问题
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L120-122, L619-622)
- **问题验证**:
  - `getAndIncrement() % writeBufferPool.size` 不是原子操作
  - 虽然`getAndIncrement()`是原子的，但取模和数组访问是分开的操作
  - 当并发线程数超过缓冲区池大小(4)时，多个线程可能获取到同一个缓冲区
  - **整数溢出风险**: `writeBufferIndex.getAndIncrement()`在达到`Int.MAX_VALUE`时溢出变为负数，取模后产生负数索引，导致`ArrayIndexOutOfBoundsException`
- **竞态场景**:
  ```
  线程1-4: 分别获取 buffer[0], buffer[1], buffer[2], buffer[3]
  线程5: writeBufferIndex=4, 4%4=0, 获取buffer[0]（正在被线程1使用！）
  ```
- **风险**: 高。高并发时可能导致数据竞争、回包数据损坏、崩溃；整数溢出时直接导致`ArrayIndexOutOfBoundsException`
- **触发条件**: 超过4个线程同时处理回包（高流量场景）；或长时间运行后索引溢出
- **代码分析**:
  ```kotlin
  // L120-122, L619-622: 问题代码
  private val writeBufferPool = Array(4) { ByteArray(PACKET_BUFFER_SIZE) }
  private val writeBufferIndex = AtomicInteger(0)
  
  private fun getWriteBuffer(): ByteArray {
      val index = writeBufferIndex.getAndIncrement() % writeBufferPool.size
      return writeBufferPool[index]  // 可能抛出负数索引异常
  }
  ```
- **建议修复**:
  ```kotlin
  // 方案1: 使用ThreadLocal（推荐）
  private val writeBuffer = ThreadLocal<ByteArray>()
  
  private fun getWriteBuffer(): ByteArray {
      return writeBuffer.get() ?: ByteArray(PACKET_BUFFER_SIZE).also {
          writeBuffer.set(it)
      }
  }
  
  // 方案2: 使用同步块
  @Synchronized
  private fun getWriteBuffer(): ByteArray {
      val index = writeBufferIndex.getAndIncrement() % writeBufferPool.size
      return writeBufferPool[index]
  }
  
  // 方案3: 修复整数溢出（如果保留原方案）
  private fun getWriteBuffer(): ByteArray {
      val index = (writeBufferIndex.getAndIncrement().toLong() and 0xFFFFFFFFL % writeBufferPool.size).toInt()
      return writeBufferPool[index]
  }
  ```

### H12: activeConnections 复合操作非原子
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L426-427, L467, L429-432, L470)
- **问题验证**:
  - 虽然使用 `ConcurrentHashMap`，但"检查-获取-更新"模式不是原子的
  - L429: `activeConnections[connectionKey]` 获取
  - L430: `existingSession?.pooledConnection?.isValid()` 检查
  - L470: `activeConnections[connectionKey]?.updateActivity()` 再次获取可能不同对象
  - 在检查和使用之间，连接可能被其他线程清理
- **竞态场景**:
  ```
  线程A (forwardViaSocks5)          线程B (cleanupStaleConnections)
  -------------------------------   --------------------------------
  val existing = activeConnections[key]
                                    activeConnections.remove(key)
                                    pool.returnConnection(conn)
  existing.pooledConnection.isValid()  // 访问已关闭的连接！
  ```
- **风险**: 高。可能导致使用无效连接、空指针异常、IO异常或重复归还
- **触发条件**: 连接刚好在30秒超时过期时、清理任务与转发并发执行、高流量场景
- **代码分析**:
  ```kotlin
  // L429-432, L470: 问题代码
  val existingSession = activeConnections[connectionKey]  // 获取
  val pooledConn = if (existingSession?.pooledConnection?.isValid() == true) {  // 检查
      existingSession.pooledConnection
  } else { ... }
  // ...
  activeConnections[connectionKey]?.updateActivity()  // 再次获取，可能不同对象
  ```
- **建议修复**:
  ```kotlin
  // 使用compute保证原子性
  activeConnections.compute(connectionKey) { key, existingSession ->
      if (existingSession?.pooledConnection?.isValid() == true) {
          existingSession.updateActivity()
          existingSession
      } else {
          // 创建新连接
          existingSession?.pooledConnection?.let { pool.returnConnection(it) }
          val conn = pool.borrowConnection(...)
          ConnectionSession(...)
      }
  }?.let { session ->
      // 使用session发送数据
  }
  ```

### H13: constructReturnPacket 潜在数组越界
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L628-687)
- **问题验证**:
  - 未验证 `buffer` 的大小是否足够容纳 `totalLen`
  - `payloadLen` 可能很大（SOCKS5返回大数据块），导致数组越界
  - `session.virtualSrcIp.split(".")` 假设IP格式正确，可能抛出异常
- **问题1 - 数组越界**:
  - writeBufferPool大小为32KB (PACKET_BUFFER_SIZE)
  - 如果payloadLen > 32728字节，会发生ArrayIndexOutOfBoundsException
  - 触发条件: SOCKS5代理返回大文件数据、视频流、合并的数据包
- **问题2 - IP解析异常**:
  - `virtualSrcIp.split(".")`可能抛出NumberFormatException
  - `srcIpParts[n]`可能抛出IndexOutOfBoundsException
  - **IP格式验证缺失**: `split(".")`和`toInt()`没有验证IP格式，非法IP格式可能导致崩溃
  - 虽然virtualSrcIp由系统生成，但缺乏防御性编程
- **风险**: 高。可能导致ArrayIndexOutOfBoundsException或NumberFormatException崩溃
- **代码分析**:
  ```kotlin
  // L628-649: 问题代码
  private fun constructReturnPacket(buffer: ByteArray, session: ConnectionSession, payloadLen: Int): Int {
      val totalLen = 20 + 20 + payloadLen  // 40 + payloadLen
      // 直接写入buffer[0..totalLen-1]，没有边界检查！
      buffer[0] = 0x45
      // ...
      // L648: 假设IP格式正确，缺少验证
      val srcIpParts = session.virtualSrcIp.split(".").map { it.toInt() }
      buffer[12] = srcIpParts[0].toByte()  // 可能越界
  }
  ```
- **建议修复**:
  ```kotlin
  private fun constructReturnPacket(buffer: ByteArray, session: ConnectionSession, payloadLen: Int): Int {
      val ipHeaderLen = 20
      val tcpHeaderLen = 20
      val totalLen = ipHeaderLen + tcpHeaderLen + payloadLen
      
      // 添加边界检查
      if (totalLen > buffer.size) {
          logger.warn("Payload too large: $payloadLen, buffer size: ${buffer.size}")
          return -1 // 或截断处理
      }
      
      // 安全的IP解析，添加格式验证
      val srcIpParts = session.virtualSrcIp.split(".").mapNotNull { it.toIntOrNull() }
      if (srcIpParts.size != 4 || srcIpParts.any { it !in 0..255 }) {
          logger.error("Invalid virtual IP format: ${session.virtualSrcIp}")
          return -1
      }
      // ...
  }
  ```

---

## Medium Severity

### M16: API服务敏感信息可能泄露 [待修复]
- **状态**: 待修复
- **位置**: `server/api/main.go` (L546, L628, L701, L743, L818, L853 等)
- **问题描述**: 多处使用 `c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})` 直接返回错误信息。虽然数据库错误已包装为通用消息，但请求绑定错误等仍可能包含敏感字段名或内部信息
- **风险**: 中。可能泄露API内部结构信息，帮助攻击者进行针对性攻击
- **代码示例**:
  ```go
  // 例如 L546, L628 等位置
  c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
  ```
- **建议修复**: 
  1. 区分内部错误和客户端错误
  2. 内部错误记录日志，向客户端返回通用错误消息
  3. 客户端错误（如验证失败）可返回具体信息，但需脱敏处理

### M17: API服务配对码生成无限循环风险 [待修复]
- **状态**: 待修复
- **位置**: `server/api/main.go` (L558-564)
- **问题描述**: 在极端情况下（数据库中已有大量配对码），`generateCode` 循环可能成为无限循环或长时间阻塞
- **风险**: 中。可能导致服务无响应
- **建议修复**: 添加最大重试次数限制

### M18: SOCKS5代理IP过滤器CIDR检查可绕过 [待修复]
- **状态**: 待修复
- **位置**: `server/socks5-proxy/main.go` (L273-297)
- **问题描述**: IPv6映射的IPv4地址（如 `::ffff:192.168.1.1`）可能无法正确匹配CIDR规则
- **风险**: 中。可能绕过IP访问控制
- **建议修复**: 统一将IPv6映射地址转换为IPv4后再匹配

### M19: Tunnel服务消息处理无速率限制 [待修复]
- **状态**: 待修复
- **位置**: `server/tunnel/main.go` (L390-413)
- **问题描述**: 没有限制单个连接的消息速率，恶意客户端可能发送大量消息导致DoS
- **风险**: 中。可能导致服务资源耗尽
- **建议修复**: 添加基于令牌桶或滑动窗口的速率限制

### M20: Tunnel服务统计信息端点无认证 [待修复]
- **状态**: 待修复
- **位置**: `server/tunnel/main.go` (L502-512)
- **问题描述**: `/stats` 端点是公开的，可能泄露敏感信息（在线设备数量）
- **风险**: 中。信息泄露
- **建议修复**: 添加认证检查或限制为本地访问

---

## Low Severity

### L8: SOCKS5代理流ID生成可预测性 [待修复]
- **状态**: 待修复
- **位置**: `server/socks5-proxy/main.go` (L689)
- **问题描述**: 流ID使用 `fmt.Sprintf("%s-%d", deviceID, time.Now().UnixNano())` 生成，依赖时间戳纳秒。虽然不存在模运算分布问题，但时间戳可预测，流ID生成逻辑可被推测
- **风险**: 低。流ID可预测性增加，可能被用于会话固定攻击
- **代码**:
  ```go
  // L689
  streamID := fmt.Sprintf("%s-%d", deviceID, time.Now().UnixNano())
  ```
- **建议修复**: 使用 `crypto/rand` 生成随机字符串替代时间戳

### L9: SOCKS5代理StreamConn DataChan可能阻塞 [待修复]
- **状态**: 待修复
- **位置**: `server/socks5-proxy/main.go` (L320, L654-657)
- **问题描述**: 如果 `DataChan` 已满且 `CloseChan` 未关闭，数据发送会阻塞或丢弃
- **风险**: 低。可能导致数据丢失或延迟
- **建议修复**: 添加默认分支处理丢弃情况，或增加缓冲区大小并监控

### L10: Tunnel服务设备状态通知无重试 [待修复]
- **状态**: 待修复
- **位置**: `server/tunnel/main.go` (L146-181)
- **问题描述**: `notifyDeviceStatus` 通知失败只是记录日志，没有重试机制。如果API服务暂时不可用，设备状态可能不一致
- **风险**: 低。状态不一致，但可接受
- **建议修复**: 添加指数退避重试机制

### L11: 日志框架混用导致输出不一致 [待修复]
- **状态**: 待修复
- **位置**: 
  - `android/app/src/main/java/com/netproxy/gateway/security/SecurityManager.kt` (L88, L93, L100, L107, L118)
  - `android/app/src/main/java/com/netproxy/gateway/NetProxyApp.kt` (L33, L38, L43, L49, L51, L66)
- **问题描述**: SecurityManager和NetProxyApp使用Android原生`Log`类，而项目其他部分使用SLF4J。导致日志格式、输出目标和级别控制不一致
- **风险**: 低。日志管理混乱，不利于统一监控和排查问题
- **代码**:
  ```kotlin
  // SecurityManager.kt - 使用Android Log
  Log.d(TAG, "Starting security check...")
  Log.w(TAG, "Root detected: ${rootResult.detectedBy}")
  
  // MqttConnectionManager.kt - 使用SLF4J
  logger.error("Heartbeat publish error")
  ```
- **建议修复**: 统一使用SLF4J日志框架，移除所有Android原生Log的使用

### L12: IP地址脱敏不充分可能泄露网络拓扑 [待修复]
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L793-807)
- **问题描述**: `redactIp()`方法对内网IP(192.168.x.x)只脱敏后两段，仍可能暴露网络拓扑信息
- **风险**: 低。日志中可能泄露内网网络结构
- **代码**:
  ```kotlin
  private fun redactIp(ip: String): String {
      val parts = ip.split(".")
      if (parts.size == 4) {
          return "${parts[0]}.${parts[1]}.*.*"  // 192.168.x.x 仍暴露前两段
      }
      return if (ip.length > 6) "${ip.take(6)}***" else "***"
  }
  ```
- **建议修复**: 对内网IP进一步脱敏，只保留第一段或使用统一掩码

---

## 新增问题（待分类）

### N16: 空catch块掩盖异常信息 [待修复]
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L60, L299-300)
- **问题描述**: 多处使用空的catch块完全忽略异常，包括Socket关闭异常和SOCKS5连接创建异常，可能掩盖严重错误
- **风险**: 中。可能遗漏关键错误信息，导致问题难以排查
- **代码**:
  ```kotlin
  fun close() {
      try {
          socket.close()
      } catch (_: Exception) {}  // 完全忽略所有异常
  }
  ```
- **建议修复**: 至少记录异常信息，区分可忽略和不可忽略的错误类型

### N19: 注释与代码实现不符 [待修复]
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L371-375)
- **问题描述**: `forwardViaWifi()`方法注释说明使用`Network.bindSocket()`，但实际代码使用`protect()`方法
- **风险**: 低。误导开发者，造成理解困难
- **代码**:
  ```kotlin
  /**
   * 通过 WiFi 网卡直连（内网流量）
   * 注意：Android VPN 模式下需要使用 Network.bindSocket()  // 注释说用bindSocket
   */
  private fun forwardViaWifi(...) {
      DatagramSocket().use { socket ->
          protect(socket)  // 实际使用的是protect
      }
  }
  ```
- **建议修复**: 更新注释，说明实际使用的是`protect()`方法及其局限性

---

## 新增问题

### N20: writeBufferPool整数溢出导致数组越界
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` L629-631
- **问题**: `AtomicInteger.getAndIncrement()`在Int.MAX_VALUE次调用后溢出为负数，取模后产生负数索引
- **风险**: Critical。应用长时间运行后必然崩溃（约2^31次调用后）
- **代码示例**:
  ```kotlin
  private fun getWriteBuffer(): ByteArray {
      val index = writeBufferIndex.getAndIncrement() % writeBufferPool.size  // 溢出后index为负数！
      return writeBufferPool[index]  // ArrayIndexOutOfBoundsException
  }
  ```
- **建议修复**: 使用ThreadLocal替代轮询，或添加溢出处理
- **关联问题**: H11的子问题

### N21: 配对码输入状态配置变更丢失
- **状态**: 已修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt` L107
- **问题**: 使用`remember`而非`rememberSaveable`保存配对码输入状态
- **风险**: High。屏幕旋转时丢失用户输入
- **引入来源**: 本次Compose UI变更引入
- **修复**: 将`remember`改为`rememberSaveable`

### N22: MainViewModel状态更新竞争条件
- **状态**: 已修复（2026-04-18 验证并修复）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` L58,66-75
- **问题**: 两次独立的`_uiState.value`更新之间存在竞态窗口
- **风险**: High。UI状态可能不一致
- **引入来源**: 本次ViewModel变更引入
- **修复**: 使用`_uiState.update{}`原子操作合并为一次更新；init块中的初始化也改为update形式
- **验证**: `./gradlew.bat :app:testDebugUnitTest` 全量测试通过

### N23: 测试直接实例化Android Service
- **状态**: 已修复
- **位置**: `android/app/src/test/java/com/netproxy/gateway/proxy/Socks5ProxyServiceTest.kt` L11,18,28
- **问题**: 直接实例化`Socks5ProxyService()`违反Android组件生命周期
- **风险**: Medium。测试不可靠
- **引入来源**: 本次测试代码变更引入
- **修复**: 使用Robolectric的`ServiceController`正确创建Service

### N24: Socks5ProxyService通知ID使用魔法数字
- **状态**: 已修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyService.kt` L81,189
- **问题**: 通知ID使用硬编码魔法数字`1`，可读性和可维护性差
- **风险**: Low。代码风格问题
- **修复**: 提取为命名常量`NOTIFICATION_ID`

### N25: MainViewModel状态更新方式不一致
- **状态**: 已修复（2026-04-18 验证并修复）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` L58,84,90,96,111,117,121,134,145,154,165,173
- **问题**: 混合使用`_uiState.value = `和`_uiState.update{}`，风格不一致
- **风险**: Low。单协程作用域内无实际竞态，但防范未来隐患
- **修复**: 统一使用`_uiState.update{}`，共修复7处直接赋值，全部改为原子更新操作
- **验证**: `./gradlew.bat :app:testDebugUnitTest` 全量测试通过

### N26: Service语言监听器残留风险
- **状态**: 已修复
- **位置**: `VpnService.kt`/`Socks5ProxyService.kt` 的`onCreate()`
- **问题**: 系统强制杀Service后监听器可能残留在单例map中
- **风险**: Low。影响小，最多2个监听器残留
- **修复**: 在`onCreate()`中先执行防御性`unregister`再`register`

### N27: ModuleCoordinator状态更新使用直接赋值
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleCoordinator.kt` L66,71,76,85
- **问题**: 使用`_connectionState.value = `、`_vpnState.value = `、`_wifiState.value = `、`_uiState.value = `直接赋值
- **风险**: Low。当前为完整对象替换而非copy操作，功能正确但风格不一致；未来若改为基于当前状态更新则存在竞争风险
- **修复**: 统一使用`update{}`原子操作
- **发现日期**: 2026-04-19（代码审查中发现）
