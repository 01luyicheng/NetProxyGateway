## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.
## 2024-05-18 - Jetpack Compose 异步加载按钮过渡
**Learning:** 在 Jetpack Compose 中，当按钮在加载状态（带有进度条指示器）和默认状态间切换时，直接通过 `if/else` 会导致突兀的视觉切换。
**Action:** 对于执行异步操作可能需要显示加载 Spinner 的按钮，应使用 `AnimatedContent` 进行平滑过渡。此外，由于插入 `AnimatedContent` 会消除由 `Button` 提供的隐式 `RowScope`，必须显式使用 `Row` 包裹内部元素来保持原本在 `Button` 里的并排布局，并通过 `LocalContentColor.current` 设置指示器颜色以保证按钮禁用时的对比度。
