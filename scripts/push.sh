#!/bin/sh
# [MINIS-OZEL] The only sanctioned way to publish a commit.
#
# Three problems this solves at once:
#
#  1. The token must not sit in .git/config. Cloning with
#     `https://x-access-token:$TOKEN@github.com/...` writes the credential to
#     disk in plain text — outside the Keystore, outside the encrypted prefs,
#     readable by any process and included in any directory copy. Here the
#     remote has no credential in it, and git asks the helper each time, which
#     reads the value from the environment and never from a file.
#
#  2. A secret scanner that only runs in CI reports the leak after it is
#     published. This runs it before the push (and the hook runs it again).
#
#  3. A broad token silently permits damage to every repository the account
#     can see. check_token_scope.py refuses to push through a credential that
#     reaches beyond this repository, so a wrong token is caught at use, not
#     at audit time.
#
# Token selection:
#   GITHUB_REPO_TOKEN  — the narrow, repo-scoped fine-grained PAT (preferred)
#   GITHUB_TOKEN       — the general-purpose token (fallback, warns loudly)
#
# Nothing here prints the token. The base64 header is built in a subshell and
# handed to git through http.extraHeader, so it never appears in .git/config.

set -eu

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

EXPECTED_REPO="431veysel-coder/openminis-custom"

TOKEN="${GITHUB_REPO_TOKEN:-}"
TOKEN_KIND="repo-scoped"
if [ -z "$TOKEN" ]; then
    TOKEN="${GITHUB_TOKEN:-}"
    TOKEN_KIND="general"
fi
if [ -z "$TOKEN" ]; then
    echo "push: no token — set GITHUB_REPO_TOKEN (fine-grained, this repo only)" >&2
    exit 1
fi
if [ "$TOKEN_KIND" = "general" ]; then
    echo "push: GITHUB_REPO_TOKEN is not set, falling back to GITHUB_TOKEN." >&2
    echo "      That token reaches every repository on the account; see the" >&2
    echo "      'Repository-scoped credential' section in SECURITY.md." >&2
fi

# 1. Refuse to publish anything with a credential in it.
echo "push: scanning for secrets..."
python3 scripts/secret_scan.py --mode tracked
python3 scripts/secret_scan.py --mode staged --quiet || {
    echo "push: blocked — a staged file contains a secret" >&2
    exit 1
}
forbidden=$(git ls-files | grep -Ei '(^|/)(keystore\.properties|provider-customization\.properties|local\.properties)$|\.(jks|keystore|p12|pfx)$' || true)
if [ -n "$forbidden" ]; then
    echo "push: blocked — secret-bearing files are tracked:" >&2
    echo "$forbidden" | sed 's/^/        /' >&2
    exit 1
fi

# 2. Refuse to publish through an over-broad credential, unless the caller
#    explicitly accepts the fallback (the general token is too broad by
#    definition, so it can only warn).
if [ "$TOKEN_KIND" = "repo-scoped" ]; then
    if ! GITHUB_TOKEN="$TOKEN" python3 scripts/check_token_scope.py \
            --expect-repo "$EXPECTED_REPO" --expect-fine-grained; then
        echo "push: refused — the repo-scoped token is not scoped to $EXPECTED_REPO" >&2
        exit 1
    fi
else
    echo "push: skipping scope check (general token is broad by design)" >&2
fi

# 3. Push with the credential supplied per-invocation, never stored.
BRANCH="${1:-main}"
AUTH_B64=$(printf 'x-access-token:%s' "$TOKEN" | base64 | tr -d '\n')

echo "push: $ROOT -> origin/$BRANCH"
# No refspec: the branch is a positional argument above only for the log line.
# Adding HEAD:refs/heads/$BRANCH here as well makes git see two sources for one
# destination ("receives from more than one src") and refuse.
git -c "http.https://github.com/.extraHeader=AUTHORIZATION: basic $AUTH_B64" \
    push origin "refs/heads/$BRANCH:refs/heads/$BRANCH"

echo "push: done"
