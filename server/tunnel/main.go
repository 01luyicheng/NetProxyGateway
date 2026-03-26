package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Config 配置结构
type Config struct {
	Addr        string
	APIEndpoint string
	TLSCert     string
	TLSKey      string
	EnableTLS   bool
	HeartbeatInterval time.Duration
	HeartbeatTimeout  time.Duration
}

// TunnelConn 隧道连接
type TunnelConn struct {
	DeviceID   string
	Conn       *websocket.Conn
	LastPing   time.Time
	mu         sync.RWMutex
	sendChan   chan []byte
	closeChan  chan struct{}
}

// NewTunnelConn 创建新的隧道连接
func NewTunnelConn(deviceID string, conn *websocket.Conn) *TunnelConn {
	return &TunnelConn{
		DeviceID:  deviceID,
		Conn:      conn,
		LastPing:  time.Now(),
		sendChan:  make(chan []byte, 100),
		closeChan: make(chan struct{}),
	}
}

// UpdatePing 更新最后ping时间
func (t *TunnelConn) UpdatePing() {
	t.mu.Lock()
	t.LastPing = time.Now()
	t.mu.Unlock()
}

// IsAlive 检查连接是否存活
func (t *TunnelConn) IsAlive(timeout time.Duration) bool {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return time.Since(t.LastPing) < timeout
}

// Send 发送消息
func (t *TunnelConn) Send(data []byte) error {
	select {
	case t.sendChan <- data:
		return nil
	case <-t.closeChan:
		return fmt.Errorf("tunnel closed")
	default:
		return fmt.Errorf("send buffer full")
	}
}

// Close 关闭连接
func (t *TunnelConn) Close() {
	close(t.closeChan)
	t.Conn.Close()
}

// TunnelManager 隧道管理器
type TunnelManager struct {
	tunnels map[string]*TunnelConn
	mu      sync.RWMutex
	config  *Config
}

// NewTunnelManager 创建隧道管理器
func NewTunnelManager(config *Config) *TunnelManager {
	return &TunnelManager{
		tunnels: make(map[string]*TunnelConn),
		config:  config,
	}
}

// Register 注册隧道
func (m *TunnelManager) Register(deviceID string, conn *websocket.Conn) *TunnelConn {
	tunnel := NewTunnelConn(deviceID, conn)
	
	m.mu.Lock()
	// 关闭旧连接
	if old, ok := m.tunnels[deviceID]; ok {
		old.Close()
	}
	m.tunnels[deviceID] = tunnel
	m.mu.Unlock()
	
	log.Printf("Tunnel registered for device: %s", deviceID)
	
	// 通知API服务设备上线
	m.notifyDeviceStatus(deviceID, "online", "")
	
	return tunnel
}

// Unregister 注销隧道
func (m *TunnelManager) Unregister(deviceID string) {
	m.mu.Lock()
	if tunnel, ok := m.tunnels[deviceID]; ok {
		tunnel.Close()
		delete(m.tunnels, deviceID)
	}
	m.mu.Unlock()
	
	log.Printf("Tunnel unregistered for device: %s", deviceID)
	
	// 通知API服务设备离线
	m.notifyDeviceStatus(deviceID, "offline", "")
}

// Get 获取隧道
func (m *TunnelManager) Get(deviceID string) (*TunnelConn, bool) {
	m.mu.RLock()
	tunnel, ok := m.tunnels[deviceID]
	m.mu.RUnlock()
	return tunnel, ok
}

// notifyDeviceStatus 通知API设备状态变化
func (m *TunnelManager) notifyDeviceStatus(deviceID, status, tunnelAddr string) {
	go func() {
		client := &http.Client{Timeout: 5 * time.Second}
		
		payload := map[string]string{
			"device_id":   deviceID,
			"status":      status,
			"tunnel_addr": tunnelAddr,
		}
		
		data, _ := json.Marshal(payload)
		resp, err := client.Post(
			m.config.APIEndpoint+"/api/device/status",
			"application/json",
			bytes.NewReader(data),
		)
		if err != nil {
			log.Printf("Failed to notify device status: %v", err)
			return
		}
		defer resp.Body.Close()
	}()
}

// cleanupDeadTunnels 清理死连接
func (m *TunnelManager) cleanupDeadTunnels() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	
	for range ticker.C {
		m.mu.Lock()
		for deviceID, tunnel := range m.tunnels {
			if !tunnel.IsAlive(m.config.HeartbeatTimeout) {
				log.Printf("Cleaning up dead tunnel for device: %s", deviceID)
				tunnel.Close()
				delete(m.tunnels, deviceID)
				
				// 通知API
				go m.notifyDeviceStatus(deviceID, "offline", "")
			}
		}
		m.mu.Unlock()
	}
}

