#!/usr/bin/env python3
"""Self-test for the three auditors.

[MINIS-OZEL 11.3.1] An auditor that silently stops matching is worse than no
auditor: it reports "clean" forever and everyone believes it. The secret
scanner already carries this lesson (scripts/secret_scan.py --self-test); the
new gates inherit it.

Each case below plants one defect in a throwaway tree and asserts the matching
rule fires. The NEGATIVE cases matter as much: a rule that fires on correct
code teaches people to mute it, and a muted rule protects nothing.

    python3 scripts/audit/self_test.py
"""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import code_audit          # noqa: E402
import compliance_audit    # noqa: E402
import security_audit      # noqa: E402

MAIN = "src/android/app/src/main"
JAVA = f"{MAIN}/java/com/openminis/app"
RES = f"{MAIN}/res"


class Tree:
    """A disposable repository-shaped directory."""

    def __enter__(self) -> "Tree":
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        (self.root / JAVA).mkdir(parents=True, exist_ok=True)
        (self.root / RES / "values").mkdir(parents=True, exist_ok=True)
        (self.root / RES / "values-tr").mkdir(parents=True, exist_ok=True)
        return self

    def __exit__(self, *exc) -> None:
        self._tmp.cleanup()

    def write(self, rel_path: str, content: str) -> Path:
        p = self.root / rel_path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
        return p

    def baseline(self) -> dict[str, str]:
        return {}


# (rule, label, fn, expect) — expect is "fires" or "clean". The clean cases are
# not decoration: a rule that fires on correct code gets muted, and a muted rule
# protects nothing.
CASES: list[tuple[str, str, object, str]] = []


def case(rule: str, label: str, expect: str = "fires"):
    def deco(fn):
        CASES.append((rule, label, fn, expect))
        return fn
    return deco


def _rules(found) -> set[str]:
    return {f.rule for f in found}


# ── security ────────────────────────────────────────────────────────────────

@case("SEC001", "a live-looking API key in a Kotlin file trips the scan")
def _(t: Tree):
    t.write(f"{JAVA}/Leak.kt", 'val k = "sk-proj-AbCdEf0123456789AbCdEf0123456789"\n')
    return security_audit.scan_secrets(t.root)


@case("SEC001", "a placeholder key does NOT trip the scan", expect="clean")
def _(t: Tree):
    t.write(f"{JAVA}/Fine.kt", 'val k = "sk-your-api-key-here-placeholder"\n')
    return security_audit.scan_secrets(t.root)


@case("SEC002", "a credential written to plain preferences trips the scan")
def _(t: Tree):
    t.write(f"{JAVA}/Bad.kt",
            'fun save(p: SharedPreferences, apiKey: String) {\n'
            '    p.edit().putString("api_key", apiKey).apply()\n'
            '}\n')
    return security_audit.scan_insecure_storage(t.root)


@case("SEC002", "the same call through EncryptedPrefs does NOT trip the scan", expect="clean")
def _(t: Tree):
    t.write(f"{JAVA}/Good.kt",
            'fun save(c: Context, apiKey: String) {\n'
            '    val prefs = EncryptedPrefsFactory.safeCreate(c, "f")\n'
            '    prefs.edit().putString("api_key", apiKey).apply()\n'
            '}\n')
    return security_audit.scan_insecure_storage(t.root)


@case("SEC003", "AES/ECB trips the cipher rule")
def _(t: Tree):
    t.write(f"{JAVA}/Bad.kt", 'val c = Cipher.getInstance("AES/ECB/PKCS5Padding")\n')
    return security_audit.scan_crypto(t.root)


@case("SEC003", "AES/GCM does NOT trip the cipher rule", expect="clean")
def _(t: Tree):
    t.write(f"{JAVA}/Good.kt", 'val c = Cipher.getInstance("AES/GCM/NoPadding")\n')
    return security_audit.scan_crypto(t.root)


@case("SEC004", "MD5 trips the digest rule")
def _(t: Tree):
    t.write(f"{JAVA}/Bad.kt", 'val d = MessageDigest.getInstance("MD5")\n')
    return security_audit.scan_crypto(t.root)


