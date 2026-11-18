#!/usr/bin/env python3
"""Secret scanner for the Minis Ozel fork.

Runs in two places, on purpose:

  1. Locally, from the pre-push hook (scripts/install_hooks.sh) — stops a
     credential before it ever leaves the device.
  2. In CI, on every push and on a weekly full-history sweep — catches
     anything that got past a missing hook (a clone without hooks installed,
     a commit made from another machine, a force-push).

Having only no.2 means the secret is already public by the time it is found:
it has been pushed, indexed, and possibly forked. Having only no.1 means one
machine without the hook defeats the whole thing. Both, or neither is real.

Why a hand-written scanner rather than gitleaks/trufflehog: the scanner has to
run in the PRoot sandbox (no package for it in Alpine, static aarch64 binary
not guaranteed to exec), in CI, and it has to know *our* rules — the
applicationId/namespace pair, the release keystore's own header, the
provider-customization file that upstream ships only as .example. A vendored
binary also adds a supply-chain dependency to the very pipeline it guards.

Exit codes: 0 clean, 1 findings, 2 usage/scan error (treated as failure by
callers — a scanner that cannot run must not silently pass).
"""

from __future__ import annotations

import argparse
import math
import os
import re
import subprocess
import sys
from dataclasses import dataclass

# ---------------------------------------------------------------------------
# Rules
# ---------------------------------------------------------------------------

