## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.
## 2024-10-25 - Smooth button state transitions and color contrast during async operations
**Learning:** In Jetpack Compose, replacing button content during async operations (like showing a spinner instead of text) causes abrupt visual swaps. Further, hardcoding colors like `MaterialTheme.colorScheme.onPrimary` inside a spinner makes it unreadable or incorrect when the button is disabled, because disabled buttons change their content color.
**Action:** Always wrap async button content inside an `AnimatedContent` for smooth crossfading. Make sure to wrap the content inside a `Row` to maintain the side-by-side layout, since `AnimatedContent` removes the implicit `RowScope` of the `Button`. For the `CircularProgressIndicator` color, use `LocalContentColor.current` to automatically adapt to the button's enabled/disabled state instead of hardcoding `onPrimary`.
