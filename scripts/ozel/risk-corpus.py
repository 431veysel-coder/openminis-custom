#!/usr/bin/env python3
"""
[37096] Risk kural tablosunun hızlı aynası — JVM olmadan davranış kontrolü.

NEDEN VAR
    Bu projede gerçek sınar Kotlin testidir: `RiskCorpusTest` sınıflandırıcının
    kendisini çalıştırır. Ama iki durumda ona ulaşılamıyor:

      1. Cihazda JDK/Gradle yok (bu telefonda JVM hiç başlamıyor: PRoot
         "Failed to mark memory page as executable" veriyor).
      2. Bir kuralı değiştirmeden önce "hangi komut hangi karta düşer"
         sorusunu görmek istiyorsun.

    Bu betik RiskRules.kt'yi ve RiskClassifier.kt'deki karar mantığını
    OKUYARAK aynalar. Kaynak Kotlin'dir; burası onun gölgesi. İkisi
    ayrışırsa doğru olan Kotlin'dir ve bu betik düzeltilmelidir — bu yüzden
    korpus beklentileri TEK dosyada (`risk-corpus.txt`) tutuluyor.

KULLANIM
    python3 scripts/ozel/risk-corpus.py            # tablo
    python3 scripts/ozel/risk-corpus.py --fail-on-mismatch
    python3 scripts/ozel/risk-corpus.py -v komut   # tek komutu açıkla
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RULES_KT = ROOT / "src/android/app/src/main/java/com/openminis/app/policy/RiskRules.kt"
CORPUS = ROOT / "src/android/app/src/test/resources/ozel/risk-corpus.txt"

SESSION = "/var/minis/minis-sessions/abc123"

SCRATCH_SUBDIRS = ("workspace", "tmp", ".cache", "offloads")
TEMP_ROOTS = ("/tmp", "/var/tmp", "/dev/shm")
USER_DATA_ROOTS = (
    "/var/minis/shared",
    "/var/minis/attachments",
    "/var/minis/memory",
    "/var/minis/mounts",
    "/var/minis/skills",
)
DEVICE_ROOTS = ("/storage", "/sdcard", "/data", "/system", "/etc", "/sdcard0")

LEVELS = ["NONE", "LOW", "MEDIUM", "HIGH"]
REACHES = ["SANDBOX", "SESSION", "DEVICE", "NETWORK", "ACCOUNT"]
RECOVERIES = ["UNDOABLE", "RECOVERABLE", "PERMANENT"]
ASK_FROM = "MEDIUM"


# --------------------------------------------------------------------------- #
# Kotlin kaynağını okuma
# --------------------------------------------------------------------------- #
def load_regexes(text: str) -> dict[str, re.Pattern]:
    """`private val AD = Regex(\"\"\"...\"\"\")` tanımlarını toplar."""
    out: dict[str, re.Pattern] = {}
    for m in re.finditer(r"private\s+val\s+(\w+)\s*=\s*Regex\(\s*\"\"\"(.*?)\"\"\"", text, re.S):
        name, pattern = m.group(1), m.group(2)
        flags = re.I if "IGNORE_CASE" in text[m.end(): m.end() + 60] else 0
        out[name] = re.compile(pattern, flags)
    return out


def split_top_level_args(body: str) -> list[str]:
    """`Rule( ... )` gövdesini üst seviye virgüllerden böler."""
    args, depth, cur, in_str, in_triple = [], 0, "", False, False
    i = 0
    while i < len(body):
        if body.startswith('"""', i) and not in_str:
            in_triple = not in_triple
            cur += '"""'
            i += 3
            continue
        ch = body[i]
        if not in_triple:
            if ch == '"':
                in_str = not in_str
            elif not in_str:
                if ch in "([{":
                    depth += 1
                elif ch in ")]}":
                    depth -= 1
                elif ch == "," and depth == 0:
                    args.append(cur.strip())
                    cur = ""
                    i += 1
                    continue
        cur += ch
        i += 1
    if cur.strip():
        args.append(cur.strip())
    return args