// Server WebSocket服务器
type Server struct {
	manager *TunnelManager
	upgrader websocket.Upgrader
	config  *Config
}

// NewServer 创建服务器
func NewServer(config *Config) *Server {
	return &Server{
		manager: NewTunnelManager(config),
		upgrader: websocket.Upgrader{
			CheckOrigin: func(r *http.Request) bool {
				// 在生产环境中应该检查来源
				return true
			},
			ReadBufferSize:  64 * 1024,
			WriteBufferSize: 64 * 1024,
		},
		config: config,
	}
}

// handleTunnel WebSocket连接处理
func (s *Server) handleTunnel(w http.ResponseWriter, r *http.Request) {
	// 获取设备ID和凭证
	deviceID := r.URL.Query().Get("device_id")
	token := r.URL.Query().Get("token")
	
	if deviceID == "" || token == "" {
		http.Error(w, "missing device_id or token", http.StatusBadRequest)
		return
	}
	
	// 验证设备凭证（调用API服务）
	if !s.validateDeviceToken(deviceID, token) {
		http.Error(w, "invalid credentials", http.StatusUnauthorized)
		return
	}
	
	// 升级WebSocket连接
	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("Failed to upgrade connection: %v", err)
		return
	}
	defer conn.Close()
	
	// 注册隧道
	tunnel := s.manager.Register(deviceID, conn)
	defer s.manager.Unregister(deviceID)
	
	// 启动心跳检测
	stopHeartbeat := make(chan struct{})
	go s.heartbeat(tunnel, stopHeartbeat)
	
	// 启动发送协程
	go s.sendLoop(tunnel)
	
	// 读取消息循环
	s.readLoop(tunnel)
	
	close(stopHeartbeat)
}

