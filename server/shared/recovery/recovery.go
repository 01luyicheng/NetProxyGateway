// Package recovery provides unified panic recovery utilities to eliminate
// repetitive defer/recover boilerplate across the codebase.
package recovery

import (
	"errors"
	"fmt"
	"log"
)

// Option configures panic recovery behavior.
type Option func(*context)

type context struct {
	component   string
	streamID    string
	deviceID    string
	namedReturn *namedReturn
}

type namedReturn struct {
	nPtr   *int
	errPtr *error
	prefix string
}

// WithStreamID adds stream ID context to panic logs.
func WithStreamID(id string) Option {
	return func(ctx *context) {
		ctx.streamID = id
	}
}

// WithDeviceID adds device ID context to panic logs.
func WithDeviceID(id string) Option {
	return func(ctx *context) {
		ctx.deviceID = id
	}
}

// WithNamedReturn configures recovery to set named return values on panic.
// nPtr and errPtr are pointers to the named return values.
// prefix is used to construct the error message: "<prefix>: <panic value>".
func WithNamedReturn(nPtr *int, errPtr *error, prefix string) Option {
	return func(ctx *context) {
		ctx.namedReturn = &namedReturn{
			nPtr:   nPtr,
			errPtr: errPtr,
			prefix: prefix,
		}
	}
}

// Recover must be called via defer. It recovers from panics, logs them with
// contextual information, and optionally sets named return values.
//
// Usage:
//
//	func (s *StreamConn) Read(p []byte) (n int, err error) {
//	    defer recovery.Recover("StreamConn.Read",
//	        recovery.WithStreamID(s.StreamID),
//	        recovery.WithNamedReturn(&n, &err, "read panic"))
//	    // ...
//	}
func Recover(component string, opts ...Option) {
	ctx := &context{component: component}
	for _, opt := range opts {
		opt(ctx)
	}

	if r := recover(); r != nil {
		msg := ctx.formatMessage()
		log.Printf("Panic in %s: %v", msg, r)

		if ctx.namedReturn != nil {
			if ctx.namedReturn.nPtr != nil {
				*ctx.namedReturn.nPtr = 0
			}
			if ctx.namedReturn.errPtr != nil {
				var panicErr error
				if e, ok := r.(error); ok {
					panicErr = e
				} else {
					panicErr = errors.New(fmt.Sprint(r))
				}
				*ctx.namedReturn.errPtr = fmt.Errorf("%s: %w", ctx.namedReturn.prefix, panicErr)
			}
		}
	}
}

// RecoverAction must be called via defer. It recovers from panics, logs them,
// and executes the provided action function.
//
// Usage:
//
//	go func(c net.Conn) {
//	    defer recovery.RecoverAction("handleConnection", func() {
//	        _ = c.Close()
//	    })
//	    s.handleConnection(c)
//	}(conn)
func RecoverAction(component string, action func(), opts ...Option) {
	ctx := &context{component: component}
	for _, opt := range opts {
		opt(ctx)
	}

	if r := recover(); r != nil {
		msg := ctx.formatMessage()
		log.Printf("Panic in %s: %v", msg, r)

		if action != nil {
			action()
		}
	}
}

func (ctx *context) formatMessage() string {
	msg := ctx.component
	if ctx.streamID != "" {
		msg += fmt.Sprintf(" for stream %s", ctx.streamID)
	}
	if ctx.deviceID != "" {
		msg += fmt.Sprintf(" for device %s", ctx.deviceID)
	}
	return msg
}