def parse_rules(text: str, regexes: dict[str, re.Pattern]) -> list[dict]:
    """`Rule(...)` çağrılarını, ALL ve DENY listelerinde göründükleri sırayla okur."""
    rules: list[dict] = []

    def read_group(list_name: str) -> None:
        start = text.index(f"val {list_name}: List<Rule> = listOf(")
        i = start + len(f"val {list_name}: List<Rule> = listOf(")
        depth = 1
        while i < len(text) and depth > 0:
            if text.startswith("Rule(", i):
                j = i + len("Rule(")
                d = 1
                in_triple = False
                while j < len(text) and d > 0:
                    if text.startswith('"""', j):
                        in_triple = not in_triple
                        j += 3
                        continue
                    if not in_triple:
                        if text[j] == "(":
                            d += 1
                        elif text[j] == ")":
                            d -= 1
                            if d == 0:
                                break
                    j += 1
                body = text[i + len("Rule("): j]
                rules.append(parse_single(body, list_name, regexes))
                i = j + 1
                continue
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
            i += 1

    read_group("ALL")
    read_group("DENY")
    return rules


def resolve_pattern(expr: str, regexes: dict[str, re.Pattern]) -> re.Pattern | None:
    # `DD` gibi bir ADI ya da satır içi bir Regex(...) ifadesini çözer.
    if expr.startswith("Regex("):
        m = re.search(r'"""(.*)"""', expr, re.S)
        if m:
            flags = re.I if "IGNORE_CASE" in expr else 0
            return re.compile(m.group(1), flags)
        m = re.search(r'"([^"]*)"', expr)
        if m:
            return re.compile(m.group(1))
        return None
    return regexes.get(expr)


def parse_single(body: str, group: str, regexes: dict[str, re.Pattern]) -> dict:
    args = split_top_level_args(body)
    rule = {
        "group": group,
        "id": "",
        "pattern": None,
        "level": "NONE",
        "reach": "SANDBOX",
        "recovery": "UNDOABLE",
        "note": "",
        "pathScoped": False,
    }
    for arg in args:
        if "=" not in arg:
            continue
        key, _, value = arg.partition("=")
        key, value = key.strip(), value.strip()
        if key == "id":
            rule["id"] = value.strip('"')
        elif key == "pattern":
            rule["pattern"] = resolve_pattern(value, regexes)
        elif key == "level":
            rule["level"] = value.split(".")[-1]
        elif key == "reach":
            rule["reach"] = value.split(".")[-1]
        elif key == "recovery":
            rule["recovery"] = value.split(".")[-1]
        elif key == "note":
            rule["note"] = value.strip('"')
        elif key == "pathScoped":
            rule["pathScoped"] = value == "true"

    # Konumsal biçim: Rule("id", PATTERN, RiskLevel.X, Reach.Y, Recovery.Z, "note")
    positional = [a for a in args if "=" not in a]
    if positional and len(positional) >= 6:
        rule["id"] = positional[0].strip('"')
        rule["pattern"] = resolve_pattern(positional[1], regexes)
        rule["level"] = positional[2].split(".")[-1]
        rule["reach"] = positional[3].split(".")[-1]
        rule["recovery"] = positional[4].split(".")[-1]
        rule["note"] = positional[5].strip('"')
    if rule["pattern"] is None and positional:
        rule["pattern"] = resolve_pattern(positional[1] if len(positional) > 1 else "", regexes)
    return rule


# --------------------------------------------------------------------------- #
# RiskClassifier aynası
# --------------------------------------------------------------------------- #
def normalize(path: str) -> str:
    p = path.strip()
    if p.startswith("~"):
        p = "/root" + p[1:]
    if not p.startswith("/"):
        p = f"{SESSION}/{p}"
    out: list[str] = []
    for part in p.split("/"):
        if part in ("", "."):
            continue
        if part == "..":
            if out:
                out.pop()
            continue
        out.append(part)
    return "/" + "/".join(out)


def is_scratch(path: str) -> bool:
    p = normalize(path)
    if not p.startswith(SESSION + "/"):
        return False
    rest = p[len(SESSION) + 1:]
    return any(rest == s or rest.startswith(s + "/") for s in SCRATCH_SUBDIRS)


