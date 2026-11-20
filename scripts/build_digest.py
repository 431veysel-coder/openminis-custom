#!/usr/bin/env python3
"""Derleme özetini kalıcı bir dosyaya yazar.

## Neden

İki ayrı yerden log kaybediyoruz:

1. **Sohbet bağlamı.** Uzun bir oturumda 40 dakikalık build log'u ikinci
   oturumda yok. Bir sonraki oturum aynı hatayı yeniden keşfeder.
2. **GitHub saklama.** Run log'ları ve artifact'lar süresi dolunca silinir
   (log 90 gün, artifact 7-30 gün). Bir ay sonra "bu hata daha önce çıkmış
   mıydı" sorusunun cevabı yok.

Bu betik ikisini de çözer: son koşuların **başarısız adımlarını** ve hata
satırlarını çıkarıp repoda bir Markdown dosyasına ekler. Repo kalıcı, git
geçmişi kalıcı, herkes okuyabilir.

Kullanım:
    python3 scripts/build_digest.py            # son 15 koşu
    python3 scripts/build_digest.py --limit 40
    python3 scripts/build_digest.py --branch main
"""
from __future__ import annotations

import argparse
import datetime
import json
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/ozel/BUILD-GECMISI.md"
ERROR_PAT = re.compile(r"(e: file|error:|Error:|FAILED|Exception|AssertionError|^e: )")


def gh(args: list[str]) -> str:
    r = subprocess.run(["gh"] + args, capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else ""


def runs(limit: int, branch: str | None) -> list[dict]:
    args = ["run", "list", "--limit", str(limit), "--json",
            "databaseId,displayTitle,status,conclusion,workflowName,headBranch,createdAt"]
    if branch:
        args += ["--branch", branch]
    out = gh(args)
    return json.loads(out) if out.strip() else []


def failed_log(run_id: int) -> list[str]:
    """Yalnız başarısız adımın log'u; hata satırları süzülür."""
    raw = gh(["run", "view", str(run_id), "--log-failed"])
    lines = []
    for line in raw.splitlines():
        # `job\tstep\t2026-...Z  actual text` → son parçayı al
        m = re.split(r"\t\d{4}-\d\d-\d\dT[\d:.]+Z\s?", line, maxsplit=1)
        text = (m[1] if len(m) > 1 else line).strip()
        if not text:
            continue
        if ERROR_PAT.search(text) or text.startswith(("e: ", "##[error]")):
            lines.append(text[:400])
        if len(lines) >= 12:
            break
    return lines


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=15)
    ap.add_argument("--branch", default=None)
    args = ap.parse_args()

    rs = runs(args.limit, args.branch)
    if not rs:
        print("koşu bulunamadı (gh kurulu mu, yetki var mı?)")
        return 1

    stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    blocks = [f"\n## {stamp} — son {len(rs)} koşu\n"]

    bad = [r for r in rs if r.get("conclusion") not in ("success", "skipped", None, "")]
    if not bad:
        blocks.append("Tüm koşular başarılı. Hata kaydı yok.\n")
    for r in bad:
        n = (r.get("displayTitle") or "")[:70]
        blocks.append(
            f"### ❌ {r['workflowName']} · {r['conclusion']} · "
            f"{r['createdAt'][:16].replace('T', ' ')} · `{r['headBranch']}`\n\n"
            f"{n}\n\n"
        )
        errs = failed_log(r["databaseId"])
        if errs:
            blocks.append("```\n" + "\n".join(errs) + "\n```\n\n")
        else:
            blocks.append(f"_Hata satırı çıkarılamadı — run {r['databaseId']}._\n\n")

    header = (
        "# DERLEME GEÇMİŞİ\n\n"
        "`python3 scripts/build_digest.py` ile üretilir. **Elle düzenlemeyin.**\n\n"
        "Amaç: build hatalarını sohbet bağlamından ve GitHub'ın saklama süresinden\n"
        "bağımsız tutmak. Bir hata tekrar çıkarsa burada aranır.\n"
    )
    if not OUT.exists():
        OUT.write_text(header, encoding="utf-8")
    with OUT.open("a", encoding="utf-8") as f:
        f.write("".join(blocks))

    print(f"yazıldı: {OUT.relative_to(ROOT)} ({len(bad)} başarısız koşu)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
