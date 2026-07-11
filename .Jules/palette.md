## 2024-05-24 - Conditionally Clear Focus on Validation
**Learning:** In Jetpack Compose, when wiring keyboard actions like `onDone`, focus should only be cleared conditionally (e.g., if input validation passes) rather than unconditionally. Unconditionally clearing focus interrupts the user's flow if they accidentally trigger `onDone` before finishing the input.
**Action:** When implementing `KeyboardActions`, wrap `focusManager.clearFocus()` within a validation block and trigger the desired action seamlessly.
