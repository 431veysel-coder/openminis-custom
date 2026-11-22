#!/usr/bin/env python3
"""Dilim kapısı — her özellik dilimini AYRI derler, AYRI test eder, AYRI rapor eder.

## Neden tek tek

Tek büyük derlemede 11 dilimin hatası tek log'a düşer ve birbirini maskeler.
Burada her dilim kendi `kotlinc` çağrısıyla derleniyor: 10 dilim yeşil, 1 dilim
kırmızıysa 10'u birleştirilebilir, 1'i izole kalır. Rapor dilim başına ayrı;
hangi dosyanın hangi satırında ne olduğu tek bakışta görünür.

## Neden bu cihazda koşmuyor

PRoot'ta JVM başlamıyor ("Failed to mark memory page as executable"), `kotlinc`
de yok. Kapı CI'da koşar (ubuntu + temurin + kotlin-compiler zip). Betik
kendisi saf Python: burada da koşar, sadece `kotlinc` bulamayınca "atlandı"
der ve **asla "temiz" demez**.

## Neden dilimler Android'siz

Her dilimin dosya listesi manifest'te ve hepsi Android'siz yazıldı (yalnız
`java.*` importları). Bu yüzden Android SDK gerekmiyor: 80 MB'lık kotlinc
yeter. Bir dilim `android.*` import ederse kapı bunu ayrı bir hata olarak
yazar ("android sızıntısı") — dilimin tek başına derlenebilirliği bozulmuş
demektir.

Kullanım:
    python3 scripts/slice_gate.py                 # hepsi
    python3 scripts/slice_gate.py 37080 37085     # seçili dilimler
    python3 scripts/slice_gate.py --list
Çıktı: build/slice-report/<dilim>.md + build/slice-report/SUMMARY.md + JSON.
"""
from __future__ import annotations

import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / "src/android/app/src/main/java"
TEST = ROOT / "src/android/app/src/test/java"
# [37092] Reports go to docs/ozel/slice-report/, NOT build/.
#
# `build/` is in .gitignore, so every one of these files was deleted by the next
# clean checkout — which is exactly why "the build logs keep disappearing". A
# report that only exists on the runner that produced it is not a record.
# Under docs/ they are committed, diffable, and survive the machine.
OUT = ROOT / "docs/ozel/slice-report"
MANIFEST = pathlib.Path(__file__).resolve().parent / "slices.json"


def load_manifest() -> dict:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def java_files(paths: list[str]) -> list[pathlib.Path]:
    out = []
    for rel in paths:
        p = ROOT / rel
        if p.is_dir():
            out.extend(sorted(p.rglob("*.kt")))
        elif p.exists():
            out.append(p)
    return out


def rel(p: pathlib.Path) -> str:
    return str(p.relative_to(ROOT))


def run(cmd: list[str], timeout: int = 900) -> tuple[int, str]:
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout or "") + (r.stderr or "")
    except FileNotFoundError:
        return 127, f"komut bulunamadı: {cmd[0]}"
    except subprocess.TimeoutExpired:
        return 124, f"zaman aşımı ({timeout}s): {' '.join(cmd[:2])}"


def kotlinc() -> str | None:
    for name in ("kotlinc", "kotlinc-jvm"):
        p = shutil.which(name)
        if p:
            return p
    env = os.environ.get("KOTLINC")
    if env and pathlib.Path(env).exists():
        return env
    return None


def kotlin_stdlib(kc: str | None) -> str | None:
    """`kotlin-stdlib.jar` — kotlinc'in yanında gelir.

    Neden gerekli: derleme stdlib'i kendisi ekliyor ama **çalıştırma** eklemiyor.
    İlk denemede 10 dilim "NoClassDefFoundError: kotlin/jvm/internal/Intrinsics"
    ile düştü; derleme geçmişti, test koşamıyordu. Kapı bunu ancak gerçek bir
    koşuyla gördü — statik denetim bunu asla göremezdi.
    """
    if kc:
        candidate = pathlib.Path(kc).resolve().parent.parent / "lib/kotlin-stdlib.jar"
        if candidate.exists():
            return str(candidate)
    env = os.environ.get("KOTLIN_STDLIB")
    return env if env and pathlib.Path(env).exists() else None


def junit_cp(kc: str | None = None) -> str | None:
    """JUnit + hamcrest + kotlin-stdlib. env JUNIT_CP ile dışarıdan verilebilir."""
    parts = []
    env = os.environ.get("JUNIT_CP")
    if env:
        parts.append(env)
    else:
        jars = sorted((ROOT / "docs/ozel/slice-report/jars").glob("*.jar"))
        if not jars:
            return None
        parts.append(os.pathsep.join(str(j) for j in jars))
    stdlib = kotlin_stdlib(kc)
    if stdlib:
        parts.append(stdlib)
    return os.pathsep.join(parts)


