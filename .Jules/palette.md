## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.

## 2024-05-XX - Smooth transitions for button loading states
**Learning:** For Jetpack Compose buttons displaying loading spinners during async operations, abrupt visual swaps between text and a spinner are jarring. Furthermore, inserting `AnimatedContent` inside a `Button` removes the implicit `RowScope`, which breaks the layout if inner elements aren't wrapped explicitly in a `Row`. Finally, using a hardcoded color like `MaterialTheme.colorScheme.onPrimary` for the spinner is incorrect in disabled states.
**Action:** Wrap the button content in `AnimatedContent` to provide smooth transitions between default and loading states. Explicitly wrap the inner elements in a `Row` to preserve side-by-side layouts. Use `LocalContentColor.current` for the spinner color to ensure correct visual contrast in both enabled and disabled states.
