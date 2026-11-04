## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.
## 2024-11-04 - Smooth Button Transitions and Content Color
**Learning:** Using `AnimatedContent` inside Jetpack Compose buttons creates a smoother UX compared to abrupt state swaps. When doing so, inner elements must be wrapped in a `Row` to maintain layout, and `LocalContentColor.current` should be used instead of hardcoded colors (like `MaterialTheme.colorScheme.onPrimary`) to ensure sufficient contrast when the button is in a disabled state.
**Action:** Always wrap loading states in buttons with `AnimatedContent` and a `Row` with `verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center`, and use `LocalContentColor.current` for spinners.
