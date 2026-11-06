## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.
## 2024-05-24 - 改善 Compose 按钮加载状态的过渡与颜色
**Learning:** 在禁用的 Compose 按钮中直接切换加载动画会导致视觉突兀，并且硬编码 `MaterialTheme.colorScheme.onPrimary` 会导致颜色对比度错误（因为禁用状态的按钮会改变其内容颜色）。
**Action:** 使用 `AnimatedContent` 包装一个 `Row` 来实现平滑过渡，并始终使用 `LocalContentColor.current` 使 `CircularProgressIndicator` 能够正确继承按钮在禁用状态下的内容颜色。
