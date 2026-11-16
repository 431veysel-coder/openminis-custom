"""Shared plumbing for the three auditors.

[MINIS-OZEL 11.3.1] One report shape, one exit-code rule, one baseline.

Why a baseline at all: this repository is a fork of a 4,000-file codebase that
was never audited. Turning on twelve new rules at once produces hundreds of
pre-existing findings, and a gate that is red on arrival gets switched off
within a week. So every finding carries a fingerprint (rule + file + the text
of the line, not the line number), pre-existing findings are recorded in
baseline.json, and CI fails only on findings that are NEW. The intent is that
`--update-baseline` is run once, deliberately, and never as a way to pass.

Every rule lives in one of the three auditors and names the failure it encodes
in its docstring. A rule with no historical failure behind it does not belong
here — the goal is "we never hit this again", not "a generic linter".
"""

from __future__ import annotations

import dataclasses
import hashlib
import json
import os
import re
import sys
from pathlib import Path

# ─── severities ──────────────────────────────────────────────────────────────
# ERROR fails CI. WARN is reported and does not. INFO is context for a human
# reading the output. A rule that cannot be trusted to be right every time is
# a WARN even if the underlying problem is serious — a noisy error gate is a
# disabled gate.

ERROR = "error"
WARN = "warn"
INFO = "info"

_RANK = {INFO: 0, WARN: 1, ERROR: 2}


@dataclasses.dataclass(frozen=True)
class Finding:
    rule: str
    severity: str
    file: str          # repo-relative
    line: int          # 0 = file-level
    message: str
    excerpt: str = ""  # the offending text, trimmed — part of the fingerprint

    @property
    def fingerprint(self) -> str:
        """Stable across line-number churn and reformatting elsewhere."""
        raw = f"{self.rule}|{self.file}|{self.excerpt.strip()}"
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]

    def as_dict(self) -> dict:
        d = {
            "rule": self.rule,
            "severity": self.severity,
            "file": self.file,
            "line": self.line,
            "message": self.message,
            "excerpt": self.excerpt.strip(),
            "fingerprint": self.fingerprint,
        }
        return d


def repo_root(start: Path | None = None) -> Path:
    p = (start or Path.cwd()).resolve()
    for candidate in [p, *p.parents]:
        if (candidate / ".git").exists():
            return candidate
    # scripts/audit/common.py -> repo root is two levels up
    return Path(__file__).resolve().parents[2]


# ─── source discovery ────────────────────────────────────────────────────────

SKIP_DIRS = {
    ".git", "build", ".gradle", "node_modules", "Pods", ".idea",
    "DerivedData", "deps", "packs", ".kotlin",
}

KOTLIN_EXT = {".kt", ".kts"}
XML_EXT = {".xml"}


def walk(root: Path, exts: set[str], *, subdir: str = "") -> list[Path]:
    """All tracked-ish source files under root/subdir with one of `exts`."""
    base = root / subdir if subdir else root
    out: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if Path(name).suffix in exts:
                out.append(Path(dirpath) / name)
    return sorted(out)


def rel(path: Path, root: Path) -> str:
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


# ─── suppression ─────────────────────────────────────────────────────────────
# `// audit:ok RULE` on the offending line, or `<!-- audit:ok RULE -->`.
# Suppression is per rule, so silencing one verdict does not silence the file.
# The repository's culture is to explain the exception in a comment above it;
# this mechanism only makes the exception possible, it does not make it free.

_SUPPRESS_RE = re.compile(r"audit:\s*ok(?:\s+([A-Za-z0-9_,\-]+))?")


def suppressed(lines: list[str], index: int, rule: str) -> bool:
    """True when the line at `index` (0-based) or the comment block above it
    mutes `rule`.

    The block walk matters: a suppression deserves a reason, and the reason is
    usually two or three comment lines long. Only consecutive comment lines are
    searched, so an `audit:ok` in unrelated code above does not leak down.
    """
    if not lines:
        return False
    index = max(0, min(index, len(lines) - 1))
    offsets = [index]
    probe = index - 1
    guard = 0
    while probe >= 0 and guard < 12:
        stripped = lines[probe].strip()
        if not stripped.startswith(("//", "*", "/*", "#", "<!--")):
            break
        offsets.append(probe)
        probe -= 1
        guard += 1
    for offset in offsets:
        if not (0 <= offset < len(lines)):
            continue
        m = _SUPPRESS_RE.search(lines[offset])
        if m:
            names = (m.group(1) or "").strip()
            if not names or "*" in names:
                return True
            wanted = {n.strip().upper() for n in names.split(",")}
            if rule.upper() in wanted:
                return True
    return False