// validateDeviceToken 验证设备令牌
// 通过HTTP请求调用API服务验证token的有效性
func (s *Server) validateDeviceToken(deviceID, token string) bool {
	// 构造验证请求
	payload := map[string]string{
		"device_id": deviceID,
		"token":     token,
	}

	data, err := json.Marshal(payload)
	if err != nil {
		log.Printf("Failed to marshal validation request: %v", err)
		return false
	}

	// 创建带超时的HTTP客户端
	client := &http.Client{
		Timeout: 10 * time.Second,
	}

	// 发送验证请求到API服务
	resp, err := client.Post(
		s.config.APIEndpoint+"/api/session/validate",
		"application/json",
		bytes.NewReader(data),
	)
	if err != nil {
		log.Printf("Failed to call validation API: %v", err)
		return false
	}
	defer resp.Body.Close()

	// 检查HTTP状态码
	if resp.StatusCode != http.StatusOK {
		log.Printf("Validation API returned non-OK status: %d", resp.StatusCode)
		return false
	}

	// 解析响应
	var result struct {
		Valid bool `json:"valid"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		log.Printf("Failed to decode validation response: %v", err)
		return false
	}

	return result.Valid
}

// heartbeat 心跳检测
func (s *Server) heartbeat(tunnel *TunnelConn, stop chan struct{}) {
	ticker := time.NewTicker(s.config.HeartbeatInterval)
	defer ticker.Stop()
	
	for {
		select {
		case <-ticker.C:
			if !tunnel.IsAlive(s.config.HeartbeatTimeout) {
				log.Printf("Heartbeat timeout for device: %s", tunnel.DeviceID)
				tunnel.Close()
				return
			}
			
			// 发送ping
			if err := tunnel.Conn.WriteControl(websocket.PingMessage, []byte{}, time.Now().Add(10*time.Second)); err != nil {
				log.Printf("Failed to send ping: %v", err)
				tunnel.Close()
				return
			}
			
		case <-stop:
			return
		}
	}
}

// sendLoop 发送循环
func (s *Server) sendLoop(tunnel *TunnelConn) {
	for {
		select {
		case data := <-tunnel.sendChan:
			if err := tunnel.Conn.WriteMessage(websocket.BinaryMessage, data); err != nil {
				log.Printf("Failed to write message: %v", err)
				return
			}
		case <-tunnel.closeChan:
			return
		}
	}
}

// readLoop 读取循环
func (s *Server) readLoop(tunnel *TunnelConn) {
	tunnel.Conn.SetPongHandler(func(string) error {
		tunnel.UpdatePing()
		return nil
	})
	
	for {
		messageType, data, err := tunnel.Conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				log.Printf("WebSocket error for device %s: %v", tunnel.DeviceID, err)
			}
			return
		}
		
		tunnel.UpdatePing()
		
		if messageType == websocket.BinaryMessage || messageType == websocket.TextMessage {
			// 处理消息
			s.handleMessage(tunnel, data)
		}
	}
}

// handleMessage 处理消息
func (s *Server) handleMessage(tunnel *TunnelConn, data []byte) {
	// 解析消息
	var msg struct {
		Type string          `json:"type"`
		Data json.RawMessage `json:"data"`
	}
	
	if err := json.Unmarshal(data, &msg); err != nil {
		log.Printf("Failed to unmarshal message: %v", err)
		return
	}
	
	switch msg.Type {
	case "connect_response":
		// 设备响应连接请求
		s.handleConnectResponse(tunnel, msg.Data)
	case "data":
		// 数据传输
		s.handleData(tunnel, msg.Data)
	case "disconnect":
		// 断开连接
		s.handleDisconnect(tunnel, msg.Data)
	default:
		log.Printf("Unknown message type: %s", msg.Type)
	}
}

// handleConnectResponse 处理连接响应
func (s *Server) handleConnectResponse(tunnel *TunnelConn, data json.RawMessage) {
	var resp struct {
		StreamID string `json:"stream_id"`
		Success  bool   `json:"success"`
		Error    string `json:"error,omitempty"`
	}
	
	if err := json.Unmarshal(data, &resp); err != nil {
		log.Printf("Failed to unmarshal connect response: %v", err)
		return
	}
	
	log.Printf("Connect response for stream %s: success=%v", resp.StreamID, resp.Success)
	
	// 这里应该将响应转发给SOCKS5服务
	// 简化版本：直接处理
}

// handleData 处理数据
func (s *Server) handleData(tunnel *TunnelConn, data json.RawMessage) {
	var resp struct {
		StreamID string `json:"stream_id"`
		Data     []byte `json:"data"`
	}
	
	if err := json.Unmarshal(data, &resp); err != nil {
		log.Printf("Failed to unmarshal data: %v", err)
		return
	}
	
	// 这里应该将数据转发给SOCKS5服务
	// 简化版本：直接处理
}

// handleDisconnect 处理断开连接
func (s *Server) handleDisconnect(tunnel *TunnelConn, data json.RawMessage) {
	var resp struct {
		StreamID string `json:"stream_id"`
	}
	
	if err := json.Unmarshal(data, &resp); err != nil {
		log.Printf("Failed to unmarshal disconnect: %v", err)
		return
	}
	
	log.Printf("Disconnect for stream %s", resp.StreamID)
}

// handleHealth 健康检查
func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status": "ok",
		"time":   time.Now().UTC(),
	})
}

// handleStats 统计信息
func (s *Server) handleStats(w http.ResponseWriter, r *http.Request) {
	s.manager.mu.RLock()
	count := len(s.manager.tunnels)
	s.manager.mu.RUnlock()
	
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"online_devices": count,
		"time":           time.Now().UTC(),
	})
}

// Run 运行服务器
func (s *Server) Run() error {
	// 启动清理协程
	go s.manager.cleanupDeadTunnels()
	
	// 设置路由
	http.HandleFunc("/tunnel", s.handleTunnel)
	http.HandleFunc("/health", s.handleHealth)
	http.HandleFunc("/stats", s.handleStats)
	
	log.Printf("Tunnel server starting on %s", s.config.Addr)
	
	if s.config.EnableTLS {
		return http.ListenAndServeTLS(s.config.Addr, s.config.TLSCert, s.config.TLSKey, nil)
	}
	return http.ListenAndServe(s.config.Addr, nil)
}

func main() {
	// 解析命令行参数
	addr := flag.String("addr", "0.0.0.0:8443", "Server address")
	apiEndpoint := flag.String("api", "http://localhost:8080", "API endpoint URL")
	tlsCert := flag.String("tls-cert", "", "TLS certificate file")
	tlsKey := flag.String("tls-key", "", "TLS key file")
	heartbeatInterval := flag.Duration("heartbeat-interval", 30*time.Second, "Heartbeat interval")
	heartbeatTimeout := flag.Duration("heartbeat-timeout", 90*time.Second, "Heartbeat timeout")
	flag.Parse()
	
	// 从环境变量读取配置
	if envAddr := os.Getenv("TUNNEL_ADDR"); envAddr != "" {
		*addr = envAddr
	}
	if envAPI := os.Getenv("API_ENDPOINT"); envAPI != "" {
		*apiEndpoint = envAPI
	}
	
	config := &Config{
		Addr:              *addr,
		APIEndpoint:       *apiEndpoint,
		EnableTLS:         *tlsCert != "" && *tlsKey != "",
		TLSCert:           *tlsCert,
		TLSKey:            *tlsKey,
		HeartbeatInterval: *heartbeatInterval,
		HeartbeatTimeout:  *heartbeatTimeout,
	}
	
	server := NewServer(config)
	
	if err := server.Run(); err != nil {
		log.Fatalf("Server failed: %v", err)
	}
}
