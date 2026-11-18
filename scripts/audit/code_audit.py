#!/usr/bin/env python3
"""Code auditor — the defect classes that cost us the most time.

[MINIS-OZEL 11.3.1] Each rule maps to a specific bug this project actually hit,
quoted in the rule's docstring. Nothing here is a general style opinion; a rule
that cannot point at a real incident does not belong in this file.

    python3 scripts/audit/code_audit.py [--update-baseline] [--json PATH]
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

NAME = "code"

MAIN = "src/android/app/src/main"
MAIN_JAVA = "src/android/app/src/main/java"


def _line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def _strip_strings(line: str) -> str:
    """Remove string literals so rules do not fire on prose."""
    return re.sub(r'"(?:[^"\\]|\\.)*"', '""', line)


# ── CODE001: network / file IO on the main thread ────────────────────────────
# Incident: NetworkOnMainThreadException on the translation catalogue, because
# a repository call ran on the caller's dispatcher and the caller was the main
# thread. The fix was withContext(Dispatchers.IO). The rule targets the shape
# of the original mistake: a blocking call inside a main-dispatcher block.
MAIN_BLOCK = re.compile(r"(?:Dispatchers\.Main|MainScope\(\)|GlobalScope\.launch\s*\(\s*Dispatchers\.Main)")
BLOCKING_CALL = re.compile(
    r"(?:\b\w+\.execute\(\)|kotlinx\.coroutines\.runBlocking\s*\{|\brunBlocking\s*\{|"
    r"URL\([^)]*\)\.openConnection|\.openStream\(\)|FileInputStream\(|FileOutputStream\(|"
    r"\.readText\(\)|\.writeText\(|OkHttpClient\(|HttpURLConnection)"
)


def _block_after(text: str, brace_index: int) -> str:
    """Return the text of the `{...}` block starting at brace_index.

    Character-level brace matching, with strings and line comments blanked out
    first. The earlier version counted braces per line and overran the block
    whenever an anonymous object or a lambda nested inside it — which made the
    rule fire on the *next* coroutine block.
    """
    i = 0
    out = []
    depth = 0
    in_str = False
    in_char = False
    in_line_comment = False
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if in_line_comment:
            out.append(ch)
            if ch == "\n":
                in_line_comment = False
            i += 1
            continue
        if in_str:
            out.append(ch)
            if ch == "\\":
                out.append(nxt)
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue
        if in_char:
            out.append(ch)
            if ch == "\\":
                out.append(nxt)
                i += 2
                continue
            if ch == "'":
                in_char = False
            i += 1
            continue
        if ch == '"':
            in_str = True
        elif ch == "'":
            in_char = True
        elif ch == "/" and nxt == "/":
            in_line_comment = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                out.append(ch)
                break
        out.append(ch)
        i += 1
    return "".join(out)


def scan_main_thread_io(root: Path) -> list[Finding]:
    out: list[Finding] = []
    for path in walk(root, KOTLIN_EXT, subdir=MAIN_JAVA):
        text = read(path)
        lines = text.splitlines()
        for m in MAIN_BLOCK.finditer(text):
            brace = text.find("{", m.end())
            if brace == -1:
                continue
            body = _block_after(text, brace)
            # Hop-back is the fix, so a body that already hops is clean.
            if re.search(r"withContext\s*\(\s*Dispatchers\.(IO|Default)", body):
                continue
            for bm in BLOCKING_CALL.finditer(body):
                line_no = _line_of(text, brace + bm.start())
                if suppressed(lines, line_no - 1, "CODE001"):
                    continue
                excerpt = lines[line_no - 1].strip() if line_no - 1 < len(lines) else bm.group(0)
                out.append(Finding(
                    rule="CODE001", severity=ERROR, file=rel(path, root), line=line_no,
                    message="blocking call inside a main-dispatcher block — "
                            "wrap it in withContext(Dispatchers.IO)",
                    excerpt=excerpt,
                ))
                break  # one finding per block is enough to act on
    return out


# ── CODE002: silent failure ──────────────────────────────────────────────────
# Incident: nine blind fixes in a row on NoteRepository were possible because a
# parse error was swallowed into `catch { return emptyList() }`; the screen and
# the tests both looked fine while the data was empty.
EMPTY_CATCH = re.compile(r"catch\s*\([^)]*\)\s*\{\s*\}?")
SWALLOW_OK = re.compile(r"(?i)audit:\s*ok")


def scan_silent_catch(root: Path) -> list[Finding]:
    out: list[Finding] = []
    seen: set[tuple[str, int]] = set()
    for path in walk(root, KOTLIN_EXT, subdir=MAIN_JAVA):
        text = read(path)
        lines = text.splitlines()
        relp = rel(path, root)
        for m in EMPTY_CATCH.finditer(text):
            line_no = _line_of(text, m.start())
            if suppressed(lines, line_no - 1, "CODE002"):
                continue
            body_tail = text[m.end(): m.end() + 240]
            close = body_tail.find("}")
            body = body_tail[:close] if close != -1 else body_tail
            # Only an actually empty body counts.
            stripped_body = re.sub(r"//[^\n]*|/\*.*?\*/", "", body, flags=re.S).strip()
            if stripped_body and not re.match(
                r"^return\s+(null|emptyList|emptyMap|emptySet|\"\"|0|false|Unit)\b", stripped_body
            ):
                continue
            key = (relp, line_no)
            if key in seen:
                continue
            seen.add(key)
            if stripped_body:
                out.append(Finding(
                    rule="CODE002", severity=WARN, file=relp, line=line_no,
                    message="exception swallowed and replaced with a default value — "
                            "the caller cannot tell failure from empty",
                    excerpt=(lines[line_no - 1].strip() + " " + stripped_body)[:160],
                ))
            else:
                # A best-effort empty catch is normal Android; it is only worth
                # a line in the report so a reviewer sees it. Not a warning.
                out.append(Finding(
                    rule="CODE002", severity=INFO, file=relp, line=line_no,
                    message="empty catch with no explanation — add a comment or log",
                    excerpt=lines[line_no - 1].strip(),
                ))
    return out


# ── CODE003: locale-sensitive case conversion ────────────────────────────────
# Incident class: the Turkish dotted/dotless i. "SIL" lowercased with the
# default locale becomes "sıl" on a tr-TR device and stops matching every
# ASCII comparison in the app. This fork ships to Turkish devices first.
LOCALE_CASE = re.compile(r"\.(?:toLowerCase|toUpperCase|capitalize)\s*\(\s*\)")
LOCALE_SAFE = re.compile(r"(?:Locale\.(?:ROOT|US|ENGLISH|forLanguageTag)|kotlin\.text\.|lowercase\()")


def scan_locale_case(root: Path) -> list[Finding]:
    out: list[Finding] = []
    for path in walk(root, KOTLIN_EXT, subdir=MAIN_JAVA):
        text = read(path)
        lines = text.splitlines()
        for i, line in enumerate(lines):
            code = _strip_strings(line)
            if "//" in code:
                code = code.split("//", 1)[0]
            if not LOCALE_CASE.search(code):
                continue
            if LOCALE_SAFE.search(code):
                continue
            if suppressed(lines, i, "CODE003"):
                continue
            out.append(Finding(
                rule="CODE003", severity=WARN, file=rel(path, root), line=i + 1,
                message="locale-sensitive case conversion (tr-TR: 'I' → 'ı') — "
                        "use lowercase(Locale.ROOT) / .lowercase()",
                excerpt=line.strip(),
            ))
    return out


# ── CODE004: version literal outside build.gradle.kts ────────────────────────
# Incident: main said versionName 1.15-ozel.13 for a release that had been
# withdrawn, because a second copy of the string lived in code and drifted.
VERSION_LITERAL = re.compile(r"""["']1\.\d+(?:\.\d+)*-ozel[^"']*["']""")
VERSION_COUNTER = re.compile(r"\bversionCode\s*=\s*\d{4,}")


def scan_version_literals(root: Path) -> list[Finding]:
    out: list[Finding] = []
    for path in walk(root, KOTLIN_EXT, subdir=MAIN):
        if path.name in {"build.gradle.kts", "build.gradle"}:
            continue
        text = read(path)
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if suppressed(lines, i, "CODE004"):
                continue
            if VERSION_COUNTER.search(line) and "BuildConfig" not in line:
                out.append(Finding(
                    rule="CODE004", severity=ERROR, file=rel(path, root), line=i + 1,
                    message="versionCode literal outside build.gradle.kts — read "
                            "BuildConfig.VERSION_CODE instead",
                    excerpt=line.strip(),
                ))
            elif VERSION_LITERAL.search(line) and "BuildConfig" not in line:
                out.append(Finding(
                    rule="CODE004", severity=ERROR, file=rel(path, root), line=i + 1,
                    message="versionName literal outside build.gradle.kts — it will drift",
                    excerpt=line.strip(),
                ))
    return out


# ── CODE005: XML built without escaping ──────────────────────────────────────
# Incident: NoteRepository.escapeXml — bodies containing & or < produced an
# unparseable document, and the empty catch above hid it.
XML_BUILD = re.compile(r"""["']<\?xml\b|["']<[a-zA-Z][a-zA-Z0-9]*>?""")
ESC_HELPER = re.compile(r"(?:escapeXml|xmlEscape|htmlEncode|TextUtils\.htmlEncode|"
                        r"XmlSerializer|\.escape\(|android\.util\.Xml)")


def scan_xml_escaping(root: Path) -> list[Finding]:
    out: list[Finding] = []
    for path in walk(root, KOTLIN_EXT, subdir=MAIN_JAVA):
        text = read(path)
        if not re.search(r"""["']<\?xml\b""", text):
            continue
        if ESC_HELPER.search(text):
            continue
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if re.search(r"""["']<\?xml\b""", line):
                if suppressed(lines, i, "CODE005"):
                    continue
                out.append(Finding(
                    rule="CODE005", severity=WARN, file=rel(path, root), line=i + 1,
                    message="XML built by string concatenation with no escape helper — "
                            "a body containing '&' or '<' will produce invalid XML",
                    excerpt=line.strip(),
                ))
                break
    return out


# ── CODE006: elapsed time measured with the wall clock ───────────────────────
# Incident class: a "took 12s" measurement that went negative after the device
# time was corrected by the network.
WALL_CLOCK_ELAPSED = re.compile(
    r"(?:val|var)\s+(\w*(?:elapsed|took|duration|latency|start|begin|t0)\w*)\s*=\s*"
    r"(?:System\.)?currentTimeMillis\(\)|"
    r"(?:System\.)?currentTimeMillis\(\)\s*-\s*\w*(?:start|begin|t0|elapsed)\w*",
    re.I,
)


def scan_elapsed_time(root: Path) -> list[Finding]:
    out: list[Finding] = []
    for path in walk(root, KOTLIN_EXT, subdir=MAIN_JAVA):
        text = read(path)
        lines = text.splitlines()
        for i, line in enumerate(lines):
            code = _strip_strings(line)
            if not WALL_CLOCK_ELAPSED.search(code):
                continue
            if "elapsedRealtime" in code or "nanoTime" in code:
                continue
            if suppressed(lines, i, "CODE006"):
                continue
            out.append(Finding(
                rule="CODE006", severity=WARN, file=rel(path, root), line=i + 1,
                message="elapsed time measured with the wall clock — use "
                        "SystemClock.elapsedRealtime()",
                excerpt=line.strip(),
            ))
    return out


# ── CODE007: documented global-singleton traps ──────────────────────────────
# Every entry here broke something once. A new call site is not automatically
# wrong, so this is INFO — but it puts the trap in front of whoever is reading
# the diff, which is the whole point.
TRAPS: dict[str, str] = {
    "BrowserTabPoolRegistry": "global browser tab pool: in a multi-panel "
                              "(Station) context tabs from different panels mix",
    "ChatViewModelStore": "bounded cache: Station panel view models can be "
                          "evicted mid-run, losing the in-flight turn",
    "AppIconRepository.PACKAGE_NAME": "must stay the NAMESPACE, not the "
                                      "applicationId — activity-alias class names break",
    "raw.githubusercontent.com": "blocked on Turkish networks: network-only "
                                 "catalogs need a bundled fallback",
}
TRAP_OWNER = re.compile(r"(?:object|class)\s+(?:BrowserTabPoolRegistry|ChatViewModelStore)")


def scan_traps(root: Path) -> list[Finding]:
    out: list[Finding] = []
    for path in walk(root, KOTLIN_EXT, subdir=MAIN_JAVA):
        text = read(path)
        lines = text.splitlines()
        for i, line in enumerate(lines):
            code = _strip_strings(line)
            for name, why in TRAPS.items():
                if name not in line:
                    continue
                # Only a *use* matters: a call, a registration, an assignment.
                leaf = name.split(".")[-1]
                if not re.search(rf"{re.escape(leaf)}\s*[.(]|{re.escape(leaf)}\s*=[^=]", code):
                    continue
                if path.name.lower().startswith(leaf.lower()):
                    continue  # the definition itself
                if suppressed(lines, i, "CODE007"):
                    continue
                out.append(Finding(
                    rule="CODE007", severity=INFO, file=rel(path, root), line=i + 1,
                    message=f"known trap — {why}",
                    excerpt=line.strip(),
                ))
    return out


# ── CODE008: unbounded retry / loop detector escape ─────────────────────────
# Incident: "döngüye girmekten çekinme" is an instruction, not a licence for a
# loop with no exit. A while(true) in tool code with no attempt counter and no
# return on failure is a hang. Advisory: the shape is legitimate in a service
# loop, so WARN only when the function also contains a suspend call.
WHILE_TRUE = re.compile(r"\bwhile\s*\(\s*true\s*\)")


# ── CODE009: hand-built per-space host path ─────────────────────────────────
# Incident: [MINIS-OZEL 37070] `fsSessionId` became a *space key* — a chat UUID
# or `project:<folderId>` — and every site that built
# `File(filesDir, "minis-sessions/$id/…")` by hand kept its old meaning while
# looking identical. A missed site reads another project's directory and
# nothing fails: the agent just writes into the wrong workspace. FsSpace is the
# one translation; this rule keeps new raw concatenations from reappearing.
#
# Only a STRING LITERAL counts: prose in a doc comment that mentions the layout
# is documentation, not a path. `FsSpace.kt` itself is exempt (it owns the
# strings) and so is anything through `FsSpace.*`.
RAW_SPACE_PATH = re.compile(r'"\s*(?:minis-sessions|minis-spaces)\s*/')
FS_SPACE_FILE = "FsSpace.kt"

def scan_raw_space_paths(root: Path) -> list[Finding]:
    out: list[Finding] = []
    for path in walk(root, KOTLIN_EXT, subdir=MAIN_JAVA):
        if path.name == FS_SPACE_FILE:
            continue
        text = read(path)
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if not RAW_SPACE_PATH.search(line):
                continue
            if line.lstrip().startswith(("*", "//", "/*")):
                continue
            if suppressed(lines, i, "CODE009"):
                continue
            out.append(Finding(
                rule="CODE009", severity=WARN, file=rel(path, root), line=i + 1,
                message="hand-built per-space path — go through FsSpace.subDir/baseDir",
                excerpt=line.strip(),
            ))
    return out

def scan_loops(root: Path) -> list[Finding]:
    out: list[Finding] = []
    # Only the agent loop matters here: that is where a runaway iteration costs
    # tokens and hangs the UI. A while(true) in a foreground service is normal.
    for path in walk(root, KOTLIN_EXT, subdir=MAIN_JAVA):
        relp = rel(path, root)
        if not re.search(r"/(agent|tools|offload)/", relp):
            continue
        text = read(path)
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if not WHILE_TRUE.search(_strip_strings(line)):
                continue
            window = "\n".join(lines[i: i + 40])
            if re.search(r"(?:attempt|retries|maxAttempts|budget|deadline|MAX_)", window, re.I):
                continue
            if suppressed(lines, i, "CODE008"):
                continue
            out.append(Finding(
                rule="CODE008", severity=INFO, file=relp, line=i + 1,
                message="while(true) with no attempt counter or deadline in sight",
                excerpt=line.strip(),
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
    findings += scan_main_thread_io(root)
    findings += scan_silent_catch(root)
    findings += scan_locale_case(root)
    findings += scan_version_literals(root)
    findings += scan_xml_escaping(root)
    findings += scan_elapsed_time(root)
    findings += scan_traps(root)
    findings += scan_raw_space_paths(root)
    findings += scan_loops(root)

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
