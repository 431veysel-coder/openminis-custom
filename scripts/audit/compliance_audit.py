#!/usr/bin/env python3
"""Compliance auditor — identity, packaging and release hygiene.

[MINIS-OZEL 11.3.1] These rules guard the things that are invisible in a build
log and expensive in the field: the app announces itself as "Minis", a locale
file breaks the resource compiler twenty minutes into a build, the version
counter goes backwards and a device can never update again.

    python3 scripts/audit/compliance_audit.py [--update-baseline] [--json PATH]
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

NAME = "compliance"

RES = "src/android/app/src/main/res"
STRINGS = f"{RES}/values/strings.xml"
GRADLE = "src/android/app/build.gradle.kts"
MAIN_JAVA = "src/android/app/src/main/java"

APP_LABEL = "Minis X"
APP_ID = "com.veysel.minisx"
NAMESPACE = "com.openminis.app"
RELEASE_REPO = "431veysel-coder/openminis-custom"

# versionCode numbers that are permanently spent. A number must never describe
# two different binaries: an install carrying a burned number can only ever
# update *upward*, so reusing one strands a device forever.
BURNED = set(range(37009, 37014)) | set(range(37017, 37028))
MIN_VERSION_CODE = 37014  # first published fork build


def _string_body_has_apostrophe(line: str) -> bool:
    """A raw ' inside a <string> body breaks aapt2's flatten step.

    Android requires \\' in string resources. The failure is a build error with
    a line number inside a 2,000-line translated file and no other clue, which
    is exactly what is worth catching before the 10-minute build starts.
    """
    if "<string" not in line:
        return False
    body = line.split(">", 1)[1] if ">" in line else ""
    body = body.rsplit("</string>", 1)[0]
    stripped = body.replace("\\'", "")   # already escaped → fine
    return "'" in stripped


def scan_locales(root: Path) -> list[Finding]:
    out: list[Finding] = []
    res = root / RES
    if not res.exists():
        return out

    for path in walk(root, {".xml"}, subdir=RES):
        if path.name != "strings.xml":
            continue
        text = read(path)
        lines = text.splitlines()

        # CMP001: the name the user sees, in every locale.
        for m in re.finditer(r'<string name="app_name"[^>]*>([^<]*)</string>', text):
            value = m.group(1).strip()
            if value == APP_LABEL:
                continue
            line_no = text.count("\n", 0, m.start()) + 1
            if suppressed(lines, line_no - 1, "CMP001"):
                continue
            out.append(Finding(
                rule="CMP001", severity=ERROR, file=rel(path, root), line=line_no,
                message=f'app_name is "{value}" — the fork must always show "{APP_LABEL}"',
                excerpt=f"app_name={value}",
            ))

        # CMP002: unescaped apostrophes.
        for i, line in enumerate(lines):
            if not _string_body_has_apostrophe(line):
                continue
            if suppressed(lines, i, "CMP002"):
                continue
            out.append(Finding(
                rule="CMP002", severity=ERROR, file=rel(path, root), line=i + 1,
                message="unescaped apostrophe in a string resource — aapt2 flatten "
                        "fails; write \\' instead",
                excerpt=line.strip()[:160],
            ))
    return out


def _string_keys_in(dir_path: Path) -> set[str]:
    """All <string name> keys in every XML under one values directory.

    The resources are split across 28 files per locale (strings.xml,
    strings_network.xml, …), so reading only strings.xml reports hundreds of
    perfectly defined keys as missing — the first version of this auditor did
    exactly that.
    """
    keys: set[str] = set()
    if not dir_path.exists():
        return keys
    for path in dir_path.glob("*.xml"):
        keys |= set(re.findall(r'<string name="([^"]+)"', read(path)))
    return keys


def scan_string_keys(root: Path) -> list[Finding]:
    """CMP003: an R.string key used in code but not defined.

    The compiler catches this too — but only after the native layer and a full
    build. Here it costs a second, on a device, before the push.
    """
    out: list[Finding] = []
    defined = _string_keys_in(root / RES / "values")
    if not defined:
        return out

    used: dict[str, tuple[str, int]] = {}
    for path in walk(root, KOTLIN_EXT, subdir=MAIN_JAVA):
        text = read(path)
        for m in re.finditer(r"(?<!android\.)\bR\.string\.([A-Za-z0-9_]+)", text):
            key = m.group(1)
            if key not in used:
                used[key] = (rel(path, root), text.count("\n", 0, m.start()) + 1)
    for key, (file, line_no) in sorted(used.items()):
        if key in defined:
            continue
        if key.startswith(("abc_", "m3c_", "androidx_", "preference_")):
            continue  # library-provided
        out.append(Finding(
            rule="CMP003", severity=ERROR, file=file, line=line_no,
            message=f"R.string.{key} is used but not defined in any values/*.xml",
            excerpt=f"R.string.{key}",
        ))
    return out


def scan_translation_parity(root: Path) -> list[Finding]:
    """CMP004: keys missing from a translation, reported once per locale.

    Not an error — the app falls back to the default string, which is correct.
    The count is the signal: a locale that quietly stopped being maintained
    looks identical to a healthy one without it.
    """
    out: list[Finding] = []
    defined = _string_keys_in(root / RES / "values")
    if not defined:
        return out
    res = root / RES
    for loc_dir in sorted(res.glob("values-*")):
        if not loc_dir.is_dir():
            continue
        have = _string_keys_in(loc_dir)
        if not have:
            continue
        missing = defined - have
        if not missing:
            continue
        own_missing = {k for k in missing if k.startswith(("ozel_", "minis_ozel"))}
        if not own_missing:
            continue  # upstream keys are untranslated everywhere; not our signal
        label = loc_dir.name
        out.append(Finding(
            rule="CMP004", severity=WARN, file=rel(loc_dir, root), line=0,
            message=f"{label}: {len(own_missing)} fork-owned key(s) untranslated "
                    f"({len(missing)} total missing)",
            excerpt=f"{label}:{len(own_missing)}",
        ))
    return out


def scan_version(root: Path) -> list[Finding]:
    out: list[Finding] = []
    path = root / GRADLE
    if not path.exists():
        return [Finding("CMP005", ERROR, GRADLE, 0, "build.gradle.kts missing")]
    text = read(path)
    lines = text.splitlines()

    m_code = re.search(r"\bversionCode\s*=\s*(\d+)", text)
    m_name = re.search(r'\bversionName\s*=\s*"([^"]+)"', text)
    if not m_code:
        return [Finding("CMP005", ERROR, GRADLE, 0, "no versionCode found")]

    code = int(m_code.group(1))
    line_code = text.count("\n", 0, m_code.start()) + 1

    if code in BURNED:
        out.append(Finding(
            "CMP005", ERROR, GRADLE, line_code,
            f"versionCode {code} is permanently burned — an install carrying it can "
            f"never update again; pick a higher number",
            excerpt=f"versionCode = {code}",
        ))
    if code <= MIN_VERSION_CODE:
        out.append(Finding(
            "CMP005", ERROR, GRADLE, line_code,
            f"versionCode {code} is not above {MIN_VERSION_CODE}; the counter is "
            f"monotonic and never decreases",
            excerpt=f"versionCode = {code}",
        ))

    if m_name and "-ozel" not in m_name.group(1):
        out.append(Finding(
            "CMP006", ERROR, GRADLE, text.count("\n", 0, m_name.start()) + 1,
            'versionName must carry the "-ozel" marker',
            excerpt=m_name.group(0),
        ))

    code_hits = [i for i, l in enumerate(lines) if re.search(r"\bversionCode\s*=", l)]
    if len(code_hits) > 1:
        out.append(Finding(
            "CMP007", WARN, GRADLE, code_hits[1] + 1,
            "versionCode is assigned more than once in this file",
            excerpt=lines[code_hits[1]].strip(),
        ))
    return out


def scan_identity(root: Path) -> list[Finding]:
    out: list[Finding] = []
    path = root / GRADLE
    if not path.exists():
        return out
    text = read(path)
    lines = text.splitlines()

    checks = [
        (r'applicationId\s*=\s*"([^"]*)"', APP_ID, "CMP008",
         f'applicationId must stay "{APP_ID}"'),
        (r'\bnamespace\s*=\s*"([^"]*)"', NAMESPACE, "CMP009",
         f'namespace must stay "{NAMESPACE}" — activity-alias class names depend on it'),
    ]
    for pattern, want, rule, msg in checks:
        m = re.search(pattern, text)
        if not m:
            out.append(Finding(rule, ERROR, GRADLE, 0, msg + " (not found)"))
            continue
        if m.group(1) == want:
            continue
        line_no = text.count("\n", 0, m.start()) + 1
        if suppressed(lines, line_no - 1, rule):
            continue
        out.append(Finding(rule, ERROR, GRADLE, line_no, msg, excerpt=m.group(0)))

    found_repo = RELEASE_REPO in text or any(
        RELEASE_REPO in read(p) for p in walk(root, KOTLIN_EXT, subdir=MAIN_JAVA)
    )
    if not found_repo:
        out.append(Finding(
            "CMP010", ERROR, GRADLE, 0,
            f'the self-update repo "{RELEASE_REPO}" is not referenced anywhere',
        ))
    return out


def scan_bundled_fallback(root: Path) -> list[Finding]:
    """CMP011: a network-only catalogue with no bundled copy.

    Incident: the translation pack catalogue was fetched from
    raw.githubusercontent.com only. That host is blocked on Turkish networks,
    so the screen was empty for the user this fork exists FOR. The rule: a file
    that reads a catalogue from the internet must also mention a bundled
    fallback.
    """
    out: list[Finding] = []
    remote_hosts = re.compile(r"(?i)(raw\.githubusercontent\.com|gist\.github\.com)")
    fallback = re.compile(r"(?i)(assets/|assetManager|bundledFallback|FALLBACK_|"
                          r"fallbackAssets|assets\(\)|TranslationPacks\.)")
    http_call = re.compile(
        r"(?:\bURL\(\s*\"https?:|HttpURLConnection|OkHttpClient|openConnection\(|"
        r"\.openStream\(|HttpClient|newCall\()"
    )
    for path in walk(root, KOTLIN_EXT, subdir=MAIN_JAVA):
        text = read(path)
        if not remote_hosts.search(text):
            continue
        # A URL rewrite or a WebView navigation is not a catalogue fetch; only a
        # real request counts. (`loadUrl("https:...")` used to match a looser
        # pattern here and produced a finding on a file that never fetches.)
        if not http_call.search(text):
            continue
        if fallback.search(text):
            continue
        text_lines = text.splitlines()
        hit = next((i for i, l in enumerate(text_lines)
                    if remote_hosts.search(l) and not l.strip().startswith(("*", "//"))), 0)
        if suppressed(text_lines, hit, "CMP011"):
            continue
        out.append(Finding(
            "CMP011", severity=WARN, file=rel(path, root), line=hit + 1,
            message="catalogue fetched from a host blocked on Turkish networks, "
                    "with no bundled fallback in this file",
            excerpt=text_lines[hit].strip(),
        ))
    return out


def scan_workflows(root: Path) -> list[Finding]:
    """CMP012: an upload/download artifact path pair that cannot line up.

    Incident: upload-artifact v4 stores files relative to their COMMON
    ANCESTOR. Uploading `src/android/app/libs/...` then downloading to `.`
    produced `./libs/...` and failed the build on a good artifact.
    """
    out: list[Finding] = []
    wf_dir = root / ".github/workflows"
    if not wf_dir.exists():
        return out
    for path in sorted(wf_dir.glob("*.yml")):
        text = read(path)
        if "upload-artifact" not in text:
            continue
        for m in re.finditer(r"download-artifact@v\d[\s\S]{0,400}?path:\s*([^\s#]+)", text):
            dl = m.group(1).strip()
            if dl not in (".", "./"):
                continue
            line_no = text.count("\n", 0, m.start()) + 1
            out.append(Finding(
                "CMP012", severity=WARN, file=rel(path, root), line=line_no,
                message="download-artifact with path: '.' — v4 strips upload paths to "
                        "their common ancestor, so files land in the wrong place",
                excerpt=dl,
            ))
    return out


def scan_secrets_hygiene(root: Path) -> list[Finding]:
    """CMP013: a key-bearing file that is not in .gitignore."""
    out: list[Finding] = []
    gi = root / ".gitignore"
    if not gi.exists():
        return [Finding("CMP013", WARN, ".gitignore", 0, ".gitignore is missing")]
    text = read(gi)
    wanted = {
        "keystore.properties": "signing key passwords",
        "local.properties": "SDK path and occasionally a token",
        "*.jks": "the release keystore itself",
        "*.keystore": "the release keystore itself",
    }
    for pattern, why in wanted.items():
        stem = pattern.lstrip("*")
        if pattern in text or stem in text:
            continue
        out.append(Finding(
            "CMP013", severity=ERROR, file=".gitignore", line=0,
            message=f"{pattern} is not ignored ({why})",
            excerpt=pattern,
        ))
    return out


def scan_licence_headers(root: Path) -> list[Finding]:
    """CMP014: GPL-3.0 obligations that can be checked mechanically."""
    out: list[Finding] = []
    if not (root / "LICENSE").exists():
        out.append(Finding("CMP014", ERROR, "LICENSE", 0,
                           "LICENSE is missing — GPL-3.0 requires it to travel with the source"))
    if not (root / "THIRD_PARTY_LICENSES.md").exists():
        out.append(Finding("CMP014", WARN, "THIRD_PARTY_LICENSES.md", 0,
                           "third-party licence notices are missing"))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--update-baseline", action="store_true")
    ap.add_argument("--json")
    args = ap.parse_args()

    root = repo_root()
    audit_dir = Path(__file__).resolve().parent

    findings: list[Finding] = []
    findings += scan_locales(root)
    findings += scan_string_keys(root)
    findings += scan_translation_parity(root)
    findings += scan_version(root)
    findings += scan_identity(root)
    findings += scan_bundled_fallback(root)
    findings += scan_workflows(root)
    findings += scan_secrets_hygiene(root)
    findings += scan_licence_headers(root)

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
