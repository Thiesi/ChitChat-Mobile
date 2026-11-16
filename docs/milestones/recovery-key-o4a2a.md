# Recovery key O4a2a: the key display

The registration key display and its place in the root. It follows [recovery-key.md](../design/recovery-key.md)
(P-184, P-185) and the presentation lead's O4a2 decisions. It builds on [O4a1](recovery-key-o4a1.md). O4a2b adds
Privacy and security (the row, the explanation screen, Replace or Create) and the "didn't reach this phone" notice.

## Precedence

While `SessionState.registrationRecoveryKey` is set and the session is signed in, the root shows the key display
instead of everything below it:
1. the server-update screen;
2. the key display;
3. "Before you chat" (the guidelines);
4. the shell, or Find your people for a new account.

A restored back stack or a pending push tap can't get past it: the tap waits in `PendingPushTap` until the shell is
composed. The key lives in memory only (O4a1), so a process death drops it and the display with it.

## The display

- **Window:** FLAG_SECURE is set only while the display is composed, so the key stays out of screenshots, screen
  recordings and the recents thumbnail; it's cleared when the display leaves. The root view is excluded from
  autofill (`IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`), restored afterwards.
- **Key card:** the six groups in the system monospace face, two rows of three that wrap at large text. TalkBack reads
  each group separately ("Group 1 of 6: A, 7, K, 2").
- **Copy:** puts the key in the clipboard. On API 33 and later the clip carries `android.content.extra.IS_SENSITIVE`
  (no preview, kept out of keyboard history); below 33 a line under Copy says the keyboard may keep a copy. The
  clipboard is never cleared later. "Copied. Paste it into your password manager." is announced politely.
- **Save to a file:** an explicit document choice (`text/plain`, suggested name "ChitChat recovery key - {host}.txt")
  with the unencrypted line shown beforehand. The file holds the host, username, key, creation date and the warning
  line. No URI grant is kept and the file is never deleted later. A failure shows "The file wasn't saved."
- **Continue** is in flow at the end, reachable by scrolling at 200%. Continue and Back both open "Saved your
  recovery key?". Go back keeps the display; I've saved it drops the key from memory (`dropRegistrationRecoveryKey`,
  generation-fenced). Nothing records whether it was saved.

## Tests

- `RecoveryKeyJourneyTest` (the real root with the guidelines gate on):
  - the display comes before the guidelines and the shell, in a secure window;
  - Continue and Back ask first, and I've saved it drops the key and clears FLAG_SECURE;
  - Copy writes the key, marked sensitive on API 33 and later;
  - a push tap waits behind the display and the guidelines;
  - a recreated activity still shows the display first;
  - the clipboard-history line shows only below API 33.
- `RecoveryKeyPresentationTest` captures the display (top and end), the API 32 line and the dialog, light and dark,
  at a real Configuration font scale of 1 and 2, into `recovery-presentation`. FLAG_SECURE would blank a capture, so
  these captures compose without an activity; the journey test asserts the flag on the real window.