# ─── baseline ────────────────────────────────────────────────────────────────

BASELINE_NAME = "baseline.json"


def baseline_path(audit_dir: Path, name: str = "") -> Path:
    """One baseline file per auditor.

    The first version shared a single baseline.json and every auditor silently
    overwrote the previous one's — so two thirds of the known findings were
    "new" again on the next run. The name is part of the file name for that
    reason.
    """
    return audit_dir / (f"baseline-{name}.json" if name else BASELINE_NAME)


def load_baseline(audit_dir: Path, name: str = "") -> dict[str, str]:
    path = baseline_path(audit_dir, name)
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return dict(data.get("known", {}))


def write_baseline(audit_dir: Path, findings: list[Finding], name: str = "") -> Path:
    path = baseline_path(audit_dir, name)
    known: dict[str, str] = {}
    for f in findings:
        known[f.fingerprint] = f"{f.rule} {f.file}:{f.line}"
    path.write_text(
        json.dumps(
            {
                "_comment": (
                    "Findings that predate the auditor. CI fails on anything NOT "
                    "listed here. Regenerate deliberately with "
                    "`audit_all.py --update-baseline` after reviewing the report."
                ),
                "known": dict(sorted(known.items())),
            },
            indent=2,
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    return path


# ─── report ──────────────────────────────────────────────────────────────────

def group(findings: list[Finding]) -> dict[str, list[Finding]]:
    out: dict[str, list[Finding]] = {}
    for f in findings:
        out.setdefault(f.rule, []).append(f)
    return dict(sorted(out.items()))


def print_report(name: str, findings: list[Finding], known: dict[str, str]) -> tuple[int, int]:
    """Prints the grouped report; returns (new_errors, new_warnings)."""
    new_errors = new_warnings = 0
    baseline_hits = 0
    if not findings:
        print(f"[{name}] clean — nothing to report")
        return 0, 0
    for rule, items in group(findings).items():
        fresh = [f for f in items if f.fingerprint not in known]
        stale = len(items) - len(fresh)
        baseline_hits += stale
        if not fresh:
            print(f"[{name}] {rule}: {stale} known finding(s) — suppressed by baseline")
            continue
        worst = max((f.severity for f in fresh), key=lambda s: _RANK[s])
        print(f"\n[{name}] {rule}  ({len(fresh)} new, {stale} known, worst={worst})")
        for f in sorted(fresh, key=lambda x: (x.file, x.line)):
            loc = f.file if not f.line else f"{f.file}:{f.line}"
            print(f"  {f.severity:5} {loc}  {f.message}")
            if f.excerpt.strip():
                print(f"        > {f.excerpt.strip()[:160]}")
        for f in fresh:
            if f.severity == ERROR:
                new_errors += 1
            elif f.severity == WARN:
                new_warnings += 1
    if baseline_hits:
        print(f"\n[{name}] {baseline_hits} pre-existing finding(s) carried by the baseline")
    return new_errors, new_warnings


def finish(name: str, findings: list[Finding], known: dict[str, str]) -> int:
    errors, warnings = print_report(name, findings, known)
    print(f"\n[{name}] {len(findings)} finding(s), {errors} new error(s), {warnings} new warning(s)")
    return 1 if errors else 0


def json_out(path: str, sections: dict[str, list[Finding]]) -> None:
    payload = {
        "generated_utc": __import__("datetime").datetime.utcnow().isoformat(timespec="seconds") + "Z",
        "sections": {
            name: [f.as_dict() for f in items] for name, items in sections.items()
        },
    }
    Path(path).write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main_wrapper(fn):  # pragma: no cover - convenience for the three entry points
    def run():
        sys.exit(fn())
    return run
