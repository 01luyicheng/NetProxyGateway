## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.
## 2024-05-19 - Button async loading transition with AnimatedContent
**Learning:** Swapping between a standard text and a loading indicator (like `CircularProgressIndicator`) inside a Jetpack Compose `Button` abruptly can be jarring. Using `AnimatedContent` makes this transition smooth. However, `Button` provides a `RowScope` to its content. Wrapping the content in `AnimatedContent` removes this implicit scope, meaning the elements inside will no longer automatically layout side-by-side as a Row.
**Action:** When using `AnimatedContent` inside a Jetpack Compose `Button` for async loading states, explicitly wrap the inner elements (e.g., the spinner and text) inside a `Row` to preserve the side-by-side layout structure, and use `LocalContentColor.current` for the spinner color.
