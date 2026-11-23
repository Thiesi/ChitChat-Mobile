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

## Results (run 45140319444, head aadb8b8; API35 and API36)

Both levels returned 21 entries each, and **every fact agrees between API35 and API36** for every case and state. The
probe passed on both levels.

| Case | Kind | State | text | hintText | contentDescription | labeledBy | showingHint | password | preceding node |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `message-input-string` | real | empty / filled | "" / typed | null | null | null | false | false | none |
| `message-input-value` | real | empty / filled | "" / typed | null | null | null | false | false | none |
| `step-up-password` | real | empty | "" | null | null | null | false | **true** | the dialog's explanation text, not its label |
| `outlined-label-single-line` | material | empty / filled | "" / typed | null | null | null | false | false | none |
| `outlined-label-password` | material | empty / filled | "" / masked | null | null | null | false | **true** | none |
| `outlined-label-supporting` | material | empty / filled | "" / typed | null | null | null | false | false | none |
| `outlined-label-supporting-password` | material | empty / filled | "" / masked | null | null | null | false | **true** | none |
| `outlined-label-multiline` | material | empty / filled | "" / typed | null | null | null | false | false | none |
| `synthetic-placeholder-only` | synthetic | empty / filled | "" / typed | null | null | null | false | false | none |
| `synthetic-content-description-only` | synthetic | empty / filled | "" / typed | null | **null** | null | false | false | none |
| `synthetic-visible-label-before` | synthetic | empty / filled | "" / typed | null | null | null | false | false | **"Fixture question"** |

(The class is `android.widget.EditText` throughout. "typed" is the fixture text and "masked" its bullets; the typed text
is the node's text in every filled case.)

**What this shows, with Compose UI 1.11.4:**
- **The Material `label` slot reaches none of the node's name properties:** no hintText, no contentDescription, no
  labeledBy, empty or filled, in MessageInput and in every OutlinedTextField pattern. Neither does `placeholder`.
- **`contentDescription` on the field doesn't reach the node either** (the synthetic case), confirming #326.
- **The only pattern with a readable name nearby** is a visible label kept in semantics directly before the field (the
  report pattern): it's the preceding text node in traversal order.
- **Not recorded:** whether the label's text exists as a descendant node inside the field, and whether TalkBack reads
  it when it lands on the field. The CI images have no TalkBack, and the probe records only the field and the node
  before it. That decides whether the label-slot fields are actually unnamed to a TalkBack user, so it should be
  measured before any fix (see #331b below).

## Gaps and the fix pattern proposed for each

| Call sites | Measured | Proposed |
| --- | --- | --- |
| every `label`-slot OutlinedTextField (19 call sites: auth 11, step-up, server address, DM people search, message search, push, rooms 2, transfer) and MessageInput (its 2 definitions; the room and DM composers and the author edit body) | no name on the node; nothing named before it (step-up: its explanation) | **#331b step 0:** add the field's descendant text nodes to the probe (no other change), and run it. If the label is a descendant that an accessibility service reads with the field, the gap is smaller than this table. Otherwise the fix pattern is the report's: the visible label kept in semantics directly before the field. That's a visible layout change for the label slot, so it needs the PL's ruling and J. |
| report details (`ReportUi.kt:411`) | already the visible-label-before pattern (#326), with its own real-node test | none |

**Not proposed here:** contentDescription or placeholder (measured not to reach the node), and any Compose bump (see
below). Spoken output stays with the owner's manual TalkBack checks; nothing here claims it.

## Compose versions

A recorded finding only (TL condition 4). The app uses BOM 2026.06.01 (Compose UI 1.11.4). Google's Maven metadata now
lists Compose UI 1.13.0 stable (1.14.0-alpha01 newest) and BOM 2026.11.01. Whether a newer version maps the label to
hintText or labeledBy is **not verified**. If proposed, it's a separate compatible-set change (with Kotlin 2.3, AGP and
API 36), measured by re-running this same probe; never a single version bump.
