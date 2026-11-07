#!/usr/bin/env python3
"""Read-only local scratch guard for ChitChat Mobile agents.

Counts files in this host's agent scratch locations and exits 1 when any bucket exceeds
the limit, so a full gate or emulator run can refuse to start on a host that is filling up.
It never deletes anything. See docs/local-disk-hygiene.md.

Usage (from any checkout or worktree of this repository):
  python -I tools/scratch_guard.py            # guard: exit 1 above the limit
  python -I tools/scratch_guard.py --report   # full counts for every bucket
  python -I tools/scratch_guard.py --limit 50000
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
from pathlib import Path

DEFAULT_LIMIT = 50_000
# Shared, deliberately persistent tool installs: never counted as scratch.
TOOLING_KEEP = {"android-sdk", "android-user-home", "java17", "worktrees"}
# Shared caches: guarded as their own bucket, but not part of the per-run scratch total.
TOOLING_CACHES = {"gradle-user-home"}
# Ignored build output inside a checkout or worktree.
BUILD_DIR_NAMES = {"build", ".gradle", ".kotlin", "captures"}
TEMP_PREFIXES = ("ccm-", "chitchat")


def count_files(root: Path, stop_after: int | None) -> int:
    """Count regular files under root without following links; stop early if asked."""
    total = 0
    stack = [root]
    while stack:
        current = stack.pop()
        try:
            with os.scandir(current) as entries:
                for entry in entries:
                    try:
                        if entry.is_dir(follow_symlinks=False):
                            stack.append(Path(entry.path))
                        else:
                            total += 1
                    except OSError:
                        continue
                    if stop_after is not None and total > stop_after:
                        return total
        except OSError:
            continue
    return total


def build_dirs(checkout: Path) -> list[Path]:
    """Ignored build-output directories in a checkout, skipping .git and nested worktrees."""
    found: list[Path] = []
    stack = [checkout]
    while stack:
        current = stack.pop()
        try:
            with os.scandir(current) as entries:
                for entry in entries:
                    if not entry.is_dir(follow_symlinks=False):
                        continue
                    if entry.name in {".git", ".tooling", "node_modules"}:
                        continue
                    if entry.name in BUILD_DIR_NAMES:
                        found.append(Path(entry.path))
                    else:
                        stack.append(Path(entry.path))
        except OSError:
            continue
    return found


def main_checkout() -> Path:
    common = subprocess.run(
        ["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    return Path(common).parent


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--report", action="store_true", help="count everything and print all buckets")
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT)
    args = parser.parse_args()
    stop = None if args.report else args.limit

    root = main_checkout()
    buckets: list[tuple[str, Path | list[Path]]] = []
    tooling = root / ".tooling"
    if tooling.is_dir():
        for child in sorted(tooling.iterdir()):
            if child.is_dir() and child.name not in TOOLING_KEEP:
                buckets.append((f".tooling/{child.name}", child))
    checkouts = [root]
    worktrees = tooling / "worktrees"
    if worktrees.is_dir():
        checkouts += sorted(p for p in worktrees.iterdir() if p.is_dir())
    for checkout in checkouts:
        label = "main checkout" if checkout == root else f"worktree {checkout.name}"
        buckets.append((f"{label} build output", build_dirs(checkout)))
    temp = Path(tempfile.gettempdir())
    temp_entries = [p for p in temp.iterdir() if p.name.lower().startswith(TEMP_PREFIXES)]
    buckets.append((f"{temp} {'/'.join(TEMP_PREFIXES)}* entries", temp_entries))

    # Small per-run folders add up: the whole .tooling scratch area is also one bucket.
    over = False
    tooling_total = 0
    rows: list[tuple[int, str]] = []
    for label, target in buckets:
        paths = target if isinstance(target, list) else [target]
        n = 0
        for path in paths:
            n += count_files(path, None if stop is None else max(stop - n, 0)) if path.is_dir() else 1
        if label.startswith(".tooling/") and label[len(".tooling/"):] not in TOOLING_CACHES:
            tooling_total += n
        rows.append((n, label))
        over |= n > args.limit
    rows.append((tooling_total, ".tooling scratch total (excluding shared SDK, JDK, Gradle home, user home, worktrees)"))
    over |= tooling_total > args.limit

    for n, label in sorted(rows, reverse=True):
        if args.report or n > args.limit:
            print(f"{n:>10}  {label}")
    if over:
        print(f"scratch_guard: a bucket exceeds {args.limit} files; clean up before starting a gate "
              "(docs/local-disk-hygiene.md).", file=sys.stderr)
        return 1
    if not args.report:
        print(f"scratch_guard: ok (limit {args.limit} files per bucket)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