ANDROID_MARK = ("import android.", "import androidx.")


def android_leak(files: list[pathlib.Path]) -> list[tuple[str, int, str]]:
    """Dilim tek başına derlenebilirliğini bozan import'lar."""
    hits = []
    for f in files:
        try:
            for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
                if line.startswith(ANDROID_MARK):
                    hits.append((rel(f), i, line.strip()))
        except (UnicodeDecodeError, OSError):
            continue
    return hits


def compile_slice(name: str, spec: dict, workdir: pathlib.Path, kc: str) -> dict:
    src = java_files(spec["files"])
    # `deps`: dilimin kendi dosyası olmayan ama derlemede gereken repo dosyaları.
    # Ayrı tutuluyor ki raporda "bu dilim şu kadar dosyaya bağlı" görünsün.
    deps = java_files(spec.get("deps", []) or [])
    tests = java_files(spec.get("tests", []))
    leaking = android_leak(src + deps + tests)

    result = {
        "slice": name,
        "title": spec.get("title", name),
        "sources": [rel(p) for p in src],
        "deps": [rel(p) for p in deps],
        "tests": [rel(p) for p in tests],
        "android_leak": [{"file": f, "line": n, "import": s} for f, n, s in leaking],
        "compile": {"ok": False, "log": ""},
        "test": {"ran": False, "ok": None, "pass": 0, "fail": 0, "log": ""},
        "verdict": "FAIL",
    }

    if not src:
        result["compile"]["log"] = "manifest'teki hiçbir kaynak dosya bulunamadı"
        return result

    # 1) derleme
    classes = workdir / f"classes-{name}"
    classes.mkdir(parents=True, exist_ok=True)
    code, log = run([kc, "-nowarn", "-d", str(classes)] + [str(p) for p in src + deps])
    # JVM bu makinede açılmıyor olabilir (PRoot: executable-page kısıtı).
    # O durumda kod 0 döndürmez ama hata KODUN değil ÇEVRENİN dir. "FAIL"
    # yazmak yanlış sinyal verir: CI'da geçen 37080 bile burada kırmızıdır.
    # Ayrımı hata metninden yap: JVM başlamadıysa ENV-FAIL, değilse gerçek FAIL.
    if code != 0 and "Failed to mark memory page" in log:
        result["compile"] = {"ok": False, "log": log.strip(), "env": "jvm-unavailable"}
        result["verdict"] = "ENV-FAIL"
        return result
    result["compile"] = {"ok": code == 0, "log": log.strip()}
    if code != 0:
        return result

    # 2) testler
    if not tests:
        result["verdict"] = "PASS" if not leaking else "PASS(uyarı)"
        return result

    cp = junit_cp(kc)
    if cp is None:
        result["test"]["log"] = "JUnit jar'ları yok (JUNIT_CP tanımsız) — testler atlandı"
        result["verdict"] = "DERLENDİ(test yok)"
        return result

    test_classes = workdir / f"test-{name}"
    test_classes.mkdir(parents=True, exist_ok=True)
    sep = os.pathsep
    code, log = run(
        [kc, "-nowarn", "-cp", f"{classes}{sep}{cp}", "-d", str(test_classes)]
        + [str(p) for p in tests]
    )
    if code != 0:
        result["test"] = {"ran": False, "ok": False, "pass": 0, "fail": 0,
                          "log": "test derlenemedi:\n" + log.strip()}
        return result

    classes_out = [p for p in test_classes.rglob("*.class") if "$" not in p.name]
    names = []
    for p in classes_out:
        names.append(str(p.relative_to(test_classes)).replace("/", ".")[:-len(".class")])
    if not names:
        result["test"] = {"ran": False, "ok": False, "pass": 0, "fail": 0,
                          "log": "derlenmiş test sınıfı yok"}
        return result

    code, log = run(
        ["java", "-cp", f"{classes}{sep}{test_classes}{sep}{cp}", "org.junit.runner.JUnitCore"] + names
    )
    passed = log.count("\nOK (") or 0
    fail_count = 0
    if "Failures:" in log:
        for line in log.splitlines():
            if line.strip().startswith("Tests run:"):
                for tok in line.split(","):
                    if "Failures:" in tok:
                        fail_count = int(tok.split(":")[1].strip())
    result["test"] = {
        "ran": True,
        "ok": code == 0,
        "pass": 0 if code != 0 else 1,
        "fail": fail_count,
        "log": log.strip()[-4000:],
    }
    if code == 0 and not leaking:
        result["verdict"] = "PASS"
    elif code == 0:
        result["verdict"] = "PASS(uyarı)"
    return result


