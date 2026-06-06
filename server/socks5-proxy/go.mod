module github.com/netproxy/socks5-proxy

go 1.22

require (
	github.com/gorilla/websocket v1.5.3
	github.com/netproxy/shared/httpclient v0.0.0
	github.com/netproxy/shared/ratelimit v0.0.0
	github.com/netproxy/shared/stringutil v0.0.0
)

replace (
	github.com/netproxy/shared/httpclient => ../shared/httpclient
	github.com/netproxy/shared/ratelimit => ../shared/ratelimit
	github.com/netproxy/shared/stringutil => ../shared/stringutil
)
