## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.

## 2024-05-24 - AnimatedContent inside Buttons and Disabled Colors
**Learning:** Abrupt state swaps in buttons during async operations feel jarring. Wrapping the button content in `AnimatedContent` provides a smooth transition. However, adding `AnimatedContent` removes the implicit `RowScope` of the `Button`, so we must explicitly wrap the inner elements in a `Row` to maintain the layout. Furthermore, hardcoding `MaterialTheme.colorScheme.onPrimary` in a `CircularProgressIndicator` breaks visual contrast when the button is disabled.
**Action:** Use `AnimatedContent` with an explicit `Row` inside `Button` for loading states. Always use `LocalContentColor.current` for `CircularProgressIndicator` inside buttons so it respects the button's disabled state color.
