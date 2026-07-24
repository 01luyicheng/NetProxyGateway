## 2026-07-24 - Jetpack Compose Network Status Smooth Transitions
**Learning:** Users notice abrupt UI state swaps. In Jetpack Compose, when updating visual state (like network icons or text colors) based on a boolean value, use `Crossfade` to keep each transitioning item aligned with its own state.
**Action:** Always wrap visual elements that toggle between states (icons, colors, text) in `Crossfade`, and calculate the icon, text, and color from the lambda's state so outgoing content is not rendered with the incoming state's color.
