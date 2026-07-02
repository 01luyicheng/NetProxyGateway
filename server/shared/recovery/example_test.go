package recovery_test

import (
	"log"
	"os"

	"github.com/netproxy/shared/recovery"
)

func ExampleRecover() {
	var n int
	var err error
	logger := log.New(os.Stdout, "", 0)

	func() {
		defer recovery.Recover("StreamConn.Read",
			recovery.WithStreamID("stream-123"),
			recovery.WithNamedReturn(&n, &err, "read panic"),
			recovery.WithLogger(logger),
		)
		panic("connection lost")
	}()
	// Output:
	// Panic in StreamConn.Read for stream stream-123: connection lost
}

func ExampleRecoverAction() {
	logger := log.New(os.Stdout, "", 0)
	func() {
		defer recovery.RecoverAction("handleConnection", func() {
			// action
		}, recovery.WithLogger(logger))
		panic("connection reset")
	}()
	// Output:
	// Panic in handleConnection: connection reset
}
