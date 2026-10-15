## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.
## 2024-10-15 - Async Button Spinner Contrast and Layout Transition
**Learning:** Hardcoding `MaterialTheme.colorScheme.onPrimary` for a `CircularProgressIndicator` inside a disabled button causes accessibility/contrast issues, as it ignores the dimmed disabled text color. Additionally, wrapping a Compose `Button`'s content in `AnimatedContent` drops the implicit `RowScope`, which breaks side-by-side layout of the spinner and text.
**Action:** Always use `LocalContentColor.current` for spinners in buttons to respect disabled contrast states, and explicitly wrap the inner contents of `AnimatedContent` in a `Row` to preserve horizontal layout.
