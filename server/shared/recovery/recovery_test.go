package recovery

import (
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
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("Recover did not recover from panic: %v", r)
			}
		}()
		defer Recover("test.stream", WithStreamID("stream-123"))
		panic("test panic")
	}()
	// Should log: "Panic in test.stream for stream stream-123: test panic"
}

func TestRecover_WithDeviceID(t *testing.T) {
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Errorf("Recover did not recover from panic: %v", r)
			}
		}()
		defer Recover("test.device", WithDeviceID("device-456"))
		panic("test panic")
	}()
	// Should log: "Panic in test.device for device device-456: test panic"
}

func TestRecover_WithBothIDs(t *testing.T) {
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
	// Should log: "Panic in test.both for stream stream-123 for device device-456: test panic"
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
