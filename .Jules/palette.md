## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.
## 2024-05-24 - AnimatedContent inside Button in Jetpack Compose
**Learning:** When adding `AnimatedContent` inside a `Button` to transition between states (like a loading spinner and text), the `AnimatedContent` removes the implicit `RowScope` provided by the `Button`. This can break side-by-side layouts, requiring an explicit `Row` wrapper inside the animation lambda. Also, for proper disabled state contrast, use `LocalContentColor.current` rather than `MaterialTheme.colorScheme.onPrimary`.
**Action:** When wrapping button content with `AnimatedContent` for transitions, always provide an explicit layout (like a `Row`) inside the lambda and use `LocalContentColor.current` for tinting components like `CircularProgressIndicator`.
