## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.
## 2024-05-24 - 为 Jetpack Compose 按钮中的加载状态提供平滑过渡
**Learning:** 在 Jetpack Compose 中，当按钮在异步操作（如“配对”与“配对中...”）期间直接切换其内部内容时，会导致视觉上突兀的跳跃。
**Action:** 使用 `AnimatedContent` 包裹按钮的内容。因为 `AnimatedContent` 会移除 `Button` 隐式提供的 `RowScope`，所以要显式使用 `Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center)` 来保持水平布局，并使用 `LocalContentColor.current` 而不是硬编码的主题颜色，以确保在禁用或加载状态下的可访问性对比度。
