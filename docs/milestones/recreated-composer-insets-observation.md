# Native test-host theme hygiene and retained IME observations

Status: the bounded investigation is stopped under
[issue69/6728241370](https://github.com/Thiesi/ChitChat-Mobile/issues/69#issuecomment-6728241370).
PR133's final scope is theme/manifest-guard hygiene; no keyboard fix is claimed.

## Final hygiene scope

The Compose library explicitly assigns its ComponentActivity a platform Light theme,
which overrides the application's Theme.ChitChat fallback. The debug manifest now
explicitly uses Theme.ChitChat and adjustResize, matching MainActivity's declared
theme and input mode before attachment. A device-free manifest guard catches drift.
Day/night resources and production Activity/input code are unchanged.

The experimental production-helper extraction, debug Activity, lifecycle timing case,
failure observer and opt-in20-case runner/collector are absent from the final diff.
Their commits and measured source are retained in this PR's history. The original
ReplyComposer journey remains enabled and byte-equivalent to main, with all original
native actions, positive-Compose-inset predicates and5000ms deadlines intact.

## Original results, retained without retry

- The20-cycle diagnostic42522293944 at05c985f failed20/20 on API35. Current attached/
  focused roots reported native IME259 versus direct/mirrored Compose0. The original
  collector's terminal-marker accounting failed; its later correction exists only in
  archived experiment source, not this hygiene delivery. Report69/6727081243.
- Timing-only0fb6f51/run42525249420: API35 failed247/249 (both timing cases),
  API36 passed249/249. Startup timing alone was insufficient.
- Theme-onlyab76e8f/run42527845665: API35 failed247/249 (both cases), API36 failed
 248/249 with a separately audited known strict-input symptom. Report133/6727876673.
- Pre-theme/current comparison42530146699:19/20 in each arm; the same original
  ReplyComposer failure predates Lantern B. Report69/6728081784.
- Real-MainActivity42531290955/0294007:1/1 passed. After DM recreation and one native
  composer tap, input and Send ended at377 above native IME top381; cleanup passed.
  This covers only that synthetic API35 path, not all room-to-DM Reply/modal paths or
  API26-34. Report69/6728186345.
- Final alignedb43071b/run42533817034: API35 failed247/249 (both cases), API36
  passed249/249;1116JVM/build/lint and two reviews passed. Post-failure window/decor640,
  content/current focused Compose root357, native rootIME259/holder0; listener
  prepared/runningfalse and saved-dispatch snapshot cleared. The content size is
  consistent with640 minus259 IME and24 status-bar space. These separately timestamped
  post-assertion samples prove no earlier deadline state or exact framework cause.
  [Full retained report](https://github.com/Thiesi/ChitChat-Mobile/pull/133#issuecomment-6728672641).

All original reports/source/APK proofs remain retained; no failed result is rewritten.
No additional experiment, diagnostic dispatch or alignment iteration is authorized.
The remaining mechanism is lower priority; a new plan is needed before further work.
The [maintainer's scoped interim rule](https://github.com/Thiesi/ChitChat-Mobile/issues/1#issuecomment-6727888625)
continues because this investigation delivered no fixture fix. The reduced head needs
its own applicable checks and two written current-head reviews; earlier results do not
transfer, per the [final disposition](https://github.com/Thiesi/ChitChat-Mobile/issues/69#issuecomment-6728684050).
Device, live, accessibility and release acceptance remain separate.