def is_temp(path: str) -> bool:
    p = normalize(path)
    return any(p == t or p.startswith(t + "/") for t in TEMP_ROOTS)


def reach_of(path: str) -> str:
    p = normalize(path)
    if not p:
        return "DEVICE"
    if is_temp(p):
        return "SANDBOX"
    if p == SESSION or p.startswith(SESSION + "/"):
        return "SANDBOX"
    if any(p == d or p.startswith(d + "/") for d in DEVICE_ROOTS):
        return "DEVICE"
    if any(p == d or p.startswith(d + "/") for d in USER_DATA_ROOTS):
        return "SESSION"
    if p.startswith("/var/minis/"):
        return "SESSION"
    if p.startswith("/"):
        return "DEVICE"
    return "SANDBOX"


def split_segments(command: str) -> list[str]:
    """Tırnak farkında bölme — Kotlin `splitSegments` ile aynı sözleşme."""
    out: list[str] = []
    cur: list[str] = []
    quote: str | None = None
    i = 0
    while i < len(command):
        ch = command[i]
        if quote:
            cur.append(ch)
            if ch == quote:
                quote = None
        elif ch in ("'", '"'):
            quote = ch
            cur.append(ch)
        elif ch == "\\" and i + 1 < len(command):
            cur.append(ch)
            cur.append(command[i + 1])
            i += 1
        elif ch in (";", "\n", "|"):
            out.append("".join(cur).strip())
            cur = []
            if i + 1 < len(command) and command[i + 1] == "|":
                i += 1
        elif ch == "&" and i + 1 < len(command) and command[i + 1] == "&":
            out.append("".join(cur).strip())
            cur = []
            i += 1
        else:
            cur.append(ch)
        i += 1
    if "".join(cur).strip():
        out.append("".join(cur).strip())
    return [s for s in out if s]


def extract_paths(segment: str) -> list[str]:
    out: list[str] = []
    for tok in re.split(r"\s+", segment):
        tok = tok.strip("'\"`")
        if not tok or tok.startswith("-") or "://" in tok:
            continue
        looks = (
            tok.startswith("/")
            or tok.startswith("~")
            or tok.startswith("./")
            or tok.startswith("../")
            or ("/" in tok and "=" not in tok)
        )
        if not looks:
            continue
        p = normalize(tok)
        if p and p not in ("/", "/root") and p not in out:
            out.append(p)
    return out


def grant_prefix_for(path: str) -> str:
    p = normalize(path)
    if not p:
        return ""
    parts = [x for x in p.split("/") if x]
    if len(parts) < 3:
        return p
    if parts[:2] == ["var", "minis"]:
        root_depth = 2
    elif parts[0] in ("storage", "sdcard", "data", "system", "etc"):
        root_depth = 1
    else:
        root_depth = 0
    return "/" + "/".join(parts[: min(len(parts), root_depth + 2)])


def adjust_level(rule: dict, targets: list[str]) -> str:
    if not rule["pathScoped"]:
        return rule["level"]
    if not targets:
        return "MEDIUM" if rule["id"] == "shell:move" else "HIGH"
    disposable = all(is_scratch(t) or is_temp(t) for t in targets)
    if disposable:
        whole_root = any(t in TEMP_ROOTS or t == SESSION for t in targets)
        return rule["level"] if whole_root else "LOW"
    any_user_data = any(
        any(t.startswith(r) for r in USER_DATA_ROOTS) for t in targets
    )
    if any_user_data:
        return "HIGH"
    if any(REACHES.index(reach_of(t)) >= REACHES.index("DEVICE") for t in targets):
        return "HIGH"
    return rule["level"]


