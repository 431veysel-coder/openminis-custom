#!/bin/sh
# [MINIS-OZEL] Install the repository's git hooks.
#
# The hooks live in .githooks/ (tracked, so they are versioned and reviewable)
# rather than .git/hooks/ (untracked, so every clone silently has none).
# Run once per clone:  sh scripts/install_hooks.sh
#
# Also sets core.hooksPath, which is what makes the tracked directory take
# effect. A pre-push hook that is not installed is not a guard, which is why
# CI runs the same scanner independently.

set -eu

root=$(git rev-parse --show-toplevel)
cd "$root"

if [ ! -d .githooks ]; then
    echo "error: .githooks/ not found — run this from the repository root" >&2
    exit 1
fi

chmod +x .githooks/* 2>/dev/null || true
git config core.hooksPath .githooks

echo "hooks installed (core.hooksPath=.githooks)"
echo "  pre-push:    refuses to push a commit that contains a credential"
echo "  pre-commit:  runs scripts/preflight.sh on the staged change set"
echo
echo "verify with:  git config --get core.hooksPath"
echo "bypass with:  git push --no-verify   (publishes the secret; rotate after)"
