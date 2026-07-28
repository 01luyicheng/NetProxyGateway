## 2024-05-18 - Jetpack Compose Network Status Smooth Transitions
**Learning:** Abrupt state changes (like swapping network icons directly) feel jarring in Jetpack Compose UI.
**Action:** Use `Crossfade` for icon swapping and `animateColorAsState` for tint color changes to create smooth, natural transitions. Ensure derived state calculations happen within the animation lambdas (e.g. `Crossfade(targetState = active) { isActive -> ... }`) to avoid breaking the transition effect.
