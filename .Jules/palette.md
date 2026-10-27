## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.

## 2024-05-24 - AnimatedContent inside disabled Button
**Learning:** When adding `AnimatedContent` inside a `Button` to transition to a loading spinner, two things break: 1) the implicit `RowScope` is lost, so elements stack unless wrapped in a `Row`, and 2) the `CircularProgressIndicator` color must use `LocalContentColor.current` instead of `MaterialTheme.colorScheme.onPrimary` so it properly matches the disabled text color (gray) rather than rendering bright white on a disabled gray button.
**Action:** Wrap `AnimatedContent` inner elements in a `Row` to preserve side-by-side layouts, and use `LocalContentColor.current` for spinners inside buttons.
