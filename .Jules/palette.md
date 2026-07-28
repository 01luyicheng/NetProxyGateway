
## 2024-05-18 - Jetpack Compose Network Status Smooth Transitions
**Learning:** 在 Jetpack Compose UI 中，生硬的状态切换（例如直接替换网络图标）会带来突兀的视觉体验。
**Action:** 使用 `Crossfade` 实现图标切换的过渡效果，并使用 `animateColorAsState` 实现颜色渐变，从而创造平滑自然的交互体验。确保派生状态计算在动画 Lambda 表达式内进行（例如 `Crossfade(targetState = active) { isActive -> ... }`），以避免破坏过渡效果。
