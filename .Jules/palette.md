## 2024-07-25 - Smooth Transitions for Status Cards
**Learning:** Abrupt state swaps in status cards (like connected vs disconnected) can feel jarring. Using `AnimatedContent` and `animateColorAsState` provides a much smoother UX. Crucially, derived state (like the target icon and text) must be computed *inside* the animation lambda based on `targetState` to prevent immediate outer-scope updates from breaking the fade-out animation.
**Action:** Always prefer `AnimatedContent` for status icons/text, and compute properties within the lambda.
