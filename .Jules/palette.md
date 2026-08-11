## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.

## 2024-07-01 - 平滑的按钮过渡动画和加载图标颜色对比度
**Learning:** 在 Jetpack Compose 的 `Button` 中使用 `AnimatedContent` 来实现默认状态和加载状态之间的平滑过渡时，会丢失隐式的 `RowScope`，从而导致布局问题。此外，硬编码加载图标颜色（如 `onPrimary`）在按钮被禁用时会导致视觉对比度不足。
**Action:** 在使用 `AnimatedContent` 时，始终将按钮的内部元素包裹在 `Row` 中，并使用 `LocalContentColor.current` 作为 `CircularProgressIndicator` 的颜色，以确保在启用和禁用状态下都有正确的对比度。
