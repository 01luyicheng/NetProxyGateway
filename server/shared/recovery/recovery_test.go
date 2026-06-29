package recovery

import (
	"bytes"
	"errors"
	"log"
	"strings"
	"testing"
)

func TestRecover_NoPanic(t *testing.T) {
	func() {
		defer Recover("test.no_panic")
		// no panic
	}()
	// should not panic or log
}

func TestRecover_WithPanic_LogsAndRecovers(t *testing.T) {
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("Recover did not recover from panic: %v", r)
			}
		}()
		defer Recover("test.panic")
		panic("test panic")
	}()
}

func TestRecover_WithNamedReturn(t *testing.T) {
	fn := func() (n int, err error) {
		defer Recover("test.named_return",
			WithNamedReturn(&n, &err, "named panic"))
		panic("boom")
	}

	n, err := fn()
	if n != 0 {
		t.Errorf("expected n=0, got %d", n)
	}
	if err == nil {
		t.Error("expected error, got nil")
	} else if err.Error() != "named panic: boom" {
		t.Errorf("unexpected error: %v", err)
	}
}

func TestRecover_WithStreamID(t *testing.T) {
	var buf bytes.Buffer
	oldOutput := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(oldOutput)

	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("Recover did not recover from panic: %v", r)
			}
		}()
		defer Recover("test.stream", WithStreamID("stream-123"))
		panic("test panic")
	}()

	got := buf.String()
	want := "Panic in test.stream for stream stream-123: test panic"
	if !strings.Contains(got, want) {
		t.Errorf("log output does not match expected. want substring: %s, got: %s", want, got)
	}
}

func TestRecover_WithDeviceID(t *testing.T) {
	var buf bytes.Buffer
	oldOutput := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(oldOutput)

	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("Recover did not recover from panic: %v", r)
			}
		}()
		defer Recover("test.device", WithDeviceID("device-456"))
		panic("test panic")
	}()

	got := buf.String()
	want := "Panic in test.device for device device-456: test panic"
	if !strings.Contains(got, want) {
		t.Errorf("log output does not match expected. want substring: %s, got: %s", want, got)
	}
}

func TestRecover_WithBothIDs(t *testing.T) {
	var buf bytes.Buffer
	oldOutput := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(oldOutput)

	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("Recover did not recover from panic: %v", r)
			}
		}()
		defer Recover("test.both",
			WithStreamID("stream-123"),
			WithDeviceID("device-456"))
		panic("test panic")
	}()

	got := buf.String()
	want := "Panic in test.both for stream stream-123 for device device-456: test panic"
	if !strings.Contains(got, want) {
		t.Errorf("log output does not match expected. want substring: %s, got: %s", want, got)
	}
}

func TestRecoverAction_WithPanic_ExecutesAction(t *testing.T) {
	actionCalled := false
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("RecoverAction did not recover from panic: %v", r)
			}
		}()
		defer RecoverAction("test.action", func() {
			actionCalled = true
		})
		panic("test panic")
	}()

	if !actionCalled {
		t.Error("expected action to be called")
	}
}

func TestRecoverAction_NoPanic_DoesNotExecuteAction(t *testing.T) {
	actionCalled := false
	func() {
		defer RecoverAction("test.no_action", func() {
			actionCalled = true
		})
		// no panic
	}()

	if actionCalled {
		t.Error("expected action not to be called")
	}
}

func TestRecoverAction_NilAction(t *testing.T) {
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("RecoverAction did not recover from panic: %v", r)
			}
		}()
		defer RecoverAction("test.nil_action", nil)
		panic("test panic")
	}()
	// Should not panic
}

func TestRecover_NilNamedReturnPointers(t *testing.T) {
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("Recover did not recover from panic: %v", r)
			}
		}()
		defer Recover("test.nil_ptrs",
			WithNamedReturn(nil, nil, "panic"))
		panic("test panic")
	}()
	// Should not panic when pointers are nil
}