@case("SEC010", "an exported sandbox service without a permission is an error")
def _(t: Tree):
    t.write(f"{MAIN}/AndroidManifest.xml",
            '<manifest>\n  <application>\n'
            '    <service android:name=".sandbox.SandboxService" android:exported="true" />\n'
            '  </application>\n</manifest>\n')
    return security_audit.scan_manifest(t.root)


@case("SEC010", "an exported share target is only a warning")
def _(t: Tree):
    t.write(f"{MAIN}/AndroidManifest.xml",
            '<manifest>\n  <application>\n'
            '    <activity android:name=".share.ShareActivity" android:exported="true">\n'
            '      <intent-filter><action android:name="android.intent.action.SEND" /></intent-filter>\n'
            '    </activity>\n  </application>\n</manifest>\n')
    found = security_audit.scan_manifest(t.root)
    assert all(f.severity != "error" for f in found), found
    return found


# ── code ────────────────────────────────────────────────────────────────────

@case("CODE001", "totally-qualified runBlocking inside Dispatchers.Main is an error")
def _(t: Tree):
    t.write(f"{JAVA}/Bad.kt",
            'fun go() {\n'
            '    scope.launch(Dispatchers.Main) {\n'
            '        kotlinx.coroutines.runBlocking { repository.load() }\n'
            '    }\n'
            '}\n')
    return code_audit.scan_main_thread_io(t.root)


@case("CODE001", "the same block hopping to Dispatchers.IO does NOT trip", expect="clean")
def _(t: Tree):
    t.write(f"{JAVA}/Good.kt",
            'fun go() {\n'
            '    scope.launch(Dispatchers.Main) {\n'
            '        withContext(Dispatchers.IO) {\n'
            '            kotlinx.coroutines.runBlocking { repository.load() }\n'
            '        }\n'
            '    }\n'
            '}\n')
    return code_audit.scan_main_thread_io(t.root)


@case("CODE002", "an empty catch that returns an empty list is a warning")
def _(t: Tree):
    t.write(f"{JAVA}/Bad.kt",
            'fun parse(s: String): List<String> {\n'
            '    return try { listOf(s) } catch (e: Exception) { return emptyList() }\n'
            '}\n')
    return code_audit.scan_silent_catch(t.root)


@case("CODE003", "toLowerCase() without a locale trips the tr-TR rule")
def _(t: Tree):
    t.write(f"{JAVA}/Bad.kt", 'val k = name.toLowerCase()\n')
    return code_audit.scan_locale_case(t.root)


@case("CODE003", "lowercase(Locale.ROOT) does NOT trip it", expect="clean")
def _(t: Tree):
    t.write(f"{JAVA}/Good.kt", 'val k = name.lowercase(java.util.Locale.ROOT)\n')
    return code_audit.scan_locale_case(t.root)


@case("CODE004", "a versionName literal in code is an error")
def _(t: Tree):
    t.write(f"{JAVA}/Bad.kt", 'val v = "1.15-ozel.11.3.1"\n')
    return code_audit.scan_version_literals(t.root)


@case("CODE005", "XML built by concatenation with no escape helper is flagged")
def _(t: Tree):
    t.write(f"{JAVA}/Bad.kt",
            'fun doc(body: String) = "<?xml version=\\"1.0\\"?><note>" + body + "</note>"\n')
    return code_audit.scan_xml_escaping(t.root)


@case("CODE006", "a duration measured from the wall clock is flagged")
def _(t: Tree):
    t.write(f"{JAVA}/Bad.kt",
            'fun timed() {\n    val start = System.currentTimeMillis()\n}\n')
    return code_audit.scan_elapsed_time(t.root)


@case("CODE007", "a new Station panel touching the global tab pool is noted")
def _(t: Tree):
    t.write(f"{JAVA}/ui/station/Panel.kt",
            'fun open() { BrowserTabPoolRegistry.acquire() }\n')
    return code_audit.scan_traps(t.root)


# ── compliance ──────────────────────────────────────────────────────────────

@case("CMP001", "app_name reverting to Minis in any locale is an error")
def _(t: Tree):
    t.write(f"{RES}/values-tr/strings.xml", '<resources><string name="app_name">Minis</string></resources>\n')
    return compliance_audit.scan_locales(t.root)


