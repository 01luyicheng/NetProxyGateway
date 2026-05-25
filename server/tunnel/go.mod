module github.com/netproxy/tunnel

go 1.22

require (
	github.com/gorilla/websocket v1.5.1
	github.com/netproxy/shared/httpclient v0.0.0
	github.com/netproxy/shared/stringutil v0.0.0
)

require golang.org/x/net v0.17.0 // indirect

replace (
	github.com/netproxy/shared/httpclient => ../shared/httpclient
	github.com/netproxy/shared/stringutil => ../shared/stringutil
)
