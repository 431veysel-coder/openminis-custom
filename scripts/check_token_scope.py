#!/usr/bin/env python3
"""[MINIS-OZEL] Report how far the git credential actually reaches.

Why this exists: the build token is handed to the agent's shell as an
environment variable, so it is readable by any process the agent runs. The only
limit on the damage a leaked or injected token can do is *what the token is
allowed to touch*. A classic PAT with `repo` + `workflow` reaches every
repository the account can see, plus settings — that is the whole account. A
fine-grained PAT limited to one repository can push a bad commit to that one
repository and nothing else.

This is a guard, not a scanner: it answers "is the credential in this
environment still the narrow one", and prints a loud line if it is not.

Usage:
    python3 scripts/check_token_scope.py            # uses $GITHUB_TOKEN
    python3 scripts/check_token_scope.py --expect-repo owner/name
    python3 scripts/check_token_scope.py --expect-fine-grained

Exit codes: 0 within the expected scope, 1 too broad, 2 cannot determine.
NEVER prints the token itself — only its shape, scopes and reach.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def call(path: str, token: str) -> tuple[int, dict, dict]:
    req = urllib.request.Request(
        API + path,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "minis-token-scope-check",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read().decode()), dict(r.headers)
    except urllib.error.HTTPError as e:
        body = {}
        try:
            body = json.loads(e.read().decode())
        except Exception:
            pass
        return e.code, body, dict(e.headers)
    except (urllib.error.URLError, TimeoutError) as e:
        print(f"token_scope: network error: {e}", file=sys.stderr)
        raise SystemExit(2)


def token_shape(token: str) -> str:
    """Classify without revealing: prefix, length, and rough alphabet only."""
    if token.startswith("github_pat_"):
        return f"fine-grained (github_pat_…, {len(token)} chars)"
    if token.startswith("ghp_"):
        return f"classic PAT (ghp_…, {len(token)} chars)"
    if token.startswith("gho_"):
        return f"OAuth (gho_…, {len(token)} chars)"
    if token.startswith("ghs_"):
        return f"app installation (ghs_…, {len(token)} chars)"
    return f"unknown prefix (len={len(token)})"


def main() -> int:
    ap = argparse.ArgumentParser(description="Check the reach of the git credential.")
    ap.add_argument("--expect-repo", help="owner/name this token should be limited to")
    ap.add_argument("--expect-fine-grained", action="store_true",
                    help="fail unless the token is a fine-grained PAT")
    ap.add_argument("--max-repos", type=int, default=1,
                    help="maximum repositories the token may reach (default 1)")
    args = ap.parse_args()

    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN") or ""
    if not token:
        print("token_scope: no GITHUB_TOKEN / GH_TOKEN in the environment", file=sys.stderr)
        return 2

    print(f"token shape : {token_shape(token)}")
    problems: list[str] = []

    status, user, _ = call("/user", token)
    if status != 200:
        print(f"token_scope: /user returned {status}: {user.get('message', '')}", file=sys.stderr)
        return 2
    print(f"account     : {user.get('login')} ({user.get('type')})")

    # Classic PATs report their scopes in a header. Fine-grained ones do not
    # report scopes at all, which is itself the signal.
    _, _, headers = call("/user", token)
    scopes = headers.get("X-OAuth-Scopes", "").strip()
    if scopes:
        scope_list = [s.strip() for s in scopes.split(",") if s.strip()]
        print(f"scopes      : {', '.join(scope_list)}")
        dangerous = [s for s in scope_list
                     if s in {"repo", "admin:org", "delete_repo", "workflow",
                              "write:packages", "delete:packages", "admin:public_key",
                              "admin:ssh_signing_key", "user", "gist"}]
        if dangerous:
            problems.append(
                "classic scopes grant account-wide reach: " + ", ".join(dangerous)
            )
    else:
        print("scopes      : (none reported — fine-grained or app token)")

    if args.expect_fine_grained and not token.startswith("github_pat_"):
        problems.append("token is not a fine-grained PAT")

    # How many repositories can it see? For a repo-scoped token this is 1.
    status, repos, _ = call("/user/repos?per_page=100&affiliation=owner,collaborator,organization_member", token)
    if status == 200 and isinstance(repos, list):
        names = [r["full_name"] for r in repos if r.get("permissions", {}).get("push")]
        print(f"push access : {len(names)} repository(ies)")
        for n in names[:12]:
            print(f"              {n}")
        if len(names) > 12:
            print(f"              … and {len(names) - 12} more")
        if len(names) > args.max_repos:
            problems.append(
                f"token can push to {len(names)} repositories (limit {args.max_repos})"
            )
        if args.expect_repo:
            extra = [n for n in names if n != args.expect_repo]
            if extra:
                problems.append(
                    f"token reaches beyond {args.expect_repo}: " + ", ".join(extra[:5])
                )
            if args.expect_repo not in names:
                problems.append(f"token cannot push to {args.expect_repo} — useless here")
    else:
        # Fine-grained tokens scoped to one repo usually cannot list /user/repos.
        print("push access : not enumerable (expected for a repo-scoped token)")
        if status in (403, 404):
            ok_status, ok_repo, _ = call(f"/repos/{args.expect_repo}" if args.expect_repo else "/user", token)
            if args.expect_repo and ok_status == 200:
                print(f"              but {args.expect_repo} is reachable — good")

    print()
    if problems:
        print("token_scope: TOO BROAD", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        print("\nReplace it with a fine-grained PAT limited to this repository:\n"
              "  Settings → Developer settings → Fine-grained tokens → Generate new token\n"
              "  Repository access: Only select repositories → openminis-custom\n"
              "  Permissions: Contents R/W, Actions R/W, Workflows R/W, Metadata read\n"
              "  Expiry: 90 days (rotate instead of renewing forever)",
              file=sys.stderr)
        return 1

    print("token_scope: within the expected scope")
    return 0


if __name__ == "__main__":
    sys.exit(main())
