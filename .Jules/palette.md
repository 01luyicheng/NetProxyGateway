## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.
## 2024-10-10 - Jetpack Compose Button Loading Transitions
**Learning:** In Jetpack Compose, replacing button content with a loading spinner abruptly during async operations causes a jarring visual change. `AnimatedContent` should be used to provide smooth fade-through transitions. Furthermore, placing `AnimatedContent` directly inside a `Button` removes the implicit `RowScope`, breaking side-by-side layouts, so an explicit `Row` is required inside the `AnimatedContent` lambda. Additionally, hardcoding spinner colors to `MaterialTheme.colorScheme.onPrimary` breaks contrast in disabled button states; `LocalContentColor.current` should be used instead.
**Action:** Always wrap loading/default button state swaps in `AnimatedContent`, re-establish the `Row` layout inside the animation lambda, and use `LocalContentColor.current` for spinners.
