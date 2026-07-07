## 2025-02-20 - [Add Clear Icon to Pairing Code Input]
**Learning:** Adding a clear icon button to input fields provides a quick way for users to reset their input. It is important to conditionally show the icon only when the input field is not empty and the current action (e.g. pairing) is not in progress. Screen readers need a localized `contentDescription` for actionable icons.
**Action:** When adding text inputs in Compose, consider adding a clear icon when the user frequently needs to reset their input. Ensure proper accessibility configuration with string resources.
