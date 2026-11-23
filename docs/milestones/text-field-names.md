# Text-field accessible names (#331)

**Found in #326:** with Compose UI 1.11.4 (BOM 2026.06.01), a text field's `contentDescription` never reached its
AccessibilityNodeInfo, and the jar never calls `setHintText` or `setLabeledBy`. The report form's details field read
`EditText text= name=null hint=null labeledBy=false` until its visible label was kept in semantics before it.

## #331a: the probe (TL plan, 2026-11-23)

`TextFieldNodeNamesTest` (androidTest, one class) composes each pattern the app uses and reads the field's **real**
AccessibilityNodeInfo through UiAutomation with `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` (the path an accessibility service
such as TalkBack uses, as `ReportDetailsAccessibilityTest` does), empty and filled.
- **Facts per field:** class, text, hintText, contentDescription, labeledBy, isShowingHintText, isPassword, and the
  preceding text-bearing node in traversal order.
- **Output:** `ccm-a11y-nodes/text-field-nodes-api<level>.json`, collected into the `a11y-nodes` report folder
  (`collect_a11y_nodes`, validated whole: fixed name, size cap, exact keys, fixture-only text).
- **Measurement only:** no outcome asserts. It fails only if a node can't be obtained or the file can't be written. It
  reads nodes only (no keyboard, IME or focus request), and runs once per level in the normal unfiltered suite. #331b
  turns it into strict asserts or removes it.
- **Spoken output** isn't measured; it stays with the owner's manual TalkBack checks.

**Cases:**
- **real:** the app's own composables. `MessageInput` in both overloads, and `StepUpDialog`'s secret (empty only: the
  secret is the dialog's own state, and filling it needs text input, which requests focus).
- **material:** Material `OutlinedTextField` with exactly the parameters the call sites pass. None of those call sites
  adds semantics of its own, so the component and its parameters are the whole of what's measured:
  - `outlined-label-single-line`;
  - `outlined-label-password` (`PasswordVisualTransformation`);
  - `outlined-label-supporting` (`supportingText`);
  - `outlined-label-supporting-password` (both);
  - `outlined-label-multiline`.
- **synthetic** (comparison only, not app patterns as such): placeholder only; contentDescription only; and a visible
  label kept before an unlabelled field (the report details pattern).

## Call sites (main 5e0ca66) and the case that represents each

| Call site | Field | Parameters | Probe case |
| --- | --- | --- | --- |
| `core/ui/MessageInput.kt:25` | MessageInput (String) | BasicTextField, merged semantics, Material decoration `label` | `message-input-string` (real) |
| `core/ui/MessageInput.kt:40` | MessageInput (TextFieldValue) | same | `message-input-value` (real) |
| `feature/conversation/ConversationScreen.kt:599` | room composer | MessageInput (TextFieldValue) | `message-input-value` (real) |
| `feature/direct/DirectScreens.kt:531` | DM composer | MessageInput (TextFieldValue) | `message-input-value` (real) |
| `core/ui/AuthorChangeDialog.kt:128` | author edit body | MessageInput (String) | `message-input-string` (real) |
| `feature/auth/StepUpDialog.kt:79` | step-up password or recovery code | label, singleLine, password | `step-up-password` (real, empty); filled: `outlined-label-password` |
| `app/ChitChatApp.kt:1096` | server address | label, supportingText | `outlined-label-supporting` (ServerEditor is private and needs a SettingsViewModel) |
| `feature/auth/AuthScreen.kt:193` | sign-in username | label, singleLine | `outlined-label-single-line` |
| `feature/auth/AuthScreen.kt:196` | sign-in password | label, singleLine, password | `outlined-label-password` |
| `feature/auth/AuthScreen.kt:275` | sign-in recovery code | label, singleLine, password | `outlined-label-password` |
| `feature/auth/RegistrationForm.kt:110` | create username | label, singleLine, supportingText | `outlined-label-supporting` |
| `feature/auth/RegistrationForm.kt:116` | create password | label, password | `outlined-label-password` |
| `feature/auth/RegistrationForm.kt:122` | confirm password | label, password | `outlined-label-password` |
| `feature/auth/ResetPasswordForm.kt:131` | reset username | label, singleLine | `outlined-label-single-line` |
| `feature/auth/ResetPasswordForm.kt:137` | recovery key | label, singleLine, supportingText | `outlined-label-supporting` |
| `feature/auth/ResetPasswordForm.kt:142` | new password | label, supportingText, password | `outlined-label-supporting-password` |
| `feature/auth/ResetPasswordForm.kt:150` | confirm new password | label, password | `outlined-label-password` |
| `feature/auth/ResetPasswordForm.kt:218` | reset MFA recovery code | label, supportingText | `outlined-label-supporting` |
| `feature/direct/DirectScreens.kt:140` | DM people search | label, singleLine | `outlined-label-single-line` |
| `feature/search/MessageSearchScreen.kt:89` | message search | label, singleLine | `outlined-label-single-line` |
| `feature/push/NotificationSettingsScreen.kt:150` | quiet-hours zone | label, singleLine, supportingText | `outlined-label-supporting` |
| `feature/rooms/RoomsScreen.kt:126` | room lookup by ID | label, singleLine | `outlined-label-single-line` |
| `feature/rooms/RoomsScreen.kt:261` | room create fields (`RoomField`) | label, singleLine or minLines 2 | `outlined-label-single-line`, `outlined-label-multiline` |
| `feature/rooms/RoomTransferUi.kt:106` | transfer member filter | label, singleLine | `outlined-label-single-line` |
| `feature/reports/ReportUi.kt:411` | report details | no label; visible label kept in semantics before it (#326) | `ReportDetailsAccessibilityTest` (real, existing asserts); `synthetic-visible-label-before` for comparison |

**Coverage:** every call site maps to a case. The search screens and the server editor aren't reusable in isolation
without a production change, so they're represented by the Material cases with their exact parameters.

## Results

Pending the first CI run of #331a (API35 and API36). The per-field facts, the verdict per pattern and the fixes #331b
needs go here.

## Compose versions

A recorded finding only (TL condition 4); any bump is a separate proposal as a compatible toolchain set. Pending.