func TestRecoverAction_ActionPanics_Recovered(t *testing.T) {
	var buf bytes.Buffer
	oldOutput := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(oldOutput)

	actionCalled := false
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("RecoverAction did not recover from original or action panic: %v", r)
			}
		}()
		defer RecoverAction("test.action_panics", func() {
			actionCalled = true
			panic("action panic")
		})
		panic("original panic")
	}()

	if !actionCalled {
		t.Error("expected action to be called")
	}

	got := buf.String()
	if !strings.Contains(got, "recovery action: action panic") {
		t.Errorf("expected log to contain action panic message, got: %s", got)
	}
}

func TestRecoverAction_WithStreamID(t *testing.T) {
	var buf bytes.Buffer
	oldOutput := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(oldOutput)

	actionCalled := false
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("RecoverAction did not recover from panic: %v", r)
			}
		}()
		defer RecoverAction("test.action_stream", func() {
			actionCalled = true
		}, WithStreamID("stream-123"))
		panic("action panic")
	}()

	if !actionCalled {
		t.Error("expected action to be called")
	}

	got := buf.String()
	want := "Panic in test.action_stream for stream stream-123: action panic"
	if !strings.Contains(got, want) {
		t.Errorf("log output does not match expected. want substring: %s, got: %s", want, got)
	}
}

func TestRecoverAction_WithDeviceID(t *testing.T) {
	var buf bytes.Buffer
	oldOutput := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(oldOutput)

	actionCalled := false
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("RecoverAction did not recover from panic: %v", r)
			}
		}()
		defer RecoverAction("test.action_device", func() {
			actionCalled = true
		}, WithDeviceID("device-456"))
		panic("action panic")
	}()

	if !actionCalled {
		t.Error("expected action to be called")
	}

	got := buf.String()
	want := "Panic in test.action_device for device device-456: action panic"
	if !strings.Contains(got, want) {
		t.Errorf("log output does not match expected. want substring: %s, got: %s", want, got)
	}
}

func TestRecoverAction_WithBothIDs(t *testing.T) {
	var buf bytes.Buffer
	oldOutput := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(oldOutput)

	actionCalled := false
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("RecoverAction did not recover from panic: %v", r)
			}
		}()
		defer RecoverAction("test.action_both", func() {
			actionCalled = true
		}, WithStreamID("stream-123"), WithDeviceID("device-456"))
		panic("action panic")
	}()

	if !actionCalled {
		t.Error("expected action to be called")
	}

	got := buf.String()
	want := "Panic in test.action_both for stream stream-123 for device device-456: action panic"
	if !strings.Contains(got, want) {
		t.Errorf("log output does not match expected. want substring: %s, got: %s", want, got)
	}
}

func TestRecover_WithPanic_IntValue(t *testing.T) {
	fn := func() (err error) {
		defer Recover("test.int_panic",
			WithNamedReturn(nil, &err, "int panic"))
		panic(123)
	}

	err := fn()
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "123") {
		t.Errorf("expected error to contain '123', got: %v", err)
	}
}

func TestRecover_WithPanic_NilValue(t *testing.T) {
	fn := func() (err error) {
		defer Recover("test.nil_panic",
			WithNamedReturn(nil, &err, "nil panic"))
		panic(nil)
	}

	err := fn()
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "nil panic:") {
		t.Errorf("expected error to contain 'nil panic:', got: %v", err)
	}
}

func TestRecover_WithNamedReturn_EmptyPrefix(t *testing.T) {
	fn := func() (n int, err error) {
		defer Recover("test.empty_prefix",
			WithNamedReturn(&n, &err, ""))
		panic("boom")
	}

	n, err := fn()
	if n != 0 {
		t.Errorf("expected n=0, got %d", n)
	}
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if strings.HasPrefix(err.Error(), ":") {
		t.Errorf("error should not start with colon, got: %v", err)
	}
	if err.Error() != "boom" {
		t.Errorf("expected error 'boom', got: %v", err)
	}
}

