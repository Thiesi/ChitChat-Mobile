# Local disk hygiene for agents

Several agents (a coordinator, Codex, the presentation lead) work on this repository in
parallel worktrees on one Windows host. Another project on this host had a multi-day
outage when parallel agents left scratch behind: a new folder per run and per head
(test temp folders, captures, packages, clones). It grew to millions of files and took the
host down. Moving the scratch to `%TEMP%` only moved the problem, and recovery needed an
offline disk check. These rules adapt that project's safeguards to Android builds,
emulators and instrumentation evidence.

## Rules

1. **Fixed scratch folders, reused.** Each kind of local run writes to one fixed folder that
   every run reuses and overwrites, for example `.tooling/scratch/connected`,
   `.tooling/scratch/journeys` or `.tooling/scratch/lint`. Never create a new folder per run,
   per attempt or per head (`…-final-67-pass`, `…-fixed-3`). Empty the folder when the run
   ends, including anything downloaded to verify.
2. **Evidence worth keeping is committed or attached.** Results that a milestone record cites
   go into `docs/` (small, text) or into the PR or CI run as an artifact. Never cite a local
   scratch path as the place where evidence is kept; cleanup will delete it.
3. **`%TEMP%` is not storage.** Temporary entries this project creates in `%TEMP%` use the
   prefix `ccm-`, live in one fixed folder per kind, and are removed when the run ends.
4. **Gradle.** Build output stays in each checkout's ignored `build/`, `.gradle/` and
   `.kotlin/` folders. Use the shared user Gradle home; never a per-run `GRADLE_USER_HOME`.
   Stop the daemons you started (`gradlew --stop` from your own worktree) before going idle.
   Removing a merged worktree removes its build output.
5. **Emulators and devices.** Keep the one shared `ANDROID_USER_HOME` (existing rule). Do not
   copy AVDs per run, and do not save emulator snapshots per run. Connected and full native
   gates run serially on this host: one at a time, never two agents in parallel. Launch
   owned emulators from the stable repository root (existing rule) and stop only emulators
   and processes you started.
6. **One worktree per active branch.** Remove it with the existing Windows cleanup procedure
   in `AGENTS.md` when its PR merges or closes. Clean your own scratch before going idle.
   Never work in a checkout or worktree another session may be using.
7. **Guard before heavy runs.** Run `python -I tools/scratch_guard.py` before a full gate,
   a connected-test run or an emulator journey. It is read-only and exits 1 when any scratch
   bucket (each `.tooling/<folder>`, the `.tooling` scratch total, each checkout's build
   output, or this project's `%TEMP%` entries) holds more than 50,000 files. Clean up first;
   do not raise the limit to get past it. `--report` lists every bucket.
8. **No repositories or scratch under OneDrive.** This checkout lives at a plain local path.
   Keep it, its worktrees and its temp folders out of synced folders.
9. **Git safety.** No force pushes (including `--force-with-lease`) and no direct pushes to
   `main`; reconcile by merging. The project `.claude/settings.json` denies these for Claude
   sessions. Other agents follow the rule by instruction.

## Existing scratch

The guard deliberately does not delete. Folders already under `.tooling/` were created by
earlier slices, and some are cited by milestone records. Before anyone removes them, the
owner of that work checks whether a record cites a local path, moves anything worth keeping
into `docs/` or a PR artifact, and then removes the rest with the existing archive-first
Windows cleanup rules.
