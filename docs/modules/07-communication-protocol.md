# 通信协议文档

## 7.1 MQTT 协议

### 7.1.1 MQTT 主题结构

```
device/{deviceId}/status      # 设备状态上报
device/{deviceId}/control     # 工程师控制指令
device/{deviceId}/heartbeat   # 心跳
device/{deviceId}/response    # 操作响应
```

### 7.1.2 消息格式

#### 控制指令格式

```json
{
  "cmd": "start_vpn",
  "params": {},
  "message_id": "b2d1f4a0-...",
  "issued_at": "2026-03-22T10:00:00Z",
  "expires_at": "2026-03-22T10:05:00Z",
  "sig": "...optional..."
}
```

说明：
- `message_id` 用于去重/幂等（适配 MQTT QoS 重投），并作为 anti-replay 的基础。
- `expires_at` 用于时钟窗校验；过期消息必须拒绝。
- `sig` 的启用与校验策略由安全文档统一规定；若启用则必须校验。

#### 响应格式

```json
{
  "message_id": "b2d1f4a0-...",
  "success": true,
  "data": {},
  "error": null
}
```

### 7.1.3 控制指令列表

| 指令 | 描述 | 参数 |
|-----|------|------|
| start_vpn | 启动VPN隧道 | - |
| stop_vpn | 停止VPN隧道 | - |
| scan_wifi | 扫描WiFi | - |
| connect_wifi | 发起连接指定WiFi请求 | ssid（连接通常需用户确认；不传明文密码） |
| disconnect_wifi | 断开WiFi | - |
| get_status | 获取设备状态 | - |
| get_wifi_info | 获取WiFi信息 | - |

## 7.2 REST API

### 7.2.1 配对接口

| 接口 | 方法 | 描述 |
|-----|------|------|
| /api/pair | POST | 创建配对会话 |
| /api/pair/{code} | GET | 验证配对码 |
| /api/device/{id}/status | GET | 获取设备状态 |
| /api/devices | GET | 获取设备列表 |
| /api/socks/sessions | POST | 创建 SOCKS 会话（绑定 deviceId，签发短期会话令牌） |

### 7.2.2 配对流程

```
1. 客户端生成6位数字识别码
2. 客户端连接到云服务器
3. 工程师输入识别码
4. 服务器验证配对
5. 建立会话关系
```

---

## 相关文档

- [连接管理模块](./03-connection-module.md) - 了解 MQTT 连接细节
- [服务端设计](./08-server-design.md) - 了解服务端架构
- [安全性设计](./09-security.md) - 了解通信安全措施
