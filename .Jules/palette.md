## 2025-02-12 - Fix keyboard interaction flow
**Learning:** In Jetpack Compose, keyboard interactions should not leave focus lingering when users explicitly indicate they are "done" filling out a form field.
**Action:** Unconditionally clearing focus after "Done" is pressed is good for UX to dismiss the keyboard, but doing it conditionally based on validation prevents disrupting a user trying to submit an invalid form where they might want to correct it immediately.
