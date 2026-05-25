// Package stringutil provides common string manipulation helpers.
package stringutil

// FirstNonEmpty returns the first non-empty string from the provided values.
// If all values are empty, it returns an empty string.
func FirstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}
