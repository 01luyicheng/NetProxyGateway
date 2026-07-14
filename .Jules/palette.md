## 2024-05-24 - Conditional Focus Clearing on Keyboard Actions in Jetpack Compose
**Learning:** When wiring keyboard actions like `onDone` in Jetpack Compose text fields, unconditionally calling `focusManager.clearFocus()` can interrupt the user's flow if the input is invalid or incomplete. Users have to tap the field again to resume typing.
**Action:** Focus should only be cleared conditionally (e.g., if input validation passes) rather than unconditionally. This allows the user to continue typing or correcting their input without breaking focus if validation fails. Apply this pattern to all form fields that trigger actions on keyboard submission.

## 2024-07-14 - Replace Text Characters with Icons for UI State Indicators
**Learning:** Avoid using text characters (like '▲' or '▼') as UI state indicators (such as expand/collapse toggles) because screen readers read them literally (e.g., "black up-pointing triangle"), causing confusion for visually impaired users.
**Action:** Use standard Material Icons (e.g., `KeyboardArrowUp`, `KeyboardArrowDown`) instead for better semantics and visual consistency. For purely decorative elements (such as icons placed adjacent to descriptive text in a toggleable row), retain `contentDescription = null` to prevent redundant screen reader announcements.
