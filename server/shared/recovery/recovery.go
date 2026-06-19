// Package recovery provides unified panic recovery utilities to eliminate
// repetitive defer/recover boilerplate across the codebase.
package recovery

import (
	"errors"
	"fmt"
	"log"
)

// Option configures panic recovery behavior.
type Option func(*recoverCtx)

// Logger is the minimal logging interface used by recovery functions.
// The standard library's *log.Logger satisfies this interface.
type Logger interface {
	Printf(format string, v ...any)
}

type recoverCtx struct {
	component   string
	streamID    string
	deviceID    string
	namedReturn *namedReturn
	logger      Logger
}

type namedReturn struct {
	n      *int
	err    *error
	prefix string
}

// WithStreamID adds stream ID context to panic logs.
func WithStreamID(id string) Option {
	return func(ctx *recoverCtx) {
		ctx.streamID = id
	}
}

// WithDeviceID adds device ID context to panic logs.
func WithDeviceID(id string) Option {
	return func(ctx *recoverCtx) {
		ctx.deviceID = id
	}
}

// WithLogger sets the logger used by recovery functions.
// If logger is nil or not provided, the standard library's default logger is used.
func WithLogger(logger Logger) Option {
	return func(ctx *recoverCtx) {
		if logger != nil {
			ctx.logger = logger
		}
	}
}

// WithNamedReturn configures recovery to set named return values on panic.
// n and err are pointers to the named return values.
// prefix is used to construct the error message: "<prefix>: <panic value>".
func WithNamedReturn(n *int, err *error, prefix string) Option {
	return func(ctx *recoverCtx) {
		ctx.namedReturn = &namedReturn{
			n:      n,
			err:    err,
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
	ctx := setupCtx(component, opts...)
	if r := recover(); r != nil {
		ctx.logPanic(r, "")

		if ctx.namedReturn != nil {
			if ctx.namedReturn.n != nil {
				*ctx.namedReturn.n = 0
			}
			if ctx.namedReturn.err != nil {
				var panicErr error
				if e, ok := r.(error); ok {
					panicErr = e
				} else {
					panicErr = errors.New(fmt.Sprint(r))
				}
				if ctx.namedReturn.prefix != "" {
					*ctx.namedReturn.err = fmt.Errorf("%s: %w", ctx.namedReturn.prefix, panicErr)
				} else {
					*ctx.namedReturn.err = panicErr
				}
			}
		}
	}
}

func setupCtx(component string, opts ...Option) *recoverCtx {
	ctx := &recoverCtx{component: component, logger: log.Default()}
	for _, opt := range opts {
		opt(ctx)
	}
	return ctx
}

// RecoverAction must be called via defer. It recovers from panics, logs them,
// and executes the provided action function. If the action function itself
// panics, that panic is also recovered and logged; it will not propagate
// to the caller.
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
	ctx := setupCtx(component, opts...)
	if r := recover(); r != nil {
		ctx.logPanic(r, "")
		if action != nil {
			defer func() {
				if r := recover(); r != nil {
					ctx.logPanic(r, "recovery action")
				}
			}()
			action()
		}
	}
}

func (ctx *recoverCtx) logPanic(r any, suffix string) {
	msg := ctx.formatMessage()
	if suffix != "" {
		ctx.logger.Printf("Panic in %s %s: %v", msg, suffix, r)
	} else {
		ctx.logger.Printf("Panic in %s: %v", msg, r)
	}
}

func (ctx *recoverCtx) formatMessage() string {
	msg := ctx.component
	if ctx.streamID != "" {
		msg += fmt.Sprintf(" for stream %s", ctx.streamID)
	}
	if ctx.deviceID != "" {
		msg += fmt.Sprintf(" for device %s", ctx.deviceID)
	}
	return msg
}
