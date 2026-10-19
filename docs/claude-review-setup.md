# Claude review setup

Status: the maintainer connected `CLAUDE_CODE_OAUTH_TOKEN` and installed the official App workflows in PR #5. Actual App reviews completed on the discovery/setup PRs. This revision consolidates PR triggers into `.github/workflows/claude-code-review.yml` and keeps `.github/workflows/claude.yml` for issue assistance only. This is separate from credential-free Android CI.

## One manual connection

In `Thiesi/ChitChat-Mobile`, open **Settings > Secrets and variables > Actions > New repository secret**. Add exactly one of:

- `CLAUDE_CODE_OAUTH_TOKEN`: a token you generate locally with `claude setup-token` using an eligible Claude Code subscription; recommended if that is your existing subscription model.
- `ANTHROPIC_API_KEY`: an Anthropic Console API key with billing/quota enabled; API usage is billed separately from a subscription.

Do not paste credentials in chat, PRs, source, workflow files or ordinary repository variables. Do not configure both secrets. Rotate/revoke them through the relevant provider if compromised. The workflow never prints them; do not enable Actions step debugging or full Claude output on this public repository. Configure usage/budget alerts with the provider; the 20-minute timeout and 30-turn cap are not a monetary spending cap.

For PR reviews, no Firebase credential, personal GitHub access token or installed Claude GitHub App is necessary. That workflow uses the expiring `GITHUB_TOKEN` (code/issues read and PR write for comments, no code write); results appear under **`github-actions[bot]`**. The narrower PR-read/issues-write combination was actually tested and rejected by both GraphQL and REST PR-comment publication, so PR write is deliberate. This avoids the App's workflow-change validation skip without merging an unreviewed bootstrap PR. The separate issue-assistance workflow retained from #5 **does require** the installed Claude App and `id-token: write`; it can use the same one configured Anthropic API or OAuth credential and appears as `claude[bot]`.

If repository/organization policy disallows Actions comment writes or this third-party action, an administrator must permit the pinned action and the declared PR/issue write permissions. Do not broaden code permissions or use a PAT to bypass policy.

## Verify the connection

1. Configure the credential if setting up a new repository, then run **Claude Code Review / claude-review** on a PR. Its `pull_request` trigger tests proposed workflow changes before merge. Existing PRs need a new revision or a later `@claude` request after it is on the default branch.
2. Confirm preflight, Claude execution, structured-result validation and trusted comment publication succeeded. Inspect the posted comment: it must identify the current head SHA and provide findings or explicitly say no worthwhile fixable findings remain. The model cannot post comments directly; a trusted step validates its JSON/head and publishes it with a run/attempt/head audit marker. A green or skipped check alone is never evidence of a useful/current review.
3. After the workflow reaches the default branch, comment `@claude Please review the current revision again, including prior fixes.` on an open PR. **New PR conversation comments**, not edited comments or inline review replies, are supported. The comment must be made by a repository writer; comments from a workflow's `GITHUB_TOKEN` do not trigger another workflow.
4. Ensure the second run posts a fresh result for the current head. Never infer approval from silence, cancellation, quota exhaustion or a missing/skipped job.

The `issue_comment` trigger only becomes available when this workflow is on the default branch. During bootstrap, push a new revision to trigger another review instead of merging to unlock comments. No merge-before-review exception has been granted.

## Scope and review loop

Automatic reviews run for new/reopened/updated, non-draft, same-repository PRs. Authorized `@claude` comments request another complete review, even if one already exists. The action independently verifies writer permissions. Forks, unknown contributors, bot triggers, closed PRs and drafts do not receive secrets or paid review; a maintainer must inspect and safely bring external work into a trusted branch before using this workflow. Never add `pull_request_target` with an untrusted head checkout or wildcard bot/non-writer permissions.

The model may use only **two exact commands**: `gh pr diff NUMBER --repo OWNER/REPO` and `gh pr view NUMBER --repo OWNER/REPO --comments`, substituted for this PR. No wildcard command/flag permission is granted: a non-secret probe confirmed `gh --jq env` can read the process environment, so prefix-only `gh pr view:*` is unsafe here. Filesystem tools and all MCP tools (`mcp__*`) are denied. A runtime audit checks the actual SDK initialization tool list (Bash and internal structured/terminal tools only) without publishing the raw execution log. A trusted subsequent step validates the structured output/head, caps its size, neutralizes mentions/HTML/backticks and puts model text in a code block under a trusted fixed verdict, then posts through the REST issue-comment API. Tool permissions are not a complete sandbox; treat source/model output/PR-controlled workflows as untrusted. No signing keys, real account credentials or backend secrets belong in this job.

Concurrency is job-scoped, after the trigger/actor eligibility condition, and does not cancel an active review. Unrelated/public/bot comments cannot cancel a review through a shared workflow-level concurrency group. Bot-authored events (including Dependabot) are excluded instead of running a credential preflight that cannot receive secrets. Such skipped/ineligible events do not satisfy the standing merge gate: inspect an actual completed current-head comment before merging, and bring external work into a trusted branch for automated review. If an eligible PR becomes closed/draft/otherwise ineligible, scope validation fails rather than silently succeeding.

Intentional trigger semantics: PR conversation `@claude` means **review only**, even if the text asks for implementation; inline PR mentions are not supported. Use the lead coding agent or a separately tagged issue for implementation assistance. If a bot pushes a fix, a repository writer must request the fresh review with a new PR conversation comment. We do not grant arbitrary bot triggers access to paid credentials. Repeated authorized requests can produce more than one review of a head; each run/attempt has its own marker, and only completed current-head results count.

Standing milestone order: build/test, commit/push, open PR, wait for review completion, triage credible product impact, fix worthwhile findings, request another review tagging `@claude`, and repeat. Merge only once worthwhile findings are resolved, the review covers the current head, required checks pass and conflicts are absent. Then clean only verified obsolete owned branches/worktrees. CI/reviewer success never substitutes for actual test results or human/agent triage.

Actions are pinned to immutable revisions. Update pins deliberately against upstream release/security notes, reviewing upstream changes and rerunning a setup PR; do not replace them with floating branches. The action itself installs Claude Code and its runtime, so pinning its revision does not make the entire hosted AI runtime reproducible. No review has been claimed complete merely by preparing this configuration.

## Official references

- [Claude Code GitHub Actions](https://code.claude.com/docs/en/github-actions)
- [Action inputs and examples](https://github.com/anthropics/claude-code-action)
- [Action security and permissions](https://github.com/anthropics/claude-code-action/blob/main/docs/security.md)
- [GitHub token override and App bootstrap validation](https://github.com/anthropics/claude-code-action/blob/5eb090f3c3afd701a0672cd32981197ca27688f6/src/github/token.ts)
