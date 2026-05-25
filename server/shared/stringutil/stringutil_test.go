package stringutil

import "testing"

func TestFirstNonEmpty(t *testing.T) {
	tests := []struct {
		name     string
		values   []string
		expected string
	}{
		{
			name:     "first non-empty",
			values:   []string{"", "b", "c"},
			expected: "b",
		},
		{
			name:     "first value",
			values:   []string{"a", "b", "c"},
			expected: "a",
		},
		{
			name:     "all empty",
			values:   []string{"", "", ""},
			expected: "",
		},
		{
			name:     "no values",
			values:   []string{},
			expected: "",
		},
		{
			name:     "single non-empty",
			values:   []string{"only"},
			expected: "only",
		},
		{
			name:     "single empty",
			values:   []string{""},
			expected: "",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := FirstNonEmpty(tt.values...)
			if got != tt.expected {
				t.Errorf("FirstNonEmpty(%v) = %q, want %q", tt.values, got, tt.expected)
			}
		})
	}
}
