// Package ratelimit provides a simple in-memory rate limiter with blocking support.
package ratelimit

import (
	"log"
	"sync"
	"sync/atomic"
	"time"
)

// Config holds configurable parameters for the rate limiter.
type Config struct {
	MaxAttempts     int
	Window          time.Duration
	BlockDuration   time.Duration
	CleanupInterval time.Duration
	StaleAttemptTTL time.Duration
}

// DefaultConfig returns a Config with sensible defaults.
func DefaultConfig() Config {
	return Config{
		MaxAttempts:     5,
		Window:          5 * time.Minute,
		BlockDuration:   15 * time.Minute,
		CleanupInterval: 10 * time.Minute,
		StaleAttemptTTL: 30 * time.Minute,
	}
}

// Attempt tracks the state for a single key.
type Attempt struct {
	Count      int
	LastTry    time.Time
	Blocked    bool
	BlockUntil time.Time
}

// RateLimiter provides in-memory rate limiting per key.
type RateLimiter struct {
	attempts     map[string]*Attempt
	mu           sync.RWMutex
	config       Config
	stopCh       chan struct{}
	restartCount int32
}

// NewRateLimiter creates a new RateLimiter with the given configuration.
func NewRateLimiter(config Config) *RateLimiter {
	rl := &RateLimiter{
		attempts: make(map[string]*Attempt),
		config:   config,
		stopCh:   make(chan struct{}),
	}
	go rl.cleanupLoop()
	return rl
}

// NewRateLimiterWithDefaults creates a new RateLimiter using DefaultConfig.
func NewRateLimiterWithDefaults() *RateLimiter {
	return NewRateLimiter(DefaultConfig())
}

// Stop halts the background cleanup goroutine.
func (rl *RateLimiter) Stop() {
	close(rl.stopCh)
}

// Allow checks whether the given key is permitted to proceed.
// It returns true if the attempt is allowed, false if the key is blocked.
func (rl *RateLimiter) Allow(key string) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	now := time.Now()
	att, exists := rl.attempts[key]
	if !exists {
		rl.attempts[key] = &Attempt{
			Count:   1,
			LastTry: now,
		}
		return true
	}

	if att.Blocked {
		if now.Before(att.BlockUntil) {
			log.Printf("Rate limit exceeded for %s, blocked for %v", key, att.BlockUntil.Sub(now))
			return false
		}
		// Block expired; reset the attempt.
		att.Blocked = false
		att.BlockUntil = time.Time{}
		att.Count = 1
		att.LastTry = now
		return true
	}

	// Reset count if outside the window.
	if now.Sub(att.LastTry) > rl.config.Window {
		att.Count = 1
		att.LastTry = now
		return true
	}

	att.Count++
	att.LastTry = now

	if att.Count > rl.config.MaxAttempts {
		att.Blocked = true
		att.BlockUntil = now.Add(rl.config.BlockDuration)
		log.Printf("Rate limit exceeded for %s, blocked for %v", key, rl.config.BlockDuration)
		return false
	}

	return true
}

// Success clears any attempt record for the given key.
func (rl *RateLimiter) Success(key string) {
	rl.mu.Lock()
	delete(rl.attempts, key)
	rl.mu.Unlock()
}

// cleanupLoop periodically removes stale entries from the attempts map.
// It recovers from panics and restarts itself up to 3 times.
func (rl *RateLimiter) cleanupLoop() {
	maxRestarts := int32(3)
	for {
		func() {
			defer func() {
				if r := recover(); r != nil {
					log.Printf("ratelimit cleanupLoop panic recovered: %v", r)
					if atomic.AddInt32(&rl.restartCount, 1) <= maxRestarts {
						log.Printf("ratelimit cleanupLoop restarting (%d/%d)", rl.restartCount, maxRestarts)
					} else {
						log.Printf("ratelimit cleanupLoop exceeded max restarts, exiting")
						// Set restartCount above max to prevent further restarts.
						atomic.StoreInt32(&rl.restartCount, maxRestarts+1)
					}
				}
			}()

			ticker := time.NewTicker(rl.config.CleanupInterval)
			defer ticker.Stop()

			for {
				select {
				case <-ticker.C:
					rl.cleanup()
				case <-rl.stopCh:
					return
				}
			}
		}()

		// G1 (issue #90): if Stop() closed stopCh during the inner func,
		// exit the outer loop. Otherwise the next iteration creates a new
		// ticker then immediately reads from the closed stopCh and returns
		// from the inner func, spinning forever (100% CPU + goroutine leak).
		select {
		case <-rl.stopCh:
			return
		default:
		}

		if atomic.LoadInt32(&rl.restartCount) > maxRestarts {
			return
		}
	}
}

// cleanup removes stale or expired attempt entries.
func (rl *RateLimiter) cleanup() {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	now := time.Now()
	for key, att := range rl.attempts {
		if att.Blocked && now.After(att.BlockUntil) {
			delete(rl.attempts, key)
			continue
		}
		if !att.Blocked && now.Sub(att.LastTry) > rl.config.StaleAttemptTTL {
			delete(rl.attempts, key)
		}
	}
}
