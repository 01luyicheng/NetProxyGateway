## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.
## 2024-05-24 - Smooth transitions and accessible contrast for async buttons
**Learning:** Abrupt state swaps in async buttons (like showing a spinner) feel jarring, and hardcoding `MaterialTheme.colorScheme.onPrimary` for the spinner leads to poor contrast when the button is disabled. Inserting `AnimatedContent` for transitions removes the implicit `RowScope` provided by `Button`.
**Action:** Wrap the button content in `AnimatedContent` for smooth transitions between default and loading states. Explicitly wrap the inner elements in a `Row` to preserve side-by-side layouts. Use `LocalContentColor.current` for the `CircularProgressIndicator` color to ensure correct visual contrast in the disabled state.
