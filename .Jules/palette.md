## 2026-07-24 - Jetpack Compose Network Status Smooth Transitions
**Learning:** Users notice abrupt UI state swaps. In Jetpack Compose, when updating visual state (like network icons or text colors) based on a boolean value, using `Crossfade` and `animateColorAsState` provides a much smoother UX than immediate swapping.
**Action:** Always wrap visual elements that toggle between states (icons, colors, text) in `Crossfade` and calculate intermediate states inside the animation lambda to ensure smooth visual transitions.