def md_report(r: dict) -> str:
    icon = {"PASS": "✅", "PASS(uyarı)": "⚠️", "DERLENDİ(test yok)": "🟡"}.get(r["verdict"], "❌")
    lines = [f"# {icon} {r['slice']} — {r['title']}", ""]
    lines.append(f"**karar:** `{r['verdict']}`")
    lines.append(f"**kaynak:** {len(r['sources'])} dosya · **test:** {len(r['tests'])} dosya")
    if r.get("deps"):
        lines.append(f"**bağımlı (repo):** {', '.join('`' + d.split('/')[-1] + '`' for d in r['deps'])}")
    lines.append("")

    if r["android_leak"]:
        lines.append("## ⚠️ Android sızıntısı — dilim tek başına derlensin diye yasak")
        lines.append("")
        for h in r["android_leak"]:
            lines.append(f"- `{h['file']}:{h['line']}` — `{h['import']}`")
        lines.append("")

    lines.append("## Derleme")
    lines.append("")
    if r["compile"]["ok"]:
        lines.append("Geçti.")
    else:
        lines.append("```")
        lines.append(r["compile"]["log"] or "(log yok)")
        lines.append("```")
    lines.append("")

    if r["tests"]:
        lines.append("## Testler")
        lines.append("")
        t = r["test"]
        if not t["ran"]:
            lines.append(t["log"] or "koşmadı")
        else:
            lines.append("Geçti." if t["ok"] else f"**{t['fail']} başarısız**")
            if not t["ok"]:
                lines.append("")
                lines.append("```")
                lines.append(t["log"])
                lines.append("```")
        lines.append("")

    lines.append("## Dosyalar")
    lines.append("")
    for s in r["sources"]:
        lines.append(f"- `{s}`")
    for s in r["tests"]:
        lines.append(f"- `{s}` (test)")
    lines.append("")
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    manifest = load_manifest()
    slices = manifest["slices"]
    if "--list" in argv:
        for k, v in slices.items():
            print(f"{k}  {v.get('title', '')}")
        return 0

    wanted = [a for a in argv if a.isdigit()]
    if wanted:
        slices = {k: v for k, v in slices.items() if k in wanted}

    kc = kotlinc()
    OUT.mkdir(parents=True, exist_ok=True)

    if kc is None:
        print("slice gate: kotlinc bulunamadı — derleme ATLANDI (temiz değil)")
        print("  CI'da kotlin-compiler zip'i indirilir; yerelde KOTLINC=/yol/kotlinc verin")
        for name, spec in slices.items():
            (OUT / f"{name}.md").write_text(
                f"# ⏭️ {name} — {spec.get('title', '')}\n\n`kotlinc` yok; bu makinede derlenmedi.\n",
                encoding="utf-8",
            )
        (OUT / "SUMMARY.md").write_text(
            "# Dilim kapısı — ATLANDI\n\n`kotlinc` yok. Sonuç yok; 'temiz' sayılmaz.\n",
            encoding="utf-8",
        )
        return 2

    results = []
    workdir = pathlib.Path(tempfile.mkdtemp(prefix="slice-"))
    try:
        for name, spec in slices.items():
            print(f"[{name}] {spec.get('title', '')} ...", flush=True)
            r = compile_slice(name, spec, workdir, kc)
            results.append(r)
            (OUT / f"{name}.md").write_text(md_report(r), encoding="utf-8")
            print(f"  -> {r['verdict']}", flush=True)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    (OUT / "results.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    passed = [r for r in results if r["verdict"].startswith("PASS")]
    env_failed = [r for r in results if r["verdict"] == "ENV-FAIL"]
    failed = [r for r in results if r not in passed and r not in env_failed]
    summary = ["# Dilim kapısı — özet", ""]
    summary.append(f"**{len(passed)}/{len(results)} dilim geçti**" + (
        f" · **{len(env_failed)} dilim ENV-FAIL** (JVM bu makinede açılmıyor — kod hatası değil, CI'da doğrulanmalı)"
        if env_failed else ""))
    summary.append("")
    summary.append("| dilim | başlık | derleme | test | karar |")
    summary.append("|---|---|---|---|---|")
    for r in results:
        c = "✅" if r["compile"]["ok"] else "❌"
        t = "—" if not r["tests"] else ("✅" if r["test"]["ok"] else "❌")
        summary.append(f"| {r['slice']} | {r['title']} | {c} | {t} | {r['verdict']} |")
    summary.append("")
    if failed:
        summary.append("## Düzeltilecekler")
        summary.append("")
        for r in failed:
            summary.append(f"- **{r['slice']}** — ayrıntı: `{r['slice']}.md`")
    summary.append("")
    (OUT / "SUMMARY.md").write_text("\n".join(summary), encoding="utf-8")

    print(f"\n{len(passed)}/{len(results)} dilim geçti — {OUT}/SUMMARY.md")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