def classify_segment(segment: str, rules: list[dict]) -> dict | None:
    deny = [r for r in rules if r["group"] == "DENY" and r["pattern"] and r["pattern"].search(segment)]
    if deny:
        r = deny[0]
        return {
            "level": "HIGH",
            "reach": r["reach"],
            "recovery": r["recovery"],
            "ruleId": r["id"],
            "targetPrefix": "",
            "segment": segment,
        }

    match = next(
        (r for r in rules if r["group"] == "ALL" and r["pattern"] and r["pattern"].search(segment)),
        None,
    )
    if match is None:
        return None

    targets = extract_paths(segment) if match["pathScoped"] else []
    if match["pathScoped"]:
        reach = max((reach_of(t) for t in targets), key=REACHES.index) if targets else "DEVICE"
    else:
        reach = match["reach"]
    level = adjust_level(match, targets)
    worst = max(targets, key=lambda t: REACHES.index(reach_of(t))) if targets else ""
    return {
        "level": level,
        "reach": reach,
        "recovery": match["recovery"],
        "ruleId": match["id"],
        "targetPrefix": grant_prefix_for(worst),
        "segment": segment,
    }


def rank(v: dict) -> int:
    return (
        LEVELS.index(v["level"]) * 100
        + RECOVERIES.index(v["recovery"]) * 10
        + REACHES.index(v["reach"])
    )


def classify_shell(command: str, rules: list[dict]) -> dict:
    verdicts = [v for v in (classify_segment(s, rules) for s in split_segments(command)) if v]
    if not verdicts:
        return {
            "level": "NONE",
            "reach": "SANDBOX",
            "recovery": "UNDOABLE",
            "ruleId": "none",
            "targetPrefix": "",
            "segment": "",
        }
    return max(verdicts, key=rank)


def decide(verdict: dict) -> str:
    if verdict["ruleId"].startswith("deny:"):
        return "DENY"
    if LEVELS.index(verdict["level"]) < LEVELS.index(ASK_FROM):
        return "SILENT"
    return "ASK"


# --------------------------------------------------------------------------- #
def main() -> int:
    ap = argparse.ArgumentParser(description="Risk kural tablosunun aynası")
    ap.add_argument("--fail-on-mismatch", action="store_true")
    ap.add_argument("-v", "--explain", metavar="KOMUT", help="tek komutu açıkla")
    args = ap.parse_args()

    text = RULES_KT.read_text(encoding="utf-8")
    regexes = load_regexes(text)
    rules = parse_rules(text, regexes)
    if not rules:
        print("HATA: RiskRules.kt içinde hiç kural okunamadı", file=sys.stderr)
        return 2

    if args.explain:
        verdict = classify_shell(args.explain, rules)
        for seg in split_segments(args.explain):
            v = classify_segment(seg, rules)
            print(f"parça: {seg!r}")
            if v:
                print(
                    f"   kural={v['ruleId']} seviye={v['level']} reach={v['reach']} "
                    f"geri={v['recovery']} hedef={v['targetPrefix'] or '-'}"
                )
            else:
                print("   kural yok")
        print(f"SONUÇ: {decide(verdict)}  (en ağır parça: {verdict['ruleId']})")
        return 0

    mismatches = []
    checked = 0
    print(f"{'KARAR':7} {'BEKLENEN':8} {'SEVİYE':7} KURAL              KOMUT")
    print("-" * 110)
    for raw in CORPUS.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|||")]
        if len(parts) < 2:
            mismatches.append((line, "BOZUK", "-", "-"))
            continue
        command, expected = parts[0], parts[1]
        verdict = classify_shell(command, rules)
        actual = decide(verdict)
        checked += 1
        mark = "" if actual == expected else "  <-- SAPMA"
        print(
            f"{actual:7} {expected:8} {verdict['level']:7} {verdict['ruleId']:18} "
            f"{command[:52]}{mark}"
        )
        if actual != expected:
            mismatches.append((command, actual, expected, verdict["ruleId"]))

    print("-" * 110)
    print(f"{checked} komut · {len(mismatches)} sapma · {len(rules)} kural okundu")

    if mismatches:
        print("\nSAPMALAR")
        for cmd, actual, expected, rule in mismatches:
            print(f"  · {cmd}\n      bulunan={actual} beklenen={expected} kural={rule}")
        if args.fail_on_mismatch:
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
