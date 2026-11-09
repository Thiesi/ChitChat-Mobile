# Completion journey IME cleanup

This is a test-only correction to `CompletionJourneyTest` cleanup, approved by the
technical lead after the original line167 failures and the owner's temporary
[development allowance](https://github.com/Thiesi/ChitChat-Mobile/issues/1#issuecomment-6737201774).
It changes no production UI, input routing, focus, keyboard action or feature availability.

## Contract and correction

The contract is **hidden at cleanup completion**. Compose IME bottom must be zero
and both sequential native IME probes must report hidden. These observations are
sequential, not an atomic snapshot. A later re-show from the test's own earlier tap
is out of scope; this is not a guarantee that the keyboard can never reappear.

Previously cleanup waited for Compose zero and native hidden, then asserted a
second native-hidden observation outside the wait. A transition between those
probes could fail that assertion without allowing the original wait to finish its
cleanup proof. Both native observations now belong to the same condition inside
the existing single 5000ms wait. Unknown or visible native state cannot complete it.
The initial idle and the existing single conditional `Espresso.closeSoftKeyboard()`
are unchanged. There is no added hide, tap, focus request, input retry, reset deadline
or retry of a failed suite. The strict input assertions elsewhere are unchanged.

## Negative-first evidence

Before correcting the helper, an extraction-only check verified that the original
journey's final wait/assert line was replaced by a helper retaining exactly those
two operations. Four deterministic JUnit cases then ran against that old behavior:
four executed, two failed, specifically
`aVisibleConfirmationKeepsWaitingWithinTheOriginalDeadline` and
`composeAndBothNativeObservationsMustAllBeHidden` (expected false, actual true).
The two remaining cases passed. The native sequence false/true/false/false exposes
the confirmation gap without any Activity, keyboard action or timing experiment.

After the minimal correction the same four cases passed. They require one wait
with timeout 5000, reject unknown/visible native state, preserve both native probes
and show that no later unbounded probe is part of the completion contract.
Both focused runs used Kotlin 2.3.21/JDK 17 with two processors, no Gradle, emulator,
live session or full suite. Source, compiled JAR, logs and dependency hashes were
archived with byte/CRC verification; their fixed scratch folders were removed.
Negative archive SHA256:
`9cebc65074848afd7ce5114ea3207dab38d5edb9457b71b4815c101a0118fe03`.
Positive archive SHA256:
`0670880df800b21a50d370125a3bb018fc1cc9baff3100a20603447214f75254`.

These scalar cases also join the ordinary unfiltered Android suite. Focused passes
do not replace current-head hosted JVM, lint, builds and both native suites or the
two written reviews; their original results must be recorded on the delivery PR.
No physical-device, live, accessibility, release or production-keyboard claim follows.

The Completion cleanup allowance, including its API36/product-guard extension,
ends when this correction merges. Original failures stay failed. The separate
pinned-API35 ReplyComposer allowance is unaffected.
