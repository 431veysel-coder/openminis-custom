#!/usr/bin/env python3
"""Security auditor — the failures we have actually shipped, made unmatchable.

[MINIS-OZEL 11.3.1] Every rule below exists because this fork (or upstream)
already got it wrong once. A rule that only guards a hypothetical is not here;
the point is that a specific mistake cannot happen a second time, not that the
tree scores well on a generic linter.

    python3 scripts/audit/security_audit.py [--update-baseline] [--json PATH]

Exit code 1 if any NEW error-severity finding appears or if the baseline is
stale. Old findings carried in baseline.json do not fail the build.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import (  # noqa: E402
    ERROR, INFO, WARN, Finding, finish, load_baseline, read, rel, repo_root,
    suppressed, walk, write_baseline, KOTLIN_EXT,
)

NAME = "security"

MANIFEST = "src/android/app/src/main/AndroidManifest.xml"
NSCONFIG = "src/android/app/src/main/res/xml/network_security_config.xml"

# ── secret shapes ────────────────────────────────────────────────────────────
# Deliberately narrow. A broad "long random string" rule fires on hashes,
# resource names and test fixtures until someone mutes it, and a muted rule is
# worse than no rule. These are the shapes of credentials we actually use.
SECRET_PATTERNS: list[tuple[str, re.Pattern, str]] = [
    ("SEC001", re.compile(r"\bsk-[A-Za-z0-9_\-]{20,}"), "OpenAI/Anthropic-style API key"),
    ("SEC001", re.compile(r"\bsk-ant-[A-Za-z0-9_\-]{20,}"), "Anthropic API key"),
    ("SEC001", re.compile(r"\bAIza[0-9A-Za-z_\-]{30,}"), "Google API key"),
    ("SEC001", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}"), "GitHub token"),
    ("SEC001", re.compile(r"\bgithub_pat_[A-Za-z0-9_]{40,}"), "GitHub fine-grained token"),
    ("SEC001", re.compile(r"\bxox[baprs]-[A-Za-z0-9\-]{10,}"), "Slack token"),
    ("SEC001", re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "AWS access key id"),
    ("SEC001", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"), "private key block"),
    ("SEC001", re.compile(r"\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"),
     "JWT with a body — usually a live session token"),
]

# Strings that look like the above but are placeholders or documentation.
SECRET_ALLOW = re.compile(
    r"(?i)(example|placeholder|your[-_]?(?:api[-_]?)?key|redacted|dummy|fixture|"
    r"xxxx+|\*\*\*\*|\.\.\.|<[a-z_]+>|test[-_]?key|sample|sk-test|replace_me|"
    r"secretsecret|changeme)"
)

# Paths where a credential-shaped string is the point, not a leak.
SECRET_PATH_ALLOW = re.compile(
    r"(?i)(^|/)(test|androidTest|tests?)/|secret_scan\.py$|audit/(baseline|"
    r"self.?test)|\.md$"
)

# ── insecure storage / crypto ────────────────────────────────────────────────
# SEC002: a credential written through a PLAIN preference file.
#
# The first version of this rule fired on every `prefs.edit().putString(...)`
# in the tree — 28 hits, all of them ordinary state (alarm lists, device id,
# the last-used provider). That is exactly the "noisy rule gets muted" failure
# the module docstring warns about. Narrowed to what actually matters: the
# *key name* says credential, or the *value expression* is a variable named
# like one. Encrypted-prefs call sites are exempt by name.
# A string literal that names a credential.
KEY_LITERAL = r"""["'][A-Za-z0-9_\-\.]*(?:api[_-]?key|apikey|auth[_-]?token|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|credential)[A-Za-z0-9_\-\.]*["']"""
# A value expression that IS a credential variable.
KEY_VALUE = r"""(?:apiKey|api_key|authToken|accessToken|refreshToken|clientSecret|password|bearer)"""
SEC002_PATTERNS = [
    # prefs.edit().putString("some.apiKey", ...)   /  putString("password", v)
    re.compile(rf"putString\s*\(\s*{KEY_LITERAL}", re.I),
    # prefs.edit().putString(KEY, apiKey)
    re.compile(rf"putString\s*\(\s*[^,)]+,\s*(?:this\.)?{KEY_VALUE}\b", re.I),
    # putString("token", token)
    re.compile(rf"putString\s*\(\s*[\"'][^\"']*token[^\"']*[\"']\s*,", re.I),
]
# A file whose NAME says it is the secure store is exempt: that is the one place
# a key is allowed to touch disk, and it is already encrypted there.
SECURE_STORE_HINTS = re.compile(r"(SecurePrefs|EncryptedPrefs|CryptoStore|EnvVarPrivacyStore|"
                                r"VaultCrypto|Keystore|KeystoreStore)", re.I)

SEC003 = re.compile(r"""(?i)\b(?:Cipher\.getInstance|Mac\.getInstance|MessageDigest\.getInstance)\s*\(\s*["'](?:AES|DES|DESede|RC4|RC2|Blowfish)(?:/ECB[^"']*|/CBC/PKCS5Padding|/NONE[^"']*)?["']""")
SEC003_SAFE = re.compile(r"(?i)(?:/GCM/|/CTR/|/CBC/PKCS7Padding)")
SEC004 = re.compile(r"(?i)\b(?:MessageDigest\.getInstance\s*\(\s*[\"'])(?:MD5|SHA-?1)(?:[\"'])")

# ── manifest / network ──────────────────────────────────────────────────────
EXPORTED_RE = re.compile(r'android:exported="true"')
PERMISSION_RE = re.compile(r'android:permission="([^"]+)"')
COMPONENT_START = re.compile(r"<(activity|activity-alias|service|receiver|provider)\b")
COMPONENT_END = re.compile(r"/>|</(?:activity|activity-alias|service|receiver|provider)>")


def _line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def scan_secrets(root: Path) -> list[Finding]:
    out: list[Finding] = []
    include = KOTLIN_EXT | {".py", ".json", ".properties", ".yaml", ".yml", ".sh", ".md", ".xml", ".ts", ".txt"}
    for path in walk(root, include):
        relp = rel(path, root)
        if SECRET_PATH_ALLOW.search(relp):
            continue
        text = read(path)
        lines = text.splitlines()
        for rule, pattern, label in SECRET_PATTERNS:
            for m in pattern.finditer(text):
                line_no = _line_of(text, m.start())
                if suppressed(lines, line_no - 1, rule):
                    continue
                snippet = m.group(0)
                context = lines[line_no - 1] if 0 <= line_no - 1 < len(lines) else ""
                if SECRET_ALLOW.search(context):
                    continue
                # A public key in the update-signature guard is meant to be public.
                if "PUBLIC KEY" in context or "publicKey" in context:
                    continue
                out.append(Finding(
                    rule=rule,
                    severity=ERROR,
                    file=rel(path, root),
                    line=line_no,
                    message=f"possible {label} committed in source",
                    excerpt=snippet[:40] + ("…" if len(snippet) > 40 else ""),
                ))
    return out


def scan_insecure_storage(root: Path) -> list[Finding]:
    out: list[Finding] = []
    for path in walk(root, KOTLIN_EXT, subdir="src/android/app/src/main"):
        text = read(path)
        if not SECURE_STORE_HINTS.search(str(path)) and not SECURE_STORE_HINTS.search(text):
            pass  # the exemption is about the *call site*, checked per match below
        lines = text.splitlines()
        # A file that obtains its preferences from the encrypted factory is
        # already doing the right thing; the call site just looks the same.
        file_is_secure = bool(SECURE_STORE_HINTS.search(text)) or "EncryptedPrefsFactory" in text
        for pattern in SEC002_PATTERNS:
            for m in pattern.finditer(text):
                line_no = _line_of(text, m.start())
                line = lines[line_no - 1] if 0 <= line_no - 1 < len(lines) else ""
                if suppressed(lines, line_no - 1, "SEC002"):
                    continue
                # Encrypted-prefs call sites are the sanctioned path.
                if file_is_secure or SECURE_STORE_HINTS.search(str(path)) or SECURE_STORE_HINTS.search(line):
                    continue
                if "encrypted" in line.lower():
                    continue
                out.append(Finding(
                    rule="SEC002",
                    severity=WARN,
                    file=rel(path, root),
                    line=line_no,
                    message="credential-shaped key written via plain preferences",
                    excerpt=line.strip(),
                ))
    return out


def scan_crypto(root: Path) -> list[Finding]:
    out: list[Finding] = []
    for path in walk(root, KOTLIN_EXT, subdir="src/android/app/src/main"):
        text = read(path)
        lines = text.splitlines()
        for m in SEC003.finditer(text):
            line_no = _line_of(text, m.start())
            snippet = m.group(0)
            if SEC003_SAFE.search(snippet):
                continue
            if suppressed(lines, line_no - 1, "SEC003"):
                continue
            out.append(Finding(
                rule="SEC003",
                severity=ERROR,
                file=rel(path, root),
                line=line_no,
                message="weak cipher mode (ECB / no IV); use AES/GCM",
                excerpt=snippet,
            ))
        for m in SEC004.finditer(text):
            line_no = _line_of(text, m.start())
            if suppressed(lines, line_no - 1, "SEC004"):
                continue
            out.append(Finding(
                rule="SEC004",
                severity=ERROR,
                file=rel(path, root),
                line=line_no,
                message="MD5/SHA-1 used as a security primitive",
                excerpt=m.group(0),
            ))
    return out


def _components(manifest: str) -> list[tuple[int, str, str]]:
    """(start_index, kind, block) for every top-level component declaration."""
    out: list[tuple[int, str, str]] = []
    for m in COMPONENT_START.finditer(manifest):
        start = m.start()
        end = manifest.find(">", start)
        if end == -1:
            continue
        # Walk to the end of the tag *including* nested intent-filters: a
        # self-closing tag ends at the first '>', a paired one at its close.
        line_start = start
        head = manifest[start:end + 1]
        if head.rstrip().endswith("/>"):
            block = head
        else:
            close = re.compile(rf"</{m.group(1)}>")
            cm = close.search(manifest, end)
            block = manifest[line_start: cm.end() if cm else len(manifest)]
        out.append((start, m.group(1), block))
    return out


def scan_manifest(root: Path) -> list[Finding]:
    out: list[Finding] = []
    path = root / MANIFEST
    if not path.exists():
        return [Finding("SEC010", ERROR, MANIFEST, 0, "manifest missing")]

    text = read(path)
    lines = text.splitlines()

    # SEC010: exported with no permission — the component is reachable by any
    # app on the device. Legitimate for launchers, share targets and deep
    # links; not legitimate for anything that touches the sandbox or a key.
    for start, kind, block in _components(text):
        if not EXPORTED_RE.search(block):
            continue
        line_no = _line_of(text, start)
        if suppressed(lines, line_no - 1, "SEC010"):
            continue
        # A component protected by a signature permission is not reachable by
        # untrusted apps, so it is not this finding.
        if PERMISSION_RE.search(block):
            continue
        danger = re.search(
            r"(?i)(Sandbox|Terminal|DebugServer|Provider|Key|Vault|Shell|FileProvider|"
            r"A11y|Accessibility|Command)", block,
        )
        severity = ERROR if danger else WARN
        what = danger.group(1) if danger else kind
        out.append(Finding(
            rule="SEC010",
            severity=severity,
            file=MANIFEST,
            line=line_no,
            message=f"exported {kind} ({what}) with no android:permission",
            excerpt=f"<{kind}> {danger.group(0) if danger else ''}".strip(),
        ))

    # SEC011: a token-bearing host must refuse cleartext. The network config
    # has a strict list; a new provider host that receives a bearer token but
    # is missing from it would send the key in plaintext on an http:// typo.
    nsc = root / NSCONFIG
    if nsc.exists():
        ns_text = read(nsc)
        strict = set(re.findall(r"<domain[^>]*>([^<]+)</domain>",
                                ns_text.split('cleartextTrafficPermitted="false"')[-1]
                                if 'cleartextTrafficPermitted="false"' in ns_text else ""))
        token_hosts = _token_hosts(root)
        ns_lines = ns_text.splitlines()
        for host in sorted(token_hosts):
            if any(host == s or host.endswith("." + s) for s in strict):
                continue
            if suppressed(ns_lines, 0, "SEC011"):
                continue
            out.append(Finding(
                rule="SEC011",
                severity=ERROR,
                file=NSCONFIG,
                line=0,
                message=f"host '{host}' receives a bearer token but cleartext is not refused for it",
                excerpt=host,
            ))

    return out


def _token_hosts(root: Path) -> set[str]:
    """Hosts the provider presets point at — the ones a key is sent to."""
    hosts: set[str] = set()
    for path in walk(root, KOTLIN_EXT, subdir="src/android/app/src/main"):
        text = read(path)
        if "https://" not in text:
            continue
        if not re.search(r"(?i)(baseUrl|BASE_URL|endpoint|apiHost|api_base)", text):
            continue
        for m in re.finditer(r"https://([a-z0-9\.\-]+\.[a-z]{2,})", text, re.I):
            hosts.add(m.group(1).lower())
    # Only well-known vendor domains are policed here: a user-supplied base URL
    # cannot be enforced from a static file, and pretending otherwise produces
    # a rule that fires on every custom endpoint.
    known = {
        "api.openai.com", "api.anthropic.com", "generativelanguage.googleapis.com",
        "openrouter.ai", "api.deepseek.com", "api.x.ai", "api.together.xyz",
        "api.fireworks.ai", "router.huggingface.co", "ai-gateway.vercel.sh",
        "api.mistral.ai", "api.groq.com", "api.moonshot.cn", "api.moonshot.ai",
        "api.cerebras.ai", "api.sambanova.ai", "api.nvidia.com",
    }
    return {h for h in hosts if h in known}


def scan_entitlements(root: Path) -> list[Finding]:
    """SEC012: a manifest permission nobody uses is a permission to be abused.

    Emitted as INFO — the count is useful in a report but the dependency
    analysis is too weak to fail a build on.
    """
    out: list[Finding] = []
    path = root / MANIFEST
    if not path.exists():
        return out
    text = read(path)
    perms = set(re.findall(r'<uses-permission[^>]*android:name="([^"]+)"', text))
    if not perms:
        return out
    src = "\n".join(read(p) for p in walk(root, KOTLIN_EXT, subdir="src/android/app/src/main"))
    for perm in sorted(perms):
        leaf = perm.rsplit(".", 1)[-1]
        if leaf in src or perm in src:
            continue
        out.append(Finding(
            rule="SEC012",
            severity=INFO,
            file=MANIFEST,
            line=0,
            message=f"permission declared but never referenced in Kotlin: {perm}",
            excerpt=perm,
        ))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--update-baseline", action="store_true")
    ap.add_argument("--json")
    args = ap.parse_args()

    root = repo_root()
    audit_dir = Path(__file__).resolve().parent

    findings: list[Finding] = []
    findings += scan_secrets(root)
    findings += scan_insecure_storage(root)
    findings += scan_crypto(root)
    findings += scan_manifest(root)
    findings += scan_entitlements(root)

    if args.update_baseline:
        path = write_baseline(audit_dir, findings, NAME)
        print(f"[{NAME}] baseline written: {path} ({len(findings)} finding(s))")
        return 0

    known = load_baseline(audit_dir, NAME)
    rc = finish(NAME, findings, known)

    if args.json:
        import json
        Path(args.json).write_text(
            json.dumps([f.as_dict() for f in findings], indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
    return rc


if __name__ == "__main__":
    sys.exit(main())