@case("CMP002", "an unescaped apostrophe in a Turkish string is an error")
def _(t: Tree):
    t.write(f"{RES}/values-tr/strings.xml",
            '<resources><string name="x">Uygulama\'nın ayarı</string></resources>\n')
    return compliance_audit.scan_locales(t.root)


@case("CMP002", "an escaped apostrophe does NOT trip it", expect="clean")
def _(t: Tree):
    t.write(f"{RES}/values-tr/strings.xml",
            "<resources><string name=\"x\">Uygulama\\'nın ayarı</string></resources>\n")
    return compliance_audit.scan_locales(t.root)


@case("CMP003", "an R.string key with no definition is an error")
def _(t: Tree):
    t.write(f"{RES}/values/strings.xml", '<resources><string name="present">a</string></resources>\n')
    t.write(f"{JAVA}/Use.kt", 'val t = context.getString(R.string.absent_key)\n')
    return compliance_audit.scan_string_keys(t.root)


@case("CMP005", "a burned versionCode is an error")
def _(t: Tree):
    t.write("src/android/app/build.gradle.kts",
            'android {\n  defaultConfig {\n    versionCode = 37013\n'
            '    versionName = "1.15-ozel.11.3.1"\n  }\n}\n')
    return compliance_audit.scan_version(t.root)


@case("CMP008", "applicationId drifting from the fork id is an error")
def _(t: Tree):
    t.write("src/android/app/build.gradle.kts",
            'android {\n  namespace = "com.openminis.app"\n'
            '  defaultConfig {\n    applicationId = "com.openminis.app"\n'
            '    versionCode = 37029\n    versionName = "1.15-ozel.11.3.1"\n  }\n}\n')
    return compliance_audit.scan_identity(t.root)


@case("CMP012", "download-artifact into the repo root is flagged")
def _(t: Tree):
    t.write(".github/workflows/apk.yml",
            "steps:\n  - uses: actions/upload-artifact@v4\n    with:\n"
            "      path: src/android/app/libs/rclone.aar\n"
            "  - uses: actions/download-artifact@v4\n    with:\n      path: .\n")
    return compliance_audit.scan_workflows(t.root)


@case("CMP013", "a .gitignore that forgets the keystore is an error")
def _(t: Tree):
    t.write(".gitignore", "build/\n*.log\n")
    return compliance_audit.scan_secrets_hygiene(t.root)


def main() -> int:
    failures: list[str] = []
    total = 0
    for rule, label, fn, expect in CASES:
        total += 1
        with Tree() as t:
            try:
                found = fn(t)
            except AssertionError as exc:
                failures.append(f"{rule}: {label} — assertion: {exc}")
                continue
            except Exception as exc:  # noqa: BLE001 - a crashing case is a failure
                failures.append(f"{rule}: {label} — raised {type(exc).__name__}: {exc}")
                continue
            rules = _rules(found)
            if expect == "fires":
                if rule in rules:
                    print(f"  ok    {rule}  {label}")
                else:
                    failures.append(f"{rule}: {label} — rule did not fire (got {sorted(rules)})")
            else:
                if rule in rules:
                    failures.append(f"{rule}: {label} — rule fired on correct code")
                else:
                    print(f"  ok    {rule}  {label} (negative)")

    # One negative check across the whole set: correct code must produce no ERROR.
    with Tree() as t:
        t.write(f"{JAVA}/Clean.kt",
                'package com.openminis.app\n\n'
                'fun greet(name: String) = "hello " + name.lowercase()\n')
        t.write(f"{RES}/values/strings.xml",
                '<resources><string name="app_name">Minis X</string></resources>\n')
        t.write(f"{RES}/values-tr/strings.xml",
                "<resources><string name=\"app_name\">Minis X</string></resources>\n")
        errors = [f for f in code_audit.scan_locale_case(t.root) if f.severity == "error"]
        errors += [f for f in compliance_audit.scan_locales(t.root) if f.severity == "error"]
        total += 1
        if errors:
            failures.append(f"clean tree produced errors: {[f.rule for f in errors]}")
        else:
            print("  ok    —         a clean tree produces no errors")

    print()
    if failures:
        print(f"self-test FAILED: {len(failures)} of {total} case(s)")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"self-test passed: {total} case(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