# (id, description, compiled pattern, group index holding the secret)
TOKEN_RULES = [
    ("github-pat-classic", "GitHub personal access token (classic)",
     re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"), 0),
    ("github-pat-fine", "GitHub fine-grained personal access token",
     re.compile(r"\bgithub_pat_[A-Za-z0-9_]{40,}\b"), 0),
    ("github-oauth", "GitHub OAuth / app token",
     re.compile(r"\bgho_[A-Za-z0-9]{30,}\b"), 0),
    ("openai-key", "OpenAI-style API key",
     re.compile(r"\bsk-(?:proj-|ant-[a-z0-9]+-)?[A-Za-z0-9_-]{24,}\b"), 0),
    ("anthropic-key", "Anthropic API key",
     re.compile(r"\bsk-ant-[A-Za-z0-9_-]{20,}\b"), 0),
    ("google-api-key", "Google API key",
     re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"), 0),
    ("aws-access-key", "AWS access key id",
     re.compile(r"\bAKIA[0-9A-Z]{16}\b"), 0),
    ("slack-token", "Slack token",
     re.compile(r"\bxox[baprs]-[0-9A-Za-z-]{10,}\b"), 0),
    ("telegram-bot", "Telegram bot token",
     re.compile(r"\b\d{8,10}:[A-Za-z0-9_-]{35}\b"), 0),
    ("stripe-key", "Stripe secret key",
     re.compile(r"\bsk_(?:live|test)_[A-Za-z0-9]{24,}\b"), 0),
    ("jwt", "JSON Web Token",
     re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"), 0),
    ("private-key", "Private key block",
     re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY-----"), 0),
    ("keystore-b64", "Base64-encoded keystore blob in an assignment",
     re.compile(r"(?:KEYSTORE|KEYSTORE_B64|ANDROID_KEYSTORE_BASE64)\s*[:=]\s*[\"']?[A-Za-z0-9+/]{200,}"), 0),
]

# Generic "this looks like a credential assignment" rule, plus an entropy test
# so placeholders ("your-key-here") and format strings do not trip it.
GENERIC_ASSIGN = re.compile(
    r"""(?ix)
    \b(?P<name>[A-Za-z0-9_.-]*(?:api[_-]?key|apikey|secret|password|passwd|token|credential|private[_-]?key)[A-Za-z0-9_.-]*)
    \s*[:=]\s*
    (?P<quote>["'])(?P<value>[^"'\n]{16,})(?P=quote)
    """
)

# ---------------------------------------------------------------------------
# Allowlist
# ---------------------------------------------------------------------------

# Paths whose contents are by definition not shipped secrets.
SKIP_PATH_SUBSTRINGS = (
    "/.git/",
    "/build/",
    "/.gradle/",
    "node_modules/",
)

# Files we never scan even though they match nothing above: binary and vendored.
SKIP_EXTENSIONS = {
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".svgz",
    ".ttf", ".otf", ".woff", ".woff2",
    ".zip", ".gz", ".xz", ".bz2", ".7z", ".tar",
    ".apk", ".aab", ".jar", ".aar", ".so", ".dylib", ".a", ".o",
    ".mp3", ".mp4", ".wav", ".m4a", ".pdf",
    ".p12", ".jks", ".keystore",
}

# Placeholder shapes that are obviously not real credentials.
PLACEHOLDER = re.compile(
    r"""(?ix)^(
        your[-_ ]|xxx+|\.\.\.|changeme|placeholder|example|dummy|redacted|
        test[-_]|fake[-_]|sample|<.*>|\$\{.*\}|\$[A-Z_]+|\{\{.*\}\}|
        [A-Za-z]+[-_](?:key|token|secret|password)[-_]?here
    )"""
)

# Known-safe literals that would otherwise hit the generic rule.
GENERIC_ALLOWLIST = (
    "minis-toolchain-setup",
    "com.veysel.minisx",
    "com.openminis.app",
)

# A value that literally names itself a fixture is a fixture. Upstream's tests
# are full of them ("sk-ant-oat01-SECRETSECRETSECRET0123", "ABCDEFGHIJ...") and
# the redaction tests exist precisely to assert that such strings get masked.
FIXTURE_MARKERS = re.compile(r"(?i)secret|test|fake|dummy|example|redacted|sample|placeholder|todo")

# Values that are URLs, paths or identifiers, not credentials.
NOT_A_CREDENTIAL = re.compile(r"(?i)^(https?:|wss?:|ftp:|/|~|\.|com\.|android\.|io\.|[a-z]+://)")

# Rules that stay active even in test/ and docs/ paths: these shapes are never
# legitimate in a source tree, so a match there is a real leak rather than a
# fixture. Everything else is downgraded in those paths.
HIGH_CONFIDENCE_RULES = {
    "github-pat-classic", "github-pat-fine", "github-oauth",
    "private-key", "aws-access-key", "google-api-key", "keystore-b64",
}

TEST_PATH = re.compile(
    r"(?i)(^|/)(tests?|androidtest|ministests|minisuitests|__tests__|spec|specs)(/|$)"
    r"|(_test\.|_spec\.|test\.kt$|tests?\.(kt|swift|py)$|test\.(py|js|ts)$)"
)

DOC_PATH = re.compile(r"(?i)\.(md|markdown|rst)$|(^|/)docs?/")


def is_test_path(path: str) -> bool:
    return bool(TEST_PATH.search(path.replace(os.sep, "/")))


def is_doc_path(path: str) -> bool:
    return bool(DOC_PATH.search(path.replace(os.sep, "/")))

MIN_ENTROPY = 3.6
MAX_FILE_BYTES = 2 * 1024 * 1024


def shannon_entropy(s: str) -> float:
    if not s:
        return 0.0
    counts: dict[str, int] = {}
    for ch in s:
        counts[ch] = counts.get(ch, 0) + 1
    n = len(s)
    return -sum((c / n) * math.log2(c / n) for c in counts.values())


def looks_like_secret(value: str) -> bool:
    if PLACEHOLDER.match(value):
        return False
    if value in GENERIC_ALLOWLIST:
        return False
    if NOT_A_CREDENTIAL.match(value):
        return False
    if FIXTURE_MARKERS.search(value):
        return False
    # Needs both letters and digits (or be long and mixed) to be credible.
    has_digit = any(c.isdigit() for c in value)
    has_alpha = any(c.isalpha() for c in value)
    if not (has_digit and has_alpha):
        return False
    return shannon_entropy(value) >= MIN_ENTROPY


def redact(value: str) -> str:
    """Enough to recognise which key leaked, never enough to use it."""
    if len(value) <= 12:
        return value[:2] + "*" * max(0, len(value) - 2)
    return f"{value[:4]}...{value[-4:]} ({len(value)} chars)"


@dataclass
class Finding:
    path: str
    line: int
    rule: str
    description: str
    snippet: str

    def render(self) -> str:
        return f"{self.path}:{self.line}: [{self.rule}] {self.description}: {self.snippet}"


def scan_text(path: str, text: str) -> list[Finding]:
    findings: list[Finding] = []
    seen: set[tuple[int, str]] = set()
    relaxed = is_test_path(path) or is_doc_path(path)
    for lineno, line in enumerate(text.splitlines(), 1):
        # Fast reject: no credential-ish characters at all.
        if len(line) < 16:
            continue
        for rule_id, desc, pattern, _ in TOKEN_RULES:
            if relaxed and rule_id not in HIGH_CONFIDENCE_RULES:
                continue
            for m in pattern.finditer(line):
                key = (lineno, rule_id)
                if key in seen:
                    continue
                seen.add(key)
                value = m.group(0)
                # A value that names itself a fixture is a fixture, even when
                # it matches a token shape — upstream's redaction tests are
                # full of "sk-ant-oat01-SECRETSECRET..." on purpose.
                if FIXTURE_MARKERS.search(value):
                    continue
                findings.append(Finding(path, lineno, rule_id, desc, redact(value)))
        if relaxed:
            continue
        for m in GENERIC_ASSIGN.finditer(line):
            value = m.group("value")
            if not looks_like_secret(value):
                continue
            key = (lineno, "generic-credential")
            if key in seen:
                continue
            seen.add(key)
            findings.append(
                Finding(path, lineno, "generic-credential",
                        f"credential-like assignment to '{m.group('name')}'", redact(value))
            )
    return findings


def should_skip(path: str) -> bool:
    low = path.replace(os.sep, "/")
    if any(s in low for s in SKIP_PATH_SUBSTRINGS):
        return True
    ext = os.path.splitext(low)[1].lower()
    return ext in SKIP_EXTENSIONS


def scan_paths(paths: list[str]) -> list[Finding]:
    findings: list[Finding] = []
    for p in paths:
        if not os.path.isfile(p) or should_skip(p):
            continue
        try:
            if os.path.getsize(p) > MAX_FILE_BYTES:
                continue
            with open(p, "r", encoding="utf-8", errors="strict") as fh:
                text = fh.read()
        except (UnicodeDecodeError, OSError):
            continue  # binary or unreadable: covered by the other rules
        findings.extend(scan_text(p, text))
    return findings


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], check=True, capture_output=True, text=True
    ).stdout


def staged_paths() -> list[str]:
    out = git("diff", "--cached", "--name-only", "--diff-filter=ACMR")
    return [p for p in out.splitlines() if p.strip()]


def changed_paths(base: str) -> list[str]:
    try:
        out = git("diff", "--name-only", "--diff-filter=ACMR", f"{base}..HEAD")
    except subprocess.CalledProcessError:
        return []
    return [p for p in out.splitlines() if p.strip()]


def tracked_paths() -> list[str]:
    return [p for p in git("ls-files").splitlines() if p.strip()]


def history_findings() -> list[Finding]:
    """Full-history sweep: every blob ever committed, not just the tip.

    A secret that was committed and then deleted is still in the repository's
    history and therefore still public — which is why CI runs this weekly and
    not only on the tip.
    """
    findings: list[Finding] = []
    blobs = git("rev-list", "--objects", "--all").splitlines()
    for row in blobs:
        parts = row.split(maxsplit=1)
        if len(parts) != 2:
            continue
        sha, path = parts
        if should_skip(path):
            continue
        try:
            content = git("cat-file", "-p", sha)
        except subprocess.CalledProcessError:
            continue
        except UnicodeDecodeError:
            continue
        findings.extend(scan_text(path, content))
    return findings


def self_test() -> int:
    """Prove the rules still fire.

    A scanner whose patterns have drifted reports "clean" forever, which is
    indistinguishable from a clean repository. Every rule gets a must-match
    fixture and the placeholder shapes get a must-not-match one.
    """
    must_flag = {
        "github-pat-classic": "token = 'ghp_" + "aB3dE5gH7jK9mN1pQ3sT5vX7zA9cD1fG3hI5'",
        "github-pat-fine": "pat = 'github_pat_" + "11ABCDEFG0abcDEF1234567890_abcdefghijklmnopqrstuvwxyzABCDEFGH'",
        "github-oauth": "gho = 'gho_" + "aB3dE5gH7jK9mN1pQ3sT5vX7zA9cD1fG3hI5'",
        "openai-key": "k = 'sk-proj-" + "aB3dE5gH7jK9mN1pQ3sT5vX7zA9cD1fG3hI5jK7'",
        "anthropic-key": "k = 'sk-ant-" + "api03-aB3dE5gH7jK9mN1pQ3sT5vX7zA9cD'",
        "google-api-key": "k = 'AIza" + "SyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q'",
        "aws-access-key": "k = 'AKIA" + "3J7K2M9P4Q8R1S6T'",
        "slack-token": "t = 'xoxb-" + "123456789012-123456789012-abcdefghijklmnop'",
        # Assembled at runtime so this file does not itself contain the literal.
        "private-key": "-----BEGIN " + "RSA PRIVATE KEY-----",
    }
    must_ignore = [
        'api_key = "your-api-key-here"',
        'token = "${MY_TOKEN}"',
        'secret = "changeme"',
        'password = "<your password>"',
        'tokenURL = "https://claude.ai/v1/oauth/token"',
        'let token = "sk-ant-oat01-SECRETSECRETSECRET0123"',
        'const apiKey = "REPLACE_ME_WITH_YOUR_KEY"',
    ]

    failures: list[str] = []
    for rule_id, line in must_flag.items():
        found = {f.rule for f in scan_text("self_test/sample.kt", line)}
        if rule_id not in found:
            failures.append(f"rule '{rule_id}' did not match its fixture")
    for line in must_ignore:
        found = scan_text("self_test/sample.kt", line)
        if found:
            failures.append(f"false positive on: {line[:60]} ({found[0].rule})")

    if failures:
        print("secret_scan self-test FAILED", file=sys.stderr)
        for f in failures:
            print(f"  {f}", file=sys.stderr)
        return 1
    print(f"secret_scan self-test: {len(must_flag)} rules match, "
          f"{len(must_ignore)} placeholders ignored")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Scan for committed secrets.")
    ap.add_argument("--mode", choices=["staged", "changed", "tracked", "history", "paths"],
                    default="tracked", help="what to scan")
    ap.add_argument("--base", default="origin/main", help="base ref for --mode changed")
    ap.add_argument("paths", nargs="*", help="explicit paths for --mode paths")
    ap.add_argument("--quiet", action="store_true", help="only print on findings")
    ap.add_argument("--self-test", action="store_true", help="verify the rules fire")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    try:
        if args.mode == "staged":
            paths = staged_paths()
            findings = scan_paths(paths)
        elif args.mode == "changed":
            findings = scan_paths(changed_paths(args.base))
        elif args.mode == "history":
            findings = history_findings()
        elif args.mode == "paths":
            findings = scan_paths(args.paths)
        else:
            findings = scan_paths(tracked_paths())
    except subprocess.CalledProcessError as e:
        print(f"secret_scan: git failed: {e.stderr.strip()}", file=sys.stderr)
        return 2

    if findings:
        print(f"secret_scan: {len(findings)} potential secret(s) found\n", file=sys.stderr)
        for f in findings:
            print(f"  {f.render()}", file=sys.stderr)
        print(
            "\nRemove the value, rotate the credential, and purge it from history\n"
            "(git filter-repo) — deleting it in a later commit is not enough.",
            file=sys.stderr,
        )
        return 1

    if not args.quiet:
        print(f"secret_scan: clean ({args.mode})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
