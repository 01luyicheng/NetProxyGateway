## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.

## 2024-10-14 - AnimatedContent and contrast in Jetpack Compose Buttons
**Learning:** Abrupt state swaps in buttons (like showing a loading spinner) feel jarring. Inserting `AnimatedContent` breaks the implicit `RowScope` of a `Button`. Using hardcoded colors for loading spinners causes poor contrast when the button is disabled.
**Action:** Wrap button contents in `AnimatedContent` for smooth transitions. Recreate the side-by-side layout explicitly by wrapping the inner elements in a `Row`. Use `LocalContentColor.current` instead of hardcoded colors for `CircularProgressIndicator` to adapt properly to the disabled state.
