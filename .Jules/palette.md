## Palette Journal
## 2024-05-18 - Smooth transitions in Jetpack Compose
**Learning:** For Jetpack Compose micro-UX improvements, abrupt state swaps (like changing an icon or its color instantly based on a boolean state) can feel jarring.
**Action:** Always prefer smooth transitions like `AnimatedVisibility`, `animateColorAsState`, and `Crossfade`/`AnimatedContent` over abrupt state swaps to provide better visual continuity. When using `Crossfade`, always compute derived properties (like the target icon) *inside* the lambda using the passed `targetState` parameter to prevent immediate outer-scope state updates from breaking the fade-out animation.

## 2024-05-XX - Smooth transitions for state swaps
**Learning:** Abrupt state swaps in status cards (like VPN or MQTT connection status) can feel jarring. Applying `animateColorAsState` for background colors and `AnimatedContent` for icons/text creates a much smoother, polished micro-interaction. Computing derived state inside the animation lambda is critical so fade-outs display correctly.
**Action:** Use `animateColorAsState` and `AnimatedContent` for status cards, ensuring target states compute their own icons/text within the lambda.

## 2024-10-19 - 按钮禁用状态下 Spinner 的对比度和切换平滑度
**Learning:** 在 Jetpack Compose 中，禁用状态下的按钮中的 CircularProgressIndicator 若硬编码使用 onPrimary 颜色会导致视觉对比度异常，因为并没有跟随按钮禁用主题置灰。并且使用硬切换而不是 AnimatedContent 会造成体验不流畅。
**Action:** 总是用 AnimatedContent 包装按钮内的异步 loading 状态，并在 Spinner 内使用 LocalContentColor.current 获取正确的当前状态上下文色彩。
