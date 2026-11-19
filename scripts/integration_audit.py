#!/usr/bin/env python3
"""Entegrasyon denetimi — her dilimin SINIFLARI uygulamada gerçekten çağrılıyor mu?

Neden gerekli: bir sınıf + test + yeşil derleme, özelliğin ÇALIŞTIĞI anlamına
gelmez. Sınıfın üretim kodundan (test ve kendi paketi dışından) en az bir kez
çağrılması gerekir. Bu betik o farkı ölçer; "derlendi" ile "bağlandı" ayrımını
gözle değil, aramayla söyler.

Kullanım: python3 scripts/integration_audit.py
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / "src/android/app/src/main/java"
TEST = ROOT / "src/android/app/src/test/java"

SLICES = {
    "37080": ("Onay kapısı", "policy", ["RiskClassifier", "ApprovalPolicy", "ApprovalQueue", "GrantBook"]),
    "37081": ("Bütçe tavanı", "budget", ["BudgetLedger"]),
    "37082": ("Otomatik yedek", "backup", ["AutoBackupPolicy"]),
    "37083": ("Plan önizleme", "plan", ["PlanGate"]),
    "37084": ("Tur özeti", "ui/chat/diff", ["TurnDiff"]),
    "37085": ("Araç kapısı", "agent/router", ["ToolRouter"]),
    "37086": ("Ders defteri", "agent/lesson", ["LessonDetector"]),
    "37087": ("Kanıtlı bitti", "agent/evidence", ["EvidenceGate"]),
    "37088": ("Yazma niyeti", "data/journal", ["WriteIntent"]),
    "37089": ("Yeniden oynatma", "replay", ["Replayer"]),
    "37090": ("Uzaktan konsol", "remote", ["RemotePolicy"]),
}

# Bu paketler köprü katmanıdır: kendisi Android'e bağlıdır ve çağrılıp
# çağrılmadığı ayrıca denetlenir (pole göre değil, doğrudan).
BRIDGES = {
    "ozel/PolicyGate": "ChatViewModel",
    "ozel/GrantStorage": "PolicyGate",
    "ozel/AutoBackupTrigger": "MinisApp",
}


def production_files() -> list[pathlib.Path]:
    out = []
    for p in MAIN.rglob("*.kt"):
        out.append(p)
    return out


def references(name: str, files: list[pathlib.Path], exclude_pkg: str) -> list[str]:
    """Uretim kodunda `name` gecen dosyalar, kendi paketi ve testler haric."""
    hits = []
    pat = re.compile(rf"\b{re.escape(name)}\b")
    for p in files:
        rel = str(p.relative_to(MAIN))
        if rel.startswith(exclude_pkg + "/"):
            continue
        if p.name == f"{name}.kt":
            continue
        try:
            text = p.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        if pat.search(text):
            hits.append(rel)
    return hits


def main() -> int:
    files = production_files()
    rows = []
    for code, (title, pkg, classes) in SLICES.items():
        all_hits: dict[str, list[str]] = {}
        for cls in classes:
            h = references(cls, files, pkg)
            if h:
                all_hits[cls] = h
        if all_hits:
            caller = sorted({f for v in all_hits.values() for f in v})
            verdict = "BAĞLI"
        else:
            caller = []
            verdict = "BEKLİYOR"
        rows.append((code, title, pkg, verdict, caller))

    print("=== DİLİM ENTEGRASYONU (üretim kodundan çağrı) ===\n")
    width = max(len(r[1]) for r in rows) + 2
    for code, title, pkg, verdict, caller in rows:
        mark = "✅" if verdict == "BAĞLI" else "⏳"
        print(f"{mark} {code} {title.ljust(width)} {verdict}")
        for c in caller[:3]:
            print(f"      ← {c}")
        if len(caller) > 3:
            print(f"      ← … +{len(caller) - 3}")

    print("\n=== KÖPRÜ KATMANI ===")
    for bridge, expected in BRIDGES.items():
        name = bridge.split("/")[-1]
        hits = references(name, files, "ozel")
        print(f"{'✅' if hits else '⏳'} {bridge}  ({'beklenen çağıran: ' + expected})")
        for h in hits[:3]:
            print(f"      ← {h}")

    connected = sum(1 for r in rows if r[3] == "BAĞLI")
    print(f"\n{connected}/{len(rows)} dilim üretim kodundan çağrılıyor.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
