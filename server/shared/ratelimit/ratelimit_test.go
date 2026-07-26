package ratelimit

import (
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestAllow_NewKey(t *testing.T) {
	cfg := Config{
		MaxAttempts:     5,
		Window:          5 * time.Minute,
		BlockDuration:   15 * time.Minute,
		CleanupInterval: 10 * time.Minute,
		StaleAttemptTTL: 30 * time.Minute,
	}
	rl := NewRateLimiter(cfg)
	defer rl.Stop()

	if !rl.Allow("new-key") {
		t.Fatal("expected Allow to return true for a new key")
	}
}

func TestAllow_MaxAttempts(t *testing.T) {
	cfg := Config{
		MaxAttempts:     2,
		Window:          5 * time.Minute,
		BlockDuration:   15 * time.Minute,
		CleanupInterval: 10 * time.Minute,
		StaleAttemptTTL: 30 * time.Minute,
	}
	rl := NewRateLimiter(cfg)
	defer rl.Stop()

	key := "max-key"
	if !rl.Allow(key) {
		t.Fatal("expected first Allow to be true")
	}
	if !rl.Allow(key) {
		t.Fatal("expected second Allow to be true")
	}
	if rl.Allow(key) {
		t.Fatal("expected third Allow to be false after reaching MaxAttempts")
	}
}

func TestAllow_BlockExpires(t *testing.T) {
	cfg := Config{
		MaxAttempts:     1,
		Window:          5 * time.Minute,
		BlockDuration:   100 * time.Millisecond,
		CleanupInterval: 10 * time.Minute,
		StaleAttemptTTL: 30 * time.Minute,
	}
	rl := NewRateLimiter(cfg)
	defer rl.Stop()

	key := "block-key"
	if !rl.Allow(key) {
		t.Fatal("expected first Allow to be true")
	}
	if rl.Allow(key) {
		t.Fatal("expected second Allow to be false")
	}

	time.Sleep(150 * time.Millisecond)

	if !rl.Allow(key) {
		t.Fatal("expected Allow to be true after block expired")
	}
}

func TestAllow_WindowReset(t *testing.T) {
	cfg := Config{
		MaxAttempts:     3,
		Window:          100 * time.Millisecond,
		BlockDuration:   15 * time.Minute,
		CleanupInterval: 10 * time.Minute,
		StaleAttemptTTL: 30 * time.Minute,
	}
	rl := NewRateLimiter(cfg)
	defer rl.Stop()

	key := "window-key"
	if !rl.Allow(key) {
		t.Fatal("expected first Allow to be true")
	}
	if !rl.Allow(key) {
		t.Fatal("expected second Allow to be true")
	}
	if !rl.Allow(key) {
		t.Fatal("expected third Allow to be true")
	}

	// Wait for the window to expire.
	time.Sleep(200 * time.Millisecond)

	// After window reset, should allow again without blocking.
	if !rl.Allow(key) {
		t.Fatal("expected Allow to be true after window reset")
	}
}

func TestSuccess_ClearsAttempts(t *testing.T) {
	cfg := Config{
		MaxAttempts:     2,
		Window:          5 * time.Minute,
		BlockDuration:   15 * time.Minute,
		CleanupInterval: 10 * time.Minute,
		StaleAttemptTTL: 30 * time.Minute,
	}
	rl := NewRateLimiter(cfg)
	defer rl.Stop()

	key := "success-key"
	rl.Allow(key)
	rl.Allow(key)
	if rl.Allow(key) {
		t.Fatal("expected third Allow to be false before Success")
	}

	rl.Success(key)

	if !rl.Allow(key) {
		t.Fatal("expected Allow to be true after Success cleared attempts")
	}
}

func TestCleanup_RemovesStaleEntries(t *testing.T) {
	cfg := Config{
		MaxAttempts:     5,
		Window:          5 * time.Minute,
		BlockDuration:   15 * time.Minute,
		CleanupInterval: 50 * time.Millisecond,
		StaleAttemptTTL: 100 * time.Millisecond,
	}
	rl := NewRateLimiter(cfg)
	defer rl.Stop()

	key := "stale-key"
	rl.Allow(key)

	// Wait for cleanup interval + stale TTL + buffer.
	time.Sleep(300 * time.Millisecond)

	rl.mu.Lock()
	_, exists := rl.attempts[key]
	rl.mu.Unlock()

	if exists {
		t.Fatal("expected stale entry to be removed by cleanup")
	}
}

func TestCleanup_KeepsBlockedEntries(t *testing.T) {
	cfg := Config{
		MaxAttempts:     1,
		Window:          5 * time.Minute,
		BlockDuration:   500 * time.Millisecond,
		CleanupInterval: 50 * time.Millisecond,
		StaleAttemptTTL: 30 * time.Minute,
	}
	rl := NewRateLimiter(cfg)
	defer rl.Stop()

	key := "blocked-key"
	rl.Allow(key)
	rl.Allow(key) // triggers block

	time.Sleep(100 * time.Millisecond)

	rl.mu.Lock()
	_, exists := rl.attempts[key]
	rl.mu.Unlock()

	if !exists {
		t.Fatal("expected blocked entry to still exist")
	}
}

// TestCleanupLoopStopsOnStop is a regression test for issue #90 G1:
// cleanupLoop must exit after Stop() instead of busy-looping on the
// closed stopCh (100% CPU + goroutine leak).
//
// This test was inadvertently removed by PR #112 (which deleted the G1
// select-guard in cleanupLoop along with this test). Without the guard,
// Stop() closes stopCh, the inner select returns, the outer for-loop
// re-iterates and immediately re-reads the closed stopCh in a tight loop
// with no sleep — pinning one CPU core forever. See docs/ISSUES.md REV58.
func TestCleanupLoopStopsOnStop(t *testing.T) {
	cfg := Config{
		MaxAttempts:     5,
		Window:          5 * time.Minute,
		BlockDuration:   15 * time.Minute,
		CleanupInterval: 10 * time.Millisecond,
		StaleAttemptTTL: 30 * time.Minute,
	}
	rl := NewRateLimiter(cfg)

	// Wait for cleanupLoop goroutine to start so we can later verify it exits.
	// Without this, the test could race the goroutine's creation and pass
	// even on the buggy version (goroutine never observed running).
	startDeadline := time.Now().Add(500 * time.Millisecond)
	for time.Now().Before(startDeadline) {
		if hasCleanupLoopGoroutine() {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if !hasCleanupLoopGoroutine() {
		t.Fatal("cleanupLoop goroutine did not start within 500ms — cannot verify regression")
	}

	rl.Stop()

	// After Stop(), cleanupLoop should exit promptly. Poll all goroutine
	// stacks for up to 2s; the fixed implementation exits on the first
	// iteration of the outer for-loop (sub-millisecond). The buggy version
	// spins forever on the closed stopCh and never exits.
	stopDeadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(stopDeadline) {
		if !hasCleanupLoopGoroutine() {
			return // passed
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("cleanupLoop goroutine still running 2s after Stop() — busy-loop leak not fixed")
}

// hasCleanupLoopGoroutine reports whether a ratelimit.cleanupLoop goroutine
// is currently live by scanning all goroutine stacks. The lowercase pattern
// "cleanupLoop" matches the (*RateLimiter).cleanupLoop method frame (and its
// inner func1 closure) but does NOT match this test's own frames
// (TestCleanupLoopStopsOnStop / hasCleanupLoopGoroutine), which use an
// uppercase "CleanupLoop" — strings.Contains is case-sensitive.
func hasCleanupLoopGoroutine() bool {
	buf := make([]byte, 1<<20)
	n := runtime.Stack(buf, true)
	return strings.Contains(string(buf[:n]), "cleanupLoop")
}
