package ratelimit

import (
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
