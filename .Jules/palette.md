## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.

## 2024-05-19 - Button Loading Transitions and Contrast
**Learning:** Wrapping a button's content in `AnimatedContent` for smooth loading transitions removes the implicit `RowScope` from the button, requiring an explicit `Row` wrapper to maintain side-by-side layout (e.g. spinner + text). Additionally, using a hardcoded color like `MaterialTheme.colorScheme.onPrimary` for a `CircularProgressIndicator` inside a disabled button causes low contrast; `LocalContentColor.current` automatically adjusts to the disabled state.
**Action:** When adding smooth transitions to buttons with `AnimatedContent`, always explicitly wrap the inner elements in a `Row`. For loading spinners inside buttons, use `LocalContentColor.current` rather than hardcoding colors to ensure correct disabled state contrast.
