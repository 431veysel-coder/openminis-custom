#!/usr/bin/env python3
"""
Re-apply this fork's identity after an upstream merge.

## Why this exists

Merging `OpenMinis/OpenMinis` into this repo resolves most files cleanly — the
fork's own features are new files upstream has never seen. What it does NOT
resolve cleanly is the identity: upstream's `build.gradle.kts` says
`com.openminis.app`, `versionCode = 37`, `RELEASE_REPO = "OpenMinis/OpenMinis"`,
and its locale files say `app_name = Minis`. A merge that keeps upstream's
version of those files produces an APK that:

  - installs over the OFFICIAL app (same applicationId, different signer) —
    the package manager refuses it, and the user sees "App not installed";
  - or, worse, replaces the official app on a device that uninstalled ours;
  - has an updater pointed at a release stream signed with a key we do not own;
  - shows "Minis" on the launcher next to the official Minis.

`scripts/check_identity.sh` catches all of that — but it catches it AFTER the
merge, as a failure, with no repair. This script is the repair, and it is
deliberately idempotent so it can run on every sync without thinking about
whether it already ran.

## What it must never do

It never touches `namespace` (must stay `com.openminis.app`: component class
names are built from it — see FORK.md §5, the AppIconRepository trap) and it
never changes `versionCode`/`versionName` (the release counter is ours; upstream
has its own scheme, and taking theirs would move installs backwards).

Usage:
    python3 scripts/apply_identity.py [--check]

`--check` exits non-zero if anything WOULD be changed, without writing.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

APPLICATION_ID = "com.veysel.minisx"
NAMESPACE = "com.openminis.app"
RELEASE_REPO = "431veysel-coder/openminis-custom"
APP_NAME = "Minis X"

GRADLE = ROOT / "src/android/app/build.gradle.kts"
RES = ROOT / "src/android/app/src/main/res"


class Change:
    def __init__(self, path: pathlib.Path, what: str, before: str, after: str):
        self.path = path
        self.what = what
        self.before = before
        self.after = after

    def __str__(self) -> str:
        rel = self.path.relative_to(ROOT) if self.path.is_relative_to(ROOT) else self.path
        return f"  {rel}: {self.what}: {self.before!r} -> {self.after!r}"


def sub_once(text: str, pattern: str, repl: str, flags: int = 0):
    """Replace the first match; return (new_text, matched_text_or_None)."""
    m = re.search(pattern, text, flags)
    if not m:
        return text, None
    return text[: m.start()] + repl + text[m.end():], m.group(0)


def plan() -> list[tuple[pathlib.Path, str, str, str]]:
    """Compute every needed edit without writing. (path, what, before, after)."""
    edits: list[tuple[pathlib.Path, str, str, str]] = []

    # ── build.gradle.kts ───────────────────────────────────────────────────
    if GRADLE.exists():
        text = GRADLE.read_text(encoding="utf-8")

        for pattern, want, label in [
            (r'applicationId\s*=\s*"[^"]*"', f'applicationId = "{APPLICATION_ID}"', "applicationId"),
            (r'namespace\s*=\s*"[^"]*"', f'namespace = "{NAMESPACE}"', "namespace"),
        ]:
            m = re.search(pattern, text)
            if m and m.group(0) != want:
                edits.append((GRADLE, label, m.group(0), want))

        # RELEASE_REPO has a nested-quote shape: buildConfigField("String", "RELEASE_REPO", "\"owner/repo\"")
        m = re.search(r'"RELEASE_REPO"\s*,\s*"\\"[^"\\]*\\""', text)
        if m:
            want = f'"RELEASE_REPO", "\\"{RELEASE_REPO}\\""'
            if m.group(0) != want:
                edits.append((GRADLE, "RELEASE_REPO", m.group(0), want))

        # versionCode must stay above the withdrawn 37009-37013 range. If an
        # upstream merge brought 37 back, that is not "keeping ours" — it is a
        # build that can never update anyone. Report it as a hard failure
        # instead of silently inventing a number.
        m = re.search(r"versionCode\s*=\s*(\d+)", text)
        if m and int(m.group(1)) <= 37013:
            edits.append(
                (
                    GRADLE,
                    "versionCode",
                    m.group(0),
                    "versionCode = <MANUAL: must be above 37013>",
                )
            )

        m = re.search(r'versionName\s*=\s*"([^"]*)"', text)
        if m and "-ozel" not in m.group(1):
            edits.append(
                (GRADLE, "versionName", m.group(0), 'versionName = "<MANUAL: needs -ozel suffix>"')
            )

    # ── app_name in every locale that overrides it ─────────────────────────
    default = RES / "values" / "strings.xml"
    if default.exists():
        text = default.read_text(encoding="utf-8")
        m = re.search(r'(<string name="app_name">)([^<]*)(</string>)', text)
        if m and m.group(2) != APP_NAME:
            edits.append(
                (default, "app_name", m.group(2), APP_NAME)
            )

    for locale in sorted(RES.glob("values-*")):
        f = locale / "strings.xml"
        if not f.exists():
            continue
        text = f.read_text(encoding="utf-8")
        m = re.search(r'(<string name="app_name">)([^<]*)(</string>)', text)
        if m and m.group(2) != APP_NAME:
            edits.append((f, "app_name", m.group(2), APP_NAME))

    return edits


def apply(edits: list[tuple[pathlib.Path, str, str, str]]) -> None:
    by_file: dict[pathlib.Path, list[tuple[str, str, str]]] = {}
    for path, what, before, after in edits:
        by_file.setdefault(path, []).append((what, before, after))

    for path, items in by_file.items():
        text = path.read_text(encoding="utf-8")
        for what, before, after in items:
            if what == "app_name":
                # app_name always carries the same three-part shape.
                text = re.sub(
                    r'(<string name="app_name">)([^<]*)(</string>)',
                    lambda m, a=after: m.group(1) + a + m.group(3),
                    text,
                    count=1,
                )
                continue
            if "<MANUAL" in after:
                # Never guess a version number; check_identity.sh reports it.
                continue
            text = text.replace(before, after, 1)
        path.write_text(text, encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", action="store_true", help="report without writing")
    args = ap.parse_args()

    edits = plan()
    if not edits:
        print("identity: nothing to re-apply")
        return 0

    manual = [e for e in edits if "<MANUAL" in e[3]]
    if args.check:
        print(f"identity: {len(edits)} change(s) needed")
        for path, what, before, after in edits:
            print(Change(path, what, before, after))
        return 1 if edits else 0

    print(f"identity: applying {len(edits)} change(s)")
    for path, what, before, after in edits:
        print(Change(path, what, before, after))
    apply(edits)

    if manual:
        print()
        print("::error::The following need a human decision (never guessed):")
        for path, what, before, after in manual:
            print(Change(path, what, before, after))
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