func TestRecover_WithPanic_ErrorValue(t *testing.T) {
	targetErr := errors.New("wrapped error")
	fn := func() (err error) {
		defer Recover("test.error_panic",
			WithNamedReturn(nil, &err, "error panic"))
		panic(targetErr)
	}

	err := fn()
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !errors.Is(err, targetErr) {
		t.Errorf("expected errors.Is to find targetErr, got %v", err)
	}
}

func TestRecoverAction_WithPanic_IntValue(t *testing.T) {
	var buf bytes.Buffer
	oldOutput := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(oldOutput)

	actionCalled := false
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("RecoverAction did not recover from panic: %v", r)
			}
		}()
		defer RecoverAction("test.action_int", func() {
			actionCalled = true
		})
		panic(123)
	}()

	if !actionCalled {
		t.Error("expected action to be called")
	}

	got := buf.String()
	if !strings.Contains(got, "123") {
		t.Errorf("expected log to contain '123', got: %s", got)
	}
}

func TestRecoverAction_WithPanic_NilValue(t *testing.T) {
	var buf bytes.Buffer
	oldOutput := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(oldOutput)

	actionCalled := false
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("RecoverAction did not recover from panic: %v", r)
			}
		}()
		defer RecoverAction("test.action_nil", func() {
			actionCalled = true
		})
		panic(nil)
	}()

	if !actionCalled {
		t.Error("expected action to be called")
	}

	got := buf.String()
	if !strings.Contains(got, "panic called with nil argument") {
		t.Errorf("expected log to contain 'panic called with nil argument', got: %s", got)
	}
}

func TestRecoverAction_WithPanic_ErrorValue(t *testing.T) {
	targetErr := errors.New("wrapped error")
	var buf bytes.Buffer
	oldOutput := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(oldOutput)

	actionCalled := false
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("RecoverAction did not recover from panic: %v", r)
			}
		}()
		defer RecoverAction("test.action_error", func() {
			actionCalled = true
		})
		panic(targetErr)
	}()

	if !actionCalled {
		t.Error("expected action to be called")
	}

	got := buf.String()
	if !strings.Contains(got, "wrapped error") {
		t.Errorf("expected log to contain 'wrapped error', got: %s", got)
	}
}

func TestWithLogger_Nil_DoesNotPanic(t *testing.T) {
	var buf bytes.Buffer
	oldOutput := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(oldOutput)

	// WithLogger(nil) should fall back to the default logger, not cause a nil panic.
	fn := func() (err error) {
		defer Recover("test.nil_logger",
			WithLogger(nil),
			WithNamedReturn(nil, &err, "nil logger"))
		panic("trigger")
	}

	err := fn()
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if err.Error() != "nil logger: trigger" {
		t.Errorf("unexpected error: %v", err)
	}

	got := buf.String()
	if !strings.Contains(got, "Panic in test.nil_logger: trigger") {
		t.Errorf("expected default logger output, got: %s", got)
	}
}

func ExampleRecover() {
	var n int
	var err error
	func() {
		defer Recover("StreamConn.Read", WithStreamID("stream-123"), WithNamedReturn(&n, &err, "read panic"))
		panic("connection lost")
	}()
	// Note: We're not matching the output exactly because it contains date/time
}

func ExampleRecoverAction() {
	func() {
		defer RecoverAction("handleConnection", func() {
			// e.g. _ = c.Close()
		})
		panic("connection reset")
	}()
	// Note: We're not matching the output exactly because it contains date/time
}

func TestRecoverAction_WithLogger_Nil_DoesNotPanic(t *testing.T) {
	var buf bytes.Buffer
	oldOutput := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(oldOutput)

	actionCalled := false
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("RecoverAction did not recover from panic: %v", r)
			}
		}()
		defer RecoverAction("test.nil_logger_action", func() {
			actionCalled = true
		}, WithLogger(nil))
		panic("trigger")
	}()

	if !actionCalled {
		t.Error("expected action to be called")
	}

	got := buf.String()
	if !strings.Contains(got, "Panic in test.nil_logger_action: trigger") {
		t.Errorf("expected default logger output, got: %s", got)
	}
}
