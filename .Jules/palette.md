## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.
## 2024-05-24 - 配对按钮的加载状态过渡与对比度改进
**Learning:** 在 Jetpack Compose 中，当按钮处于加载和禁用状态时，使用硬编码的颜色（如 `MaterialTheme.colorScheme.onPrimary`）会导致在禁用状态下对比度不足。另外，在按钮内插入 `AnimatedContent` 会移除隐式的 `RowScope`。
**Action:** 对于显示加载动画的按钮，使用 `AnimatedContent` 包裹内容以提供平滑过渡，在其中显式使用 `Row` 来保持并排布局，并始终使用 `LocalContentColor.current` 确保禁用状态下对比度正确。
