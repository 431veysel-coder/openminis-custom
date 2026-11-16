#!/usr/bin/env bash
#
# Bring OpenMinis/OpenMinis changes into this fork.
#
# Two repositories, one APK: upstream ships the app, this fork ships identity +
# features + a release pipeline. The rule that keeps that possible is FORK.md
# §1 principle 2 — stay mergesable — and the way it breaks is silently: a merge
# deferred long enough stops being a merge and becomes a rewrite.
#
# What this does:
#   1. fetch upstream main
#   2. if nothing is new, stop (cheap, so it can run unattended)
#   3. merge into a sync branch (never into your working branch)
#   4. re-apply the identity patch (applicationId, app_name, RELEASE_REPO)
#   5. run the identity guard — a merge that breaks identity must not build
#
# It does NOT resolve content conflicts. A conflict in upstream's own code is a
# decision about which side wins, and that decision is not a script's to make:
# the branch is left in the conflicted state, named, so a human can pick.
#
# Usage:
#   scripts/sync_upstream.sh              # merge into sync/upstream-YYYY-MM-DD
#   scripts/sync_upstream.sh --dry-run    # report only, touch nothing
#
set -uo pipefail

UPSTREAM_URL="https://github.com/OpenMinis/OpenMinis.git"
UPSTREAM_REMOTE="upstream"
BASE_BRANCH="${BASE_BRANCH:-main}"
DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

say() { printf '\033[1m%s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*"; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

[ -d .git ] || die "run from the repository root"
git rev-parse --verify "$BASE_BRANCH" >/dev/null 2>&1 || die "no branch '$BASE_BRANCH'"

# ── 1. upstream ────────────────────────────────────────────────────────────────
if ! git remote | grep -qx "$UPSTREAM_REMOTE"; then
  say "adding remote $UPSTREAM_REMOTE"
  [ "$DRY_RUN" -eq 1 ] || git remote add "$UPSTREAM_REMOTE" "$UPSTREAM_URL"
fi

say "fetching $UPSTREAM_REMOTE/main"
git fetch --quiet "$UPSTREAM_REMOTE" main || die "fetch failed (network or the remote moved)"
UPSTREAM_SHA=$(git rev-parse --short "$UPSTREAM_REMOTE/main")

BEHIND=$(git rev-list --count "$BASE_BRANCH..$UPSTREAM_REMOTE/main")
AHEAD=$(git rev-list --count "$UPSTREAM_REMOTE/main..$BASE_BRANCH")
echo "  upstream $UPSTREAM_SHA — $BEHIND new commit(s) to take, $AHEAD of ours it does not have"

if [ "$BEHIND" -eq 0 ]; then
  say "nothing to do: $BASE_BRANCH already contains $UPSTREAM_REMOTE/main"
  exit 0
fi

if [ "$DRY_RUN" -eq 1 ]; then
  say "would merge these into a sync branch:"
  git --no-pager log --oneline "$BASE_BRANCH..$UPSTREAM_REMOTE/main" | head -30
  exit 0
fi

# ── 2. sync branch ────────────────────────────────────────────────────────────
BRANCH="sync/upstream-$(date +%Y-%m-%d)"
START_BRANCH=$(git rev-parse --abbrev-ref HEAD)
CLEAN=$(git status --porcelain | wc -l | tr -d ' ')
[ "$CLEAN" = "0" ] || die "working tree is dirty — commit or stash first"

say "checking out $BRANCH from $BASE_BRANCH"
git checkout -B "$BRANCH" "$BASE_BRANCH" || die "checkout failed"

# ── 3. merge ──────────────────────────────────────────────────────────────────
say "merging $UPSTREAM_REMOTE/main"
set +e
git merge --no-ff "$UPSTREAM_REMOTE/main" -m "merge: upstream $UPSTREAM_SHA into $BASE_BRANCH" >/tmp/sync-merge.log 2>&1
MERGE=$?
set -e

if [ "$MERGE" -ne 0 ]; then
  CONFLICTS=$(git diff --name-only --diff-filter=U)
  COUNT=$(printf '%s\n' "$CONFLICTS" | sed '/^$/d' | wc -l | tr -d ' ')
  warn ""
  warn "merge stopped with $COUNT conflicting file(s) — this is a decision, not a bug:"
  printf '  %s\n' $CONFLICTS
  warn ""
  warn "Both sides changed these. Resolve them on '$BRANCH' (it is checked out),"
  warn "then: git add -A && git commit, and finally:"
  warn "  python3 scripts/apply_identity.py && bash scripts/check_identity.sh"
  exit 3
fi

# ── 4. identity ───────────────────────────────────────────────────────────────
say "re-applying the fork identity (upstream's values win any file it touched)"
python3 scripts/apply_identity.py
IDENTITY=$?
# 2 means "a human must choose the version number" — the merge is still good.
[ "$IDENTITY" -eq 0 ] || [ "$IDENTITY" -eq 2 ] || die "identity patch failed ($IDENTITY)"

# ── 5. guard ──────────────────────────────────────────────────────────────────
say "identity guard"
if ! bash scripts/check_identity.sh; then
  warn ""
  warn "The merge broke the identity guard. Do NOT push this branch as-is."
  exit 4
fi

# ── 6. finish ─────────────────────────────────────────────────────────────────
if ! git diff --cached --quiet || ! git diff --quiet; then
  git add -A
  git commit -q -m "chore(sync): re-apply identity after upstream $UPSTREAM_SHA"
fi

say ""
say "done — '$BRANCH' has $UPSTREAM_SHA merged and the identity re-applied."
say "Review it, then:  git checkout $BASE_BRANCH && git merge --ff-only $BRANCH"
say "(back to $START_BRANCH was your starting branch)"
