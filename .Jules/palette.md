## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.

## 2026-08-04 - Smooth loading states and proper contrast for disabled buttons
**Learning:** For Jetpack Compose buttons displaying loading spinners during async operations, abrupt visual swaps (like changing button text to a spinner) can feel jarring. Furthermore, hardcoding colors like `MaterialTheme.colorScheme.onPrimary` for the spinner can lead to contrast issues when the button is in a disabled state.
**Action:** Wrap the content in `AnimatedContent` to provide smooth transitions between default and loading states. When using a `CircularProgressIndicator` inside a button (especially one that might be disabled), use `LocalContentColor.current` rather than hardcoding colors to ensure correct visual contrast in all states.
