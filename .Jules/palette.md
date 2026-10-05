## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.
## 2024-05-18 - Smooth transitions and contrast in Button loading states
**Learning:** For Jetpack Compose buttons displaying loading spinners during async operations, abrupt swaps are jarring. Additionally, hardcoding `MaterialTheme.colorScheme.onPrimary` for the spinner inside a disabled button causes poor contrast, as the button itself changes color when disabled.
**Action:** Wrap button inner content in `AnimatedContent` to provide smooth transitions between default and loading states. Explicitly wrap the elements in a `Row` inside the `AnimatedContent` to preserve side-by-side layouts, since `AnimatedContent` drops the `RowScope`. Furthermore, always use `LocalContentColor.current` for spinners within disabled buttons to ensure correct visual contrast.
