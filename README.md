# NetProxyGateway

面向远程网络协助场景的 Android 客户端与 Go 服务端仓库。https://github.com/01luyicheng/NetProxyGateway

## 工作入口

1. 首先阅读 [AGENTS.md](AGENTS.md)
2. Android 代码入口：`android/app/src/main/java/com/netproxy/gateway/`
3. Android 测试入口：`android/app/src/test/java/com/netproxy/gateway/`

## 验证命令

- 构建：`make android-build`
- 测试：`make android-test`

## 目录

```text
android/
server/
docs/
AGENTS.md
```

## 当前范围

- Android 客户端
- Go 服务端组件
- 面向连接、VPN、代理、WiFi 与状态流相关问题的实现与验证
