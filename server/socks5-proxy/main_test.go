package main

import (
	"errors"
	"io"
	"net"
	"testing"
)

type stubTunnelDialer struct {
	deviceID string
	token    string
	dstAddr  string
	dstPort  int
	err      error
}

func (s *stubTunnelDialer) ConnectThroughTunnel(deviceID, token, dstAddr string, dstPort int) (net.Conn, error) {
	s.deviceID = deviceID
	s.token = token
	s.dstAddr = dstAddr
	s.dstPort = dstPort
	// 返回一个 pipe 连接用于测试
	localConn, _ := net.Pipe()
	return localConn, s.err
}

func (s *stubTunnelDialer) RemoveStream(streamID string) {
}

func TestHandleConnectUsesAuthenticatedToken(t *testing.T) {
	clientConn, peerConn := net.Pipe()
	defer peerConn.Close()

	dialer := &stubTunnelDialer{err: errors.New("dial failed")}
	server := &SOCKS5Server{
		tunnelClient: dialer,
	}

	done := make(chan error, 1)
	go func() {
		done <- server.handleConnect(clientConn, AuthSession{
			DeviceID: "device-123",
			Token:    "session-token-456",
		}, "192.168.1.1", 80)
	}()

	reply := make([]byte, 10)
	if _, err := io.ReadFull(peerConn, reply); err != nil {
		t.Fatalf("failed to read SOCKS5 reply: %v", err)
	}

	if err := <-done; err == nil {
		t.Fatalf("expected handleConnect to return an error")
	}

	if dialer.deviceID != "device-123" {
		t.Fatalf("unexpected device id: %s", dialer.deviceID)
	}
	if dialer.token != "session-token-456" {
		t.Fatalf("expected authenticated token to be forwarded, got %s", dialer.token)
	}
	if dialer.dstAddr != "192.168.1.1" || dialer.dstPort != 80 {
		t.Fatalf("unexpected destination: %s:%d", dialer.dstAddr, dialer.dstPort)
	}
	if reply[1] != 0x05 {
		t.Fatalf("expected connection refused reply, got %d", reply[1])
	}
}
