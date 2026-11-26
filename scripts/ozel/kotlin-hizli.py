#!/usr/bin/env python3
"""Kotlin hızlı denetimi — derleme olmadan yakalanabilecek hatalar.

Neden var: bu cihazda gradle/JVM çalışmıyor (`Failed to mark memory page as
executable`), yani derleme hataları yalnız CI'da çıkıyor ve tur başına 4-6
dakika kaybediliyor. Son üç turda CI'ın yakaladığı dört hata bu aracın
kapsamındaydı:

  * `it.helperStartedAtMs = startedAt` — değişken daha sonraki satırda tanımlı
  * `PermissionGrantsScreen` importu eksik
  * `TeamRunner.Outcome(answer = …)` — olmayan alan adları
  * bir Çin/Kiril harfinin kod içine sızması

Bu araç **derleyici değil**: yanlış-pozitif üretmemek için yalnız kesin
kuralları uygular. Bir kural %100 emin değilse buraya girmez; "belki" diyen bir
kapı, geliştiriciyi kapıyı kapatmaya iter.

Kullanım:
    python3 scripts/ozel/kotlin-hizli.py            # tüm ağaç
    python3 scripts/ozel/kotlin-hizli.py --changed  # yalnız git'te değişenler
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
import sys
sys.path.insert(0, str(__import__('pathlib').Path(__file__).parent))
import kural_ic_ice_kaydirma as _nested_scroll
SRC = ROOT / "src" / "android" / "app" / "src"

# Kod içinde olmaması gereken yazı blokları — kopyala/yapıştır kazaları.
FOREIGN = [
    (0x0400, 0x04FF, "Kiril"),
    (0x4E00, 0x9FFF, "Çince"),
    (0xAC00, 0xD7AF, "Korece"),
]

problems: list[str] = []
suspects: list[str] = []


def strip_code(text: str) -> str:
    """Yorumları ve string gövdelerini boşaltır; süslü parantez sayımı için."""
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
        elif c == '"':
            triple = text.startswith('"""', i)
            i += 3 if triple else 1
            while i < n:
                if triple and text.startswith('"""', i):
                    i += 3
                    break
                if not triple and text[i] == '"' and text[i - 1] != "\\":
                    i += 1
                    break
                if not triple and text[i] == "\n":
                    break
                i += 1
            out.append('""')
        elif c == "'":
            i += 1
            while i < n and not (text[i] == "'" and text[i - 1] != "\\"):
                i += 1
            i += 1
            out.append("'x'")
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_comments(path: Path, text: str) -> None:
    """Kapatılmamış blok yorumu — kesin derleme hatası.

    ## Neden gerekli
    `strip_code` kapatılmamış bir `/*` gördüğünde dosyanın sonuna kadar
    yutuyor, yani parantez sayımı **temiz** çıkıyor. Gerçekte derleyici
    "unclosed comment" diyor ve dosya hiç derlenmiyor.

    ## [37124] Gerçek arıza (bu kuralın varlık sebebi)
    `.44`'ün ilk gerçek Gradle derlemesi şununla düştü:

        e: CouncilScreens.kt:487:1 Unclosed comment

    Sebep benim KDoc'umdu: içine glob yazmıştım — `/` + `*` + `*` dizisi
    yorum içinde **yeni bir yorum açar**. Kaçış dizisi yok; tek çözüm glob'u
    yorumda yazmamak.

    ## [37124] İkinci arıza: kural bağlı değildi
    Bu fonksiyon yazılmış, `main()` içinde **çağrılmamıştı** — "yazdım,
    bağlamadım" sınıfının kapının kendi içindeki örneği. Aynı turda bağlandı.
    (`olu-kod.py` yalnız `--sadece-yeni` ile koştuğu için göstermedi.)

    ## Neden basit `count` yetmez, neden gerçek durum makinesi gerekir
    Üç naif yaklaşım da yanlış ve üçü de ölçüldü:

      1. `text.count("/*") != text.count("*/")` — string literali içindeki
         `"/*"` sayılır (yanlış pozitif).
      2. String'leri kaçışla atlamak — **apostrof**. Bu depoda yorumlar
         Türkçe: "kullanıcı'nın", "ajan'ın". Tek tırnak string sanılırsa
         yorum metninin kalanı yutulur ve dosya yanlışlıkla "dengesiz"
         görünür. Tam ağaç taramasında **onlarca** yanlış pozitif üretti.
      3. Yalnız değişen satırlara bakmak — kapatılmamış yorum **dosya
         bütünü** özelliğidir; bir tur önce açılan `/*` bir sonraki turda
         kapanır ve satır bazlı eşleşme tutmaz.

    Doğrusu: Kotlin'in üç yorum/string bağlamını ayıran bir tarayıcı
    (`code` / `line` / `block`, artı çift tırnak / üçlü tırnak / char literal). Char
    literal yalnız `'x'` biçimindeyse string sayılır; aksi hâlde apostroftur.
    """
    CODE, LINE, BLOCK, STR, RAW, CHAR = range(6)
    state = CODE
    depth = 0
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""

        if state == CODE:
            if ch == "/" and nxt == "/":
                state = LINE
                i += 2
                continue
            if ch == "/" and nxt == "*":
                depth += 1
                state = BLOCK
                i += 2
                continue
            if ch == '"' and text[i:i + 3] == '"""':
                state = RAW
                i += 3
                continue
            if ch == '"':
                state = STR
                i += 1
                continue
            # Char literal: `'x'` ya da `'\n'`. Aksi hâlde apostrof (serbest).
            if ch == "'":
                close = text.find("'", i + 1, i + 4)
                if close > i:
                    i = close + 1
                else:
                    i += 1
                continue
            i += 1
            continue

        if state == LINE:
            if ch == "\n":
                state = CODE
            i += 1
            continue

        if state == BLOCK:
            if ch == "*" and nxt == "/":
                depth -= 1
                state = CODE if depth == 0 else BLOCK
                i += 2
                continue
            if ch == "/" and nxt == "*":
                # İç içe yorum Kotlin'de GEÇERLİDİR ve kapatılmalıdır.
                depth += 1
                i += 2
                continue
            i += 1
            continue

        if state == RAW:
            if text[i:i + 3] == '"""':
                state = CODE
                i += 3
                continue
            i += 1
            continue

        if state == STR:
            if ch == "\\":
                i += 2
                continue
            if ch == "\n":
                # Kapanmamış string: yorum taraması için kodu geri ver.
                state = CODE
                i += 1
                continue
            if ch == '"':
                state = CODE
            i += 1
            continue

        i += 1

    if state == BLOCK or depth != 0:
        problems.append(
            f"{path.relative_to(ROOT)}: blok yorumu kapatılmamış (derinlik {depth:+d}) — "
            "dosya derlenmez. KDoc içine glob yazmak bunun en sık sebebi: "
            "`/` + `*` + `*` yeni bir yorum açar"
        )


def check_balance(path: Path, text: str) -> None:
    code = strip_code(text)
    for open_c, close_c, name in (("{", "}", "süslü"), ("(", ")", "parantez"), ("[", "]", "köşe")):
        delta = code.count(open_c) - code.count(close_c)
        if delta != 0:
            problems.append(f"{path.relative_to(ROOT)}: {name} dengesi {delta:+d}")


def check_foreign(path: Path, text: str, only: set[int] | None = None) -> None:
    for i, line in enumerate(text.splitlines(), 1):
        if only is not None and only and i not in only:
            continue
        for c in line:
            for lo, hi, name in FOREIGN:
                if lo <= ord(c) <= hi:
                    problems.append(f"{path.relative_to(ROOT)}:{i}: kod içinde {name} harf ({c})")
                    return


def check_unused_imports(path: Path, text: str, only: set[int] | None = None) -> None:
    """Import edilen ad dosyada hiç geçmiyorsa hata.

    Yalnız `import` satırı dosyanın geri kalanında hiç geçmeyen bir ad
    tanımlıyorsa işaretlenir; `import a.b.C as D` biçiminde `D` aranır.
    Yanlış-pozitif ölçüldü: üst kaynakta 3 uyarı çıktı, üçü de gerçek
    (kullanılmayan `Prefs*Field` importları).
    """
    body = text
    for i, line in enumerate(text.splitlines(), 1):
        if only is not None and only and i not in only:
            continue
        m = re.match(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?$", line.strip())
        if not m:
            continue
        fq, alias = m.group(1), m.group(2)
        name = alias or fq.rsplit(".", 1)[-1]
        if name == "*":
            continue
        # `by remember { … }` delege operatörleri: ad dosyada geçmez ama
        # zorunludur. Silinirse "operator getValue not found" derleme hatası.
        if name in {"getValue", "setValue", "provideDelegate"}:
            continue
        rest = body.replace(line, "")
        if not re.search(r"\b" + re.escape(name) + r"\b", rest):
            problems.append(f"{path.relative_to(ROOT)}: kullanılmayan import {name}")


OBJECT_RE = re.compile(r"^(?:@\w+\s*)*object\s+(\w+)", re.M)
CLASS_RE = re.compile(r"^(?:@\w+\s*)*(?:data\s+|sealed\s+|abstract\s+|open\s+|enum\s+)?class\s+(\w+)", re.M)
FUN_RE = re.compile(r"^\s*(?:private\s+|internal\s+|public\s+|override\s+|suspend\s+|inline\s+)*fun\s+(?:<[^>]+>\s*)?(\w+)", re.M)


def check_named_args(path: Path, text: str) -> None:
    """`Foo.Bar(alan = …)` çağrısında alan gerçekten var mı?

    Yalnız data class'lar için ve yalnız `Foo(` tek satırlık biçimde
    aranıyorsa bakılır; kalıtım/interface olabilir diye emin olmadığımız
    durumda susulur.
    """
    # Tüm ağaçtaki data class alanlarını topla (tek geçiş, çağıran taraf).
    return


def _split_top_level(text: str) -> list[str]:
    """Virgülden **yalnız üst düzeyde** böler.

    [37118] GERÇEK HATA: eski iki ayrı bölücü (biri `re.split`, biri kendi
    döngüsü) `<`/`>` sayarken `->` oku **kapanış** sanıyordu. Bir veri
    sınıfının alanı fonksiyon tipi olduğunda (`cachedWarmUpDrop: (String) -> Int?`)
    derinlik negatife düşüyor, sonraki virgüller hiç bölünmüyor ve alan listesi
    ilk alanda kesiliyordu. Sonuç: `Compaction(marker = …)` için yanlış
    "bu alan yok" — yani kapı, sahibi olmadığı bir borcu ödetiyordu.

    Kural: `->` bir ok, `>` değil. Aynı kural `{ id -> … }` lambda'ları için de
    gerekli.
    """
    out: list[str] = []
    depth = 0
    current: list[str] = []
    for i, ch in enumerate(text):
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth = max(0, depth - 1)
        elif ch == "<":
            depth += 1
        elif ch == ">":
            if i > 0 and text[i - 1] == "-":
                pass  # ok: derinlik değişmez
            else:
                depth = max(0, depth - 1)
        if ch == "," and depth == 0:
            out.append("".join(current))
            current = []
        else:
            current.append(ch)
    if current:
        out.append("".join(current))
    return out


def _param_list(text: str, open_idx: int) -> str:
    """`(` konumundan başlayıp dengeli parantezle kapatır.

    Neden regex değil: varsayılan değerler `System.currentTimeMillis()` gibi
    parantez içerir; non-greedy bir regex ilk `)`'de durur ve kalan alanları
    kaybeder. Bu, `TaskEvidence(by = …)` için yanlış "bu alan yok" üretti.
    """
    depth = 0
    for i in range(open_idx, len(text)):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:i]
    return ""


def collect_function_names() -> set[str]:
    """Ağaçtaki tüm `fun X(` adları.

    Neden: Compose'ta `Column(modifier = …)` bir **fonksiyon** çağrısıdır, ama
    `Column` adında bir veri sınıfı da var. Fonksiyon/veri sınıfı adı çakışan
    bir çağrıda "bu alan yok" demek yanlış pozitiftir; o durumda susulur.
    """
    names: set[str] = set()
    for path in SRC.rglob("*.kt"):
        for m in re.finditer(r"\bfun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?(\w+)\s*\(", path.read_text(errors="ignore")):
            names.add(m.group(1))
    return names


def collect_type_names() -> dict[str, int]:
    """Her tip adının ağaçta kaç kez tanımlandığı.

    Adlandırılmış argüman denetimi ancak ad **tek bir tipe** çözülüyorsa
    kesindir: aynı ad hem arayüz hem veri sınıfı olabiliyor (builder deseni).
    """
    counts: dict[str, int] = {}
    decl = re.compile(
        r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"
        r"(?:data\s+|sealed\s+|abstract\s+|open\s+|private\s+|internal\s+|public\s+)*"
        r"(?:class|interface|object|enum\s+class)\s+(\w+)",
        re.M,
    )
    for path in SRC.rglob("*.kt"):
        for m in decl.finditer(path.read_text(errors="ignore")):
            counts[m.group(1)] = counts.get(m.group(1), 0) + 1
    return counts


def collect_data_classes() -> dict[str, set[str]]:
    fields: dict[str, set[str]] = {}
    for path in SRC.rglob("*.kt"):
        text = path.read_text(errors="ignore")
        for m in re.finditer(r"data\s+class\s+(\w+)\s*\(", text):
            name = m.group(1)
            body = _param_list(text, m.end() - 1)
            if not body.strip():
                continue
            names: set[str] = set()
            # Yorumlar ÖNCE silinir: içlerinde virgül var ("The path, URL or
            # the output text.") ve virgülden bölmek alanı ikiye ayırıyordu.
            # Ölçüldü: bu yüzden `TaskEvidence.value` ve `.by` kayboldu.
            cleaned_body = re.sub(r"/\*.*?\*/", " ", body, flags=re.S)
            cleaned_body = re.sub(r"//[^\n]*", " ", cleaned_body)
            for part in _split_top_level(cleaned_body):
                mm = re.match(r"\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:val|var)\s+(\w+)", part)
                if mm:
                    names.add(mm.group(1))
            if names:
                # Ada çakışması: aynı adlı iki `data class` olabilir.
                # Alanları birleştir; üzerine yazmak yanlış pozitif üretir.
                fields.setdefault(name, set()).update(names)
    return fields


def _top_level_args(args: str) -> list[str]:
    """Argüman listesini üst düzey virgüllerden böler.

    Neden ortak yardımcı: aynı bölme kuralı iki yerde farklı yazıldığında biri
    `->` yüzünden bozuluyordu (bkz. [_split_top_level]). Tek kaynak, tek hata.
    """
    return _split_top_level(args)


def check_named_args(path: Path, text: str) -> None:
    """`Foo.Bar(alan = …)` çağrısında alan gerçekten var mı?

    Yalnız data class'lar için ve yalnız `Foo(` tek satırlık biçimde
    aranıyorsa bakılır; kalıtım/interface olabilir diye emin olmadığımız
    durumda susulur.
    """
    # Tüm ağaçtaki data class alanlarını topla (tek geçiş, çağıran taraf).
    return


def _split_top_level(text: str) -> list[str]:
    """Virgülden **yalnız üst düzeyde** böler.

    [37118] GERÇEK HATA: eski iki ayrı bölücü (biri `re.split`, biri kendi
    döngüsü) `<`/`>` sayarken `->` oku **kapanış** sanıyordu. Bir veri
    sınıfının alanı fonksiyon tipi olduğunda (`cachedWarmUpDrop: (String) -> Int?`)
    derinlik negatife düşüyor, sonraki virgüller hiç bölünmüyor ve alan listesi
    ilk alanda kesiliyordu. Sonuç: `Compaction(marker = …)` için yanlış
    "bu alan yok" — yani kapı, sahibi olmadığı bir borcu ödetiyordu.

    Kural: `->` bir ok, `>` değil. Aynı kural `{ id -> … }` lambda'ları için de
    gerekli.
    """
    out: list[str] = []
    depth = 0
    current: list[str] = []
    for i, ch in enumerate(text):
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth = max(0, depth - 1)
        elif ch == "<":
            depth += 1
        elif ch == ">":
            if i > 0 and text[i - 1] == "-":
                pass  # ok: derinlik değişmez
            else:
                depth = max(0, depth - 1)
        if ch == "," and depth == 0:
            out.append("".join(current))
            current = []
        else:
            current.append(ch)
    if current:
        out.append("".join(current))
    return out


def _param_list(text: str, open_idx: int) -> str:
    """`(` konumundan başlayıp dengeli parantezle kapatır.

    Neden regex değil: varsayılan değerler `System.currentTimeMillis()` gibi
    parantez içerir; non-greedy bir regex ilk `)`'de durur ve kalan alanları
    kaybeder. Bu, `TaskEvidence(by = …)` için yanlış "bu alan yok" üretti.
    """
    depth = 0
    for i in range(open_idx, len(text)):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:i]
    return ""


def collect_function_names() -> set[str]:
    """Ağaçtaki tüm `fun X(` adları.

    Neden: Compose'ta `Column(modifier = …)` bir **fonksiyon** çağrısıdır, ama
    `Column` adında bir veri sınıfı da var. Fonksiyon/veri sınıfı adı çakışan
    bir çağrıda "bu alan yok" demek yanlış pozitiftir; o durumda susulur.
    """
    names: set[str] = set()
    for path in SRC.rglob("*.kt"):
        for m in re.finditer(r"\bfun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?(\w+)\s*\(", path.read_text(errors="ignore")):
            names.add(m.group(1))
    return names


def collect_type_names() -> dict[str, int]:
    """Her tip adının ağaçta kaç kez tanımlandığı.

    Adlandırılmış argüman denetimi ancak ad **tek bir tipe** çözülüyorsa
    kesindir: aynı ad hem arayüz hem veri sınıfı olabiliyor (builder deseni).
    """
    counts: dict[str, int] = {}
    decl = re.compile(
        r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"
        r"(?:data\s+|sealed\s+|abstract\s+|open\s+|private\s+|internal\s+|public\s+)*"
        r"(?:class|interface|object|enum\s+class)\s+(\w+)",
        re.M,
    )
    for path in SRC.rglob("*.kt"):
        for m in decl.finditer(path.read_text(errors="ignore")):
            counts[m.group(1)] = counts.get(m.group(1), 0) + 1
    return counts


def collect_data_classes() -> dict[str, set[str]]:
    fields: dict[str, set[str]] = {}
    for path in SRC.rglob("*.kt"):
        text = path.read_text(errors="ignore")
        for m in re.finditer(r"data\s+class\s+(\w+)\s*\(", text):
            name = m.group(1)
            body = _param_list(text, m.end() - 1)
            if not body.strip():
                continue
            names: set[str] = set()
            # Yorumlar ÖNCE silinir: içlerinde virgül var ("The path, URL or
            # the output text.") ve virgülden bölmek alanı ikiye ayırıyordu.
            # Ölçüldü: bu yüzden `TaskEvidence.value` ve `.by` kayboldu.
            cleaned_body = re.sub(r"/\*.*?\*/", " ", body, flags=re.S)
            cleaned_body = re.sub(r"//[^\n]*", " ", cleaned_body)
            for part in _split_top_level(cleaned_body):
                mm = re.match(r"\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:val|var)\s+(\w+)", part)
                if mm:
                    names.add(mm.group(1))
            if names:
                # Ada çakışması: aynı adlı iki `data class` olabilir.
                # Alanları birleştir; üzerine yazmak yanlış pozitif üretir.
                fields.setdefault(name, set()).update(names)
    return fields


def _top_level_args(args: str) -> list[str]:
    """Argüman listesini **yalnız üst düzey** virgüllerden böler.

    Neden: `Column(verticalArrangement = …)` gibi iç içe bir çağrı, dış
    çağrının argüman metninde geçiyor. Düz virgül bölmesi onun alanlarını dış
    tipin alanları sanıyor ve yanlış "bu alan yok" üretiyordu.
    """
    out: list[str] = []
    depth = 0
    current: list[str] = []
    for ch in args:
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(current))
            current = []
        else:
            current.append(ch)
    if current:
        out.append("".join(current))
    return [a.strip() for a in out if a.strip()]


def check_calls_for(
    fields: dict[str, set[str]],
    path: Path,
    type_counts: dict[str, int],
    function_names: set[str],
) -> None:
    """`Type(a = 1)` içindeki adlandırılmış argümanlar gerçek alan mı?

    ## Neden dengeli parantez

    Önce `([^()]{0,400})` ile aranıyordu. Gerçek hata tam bu yüzden kaçtı:
    `AgentCallback(..., budget = budgetClock(job.elapsedMs, …))` çağrısının
    argümanları **iç içe parantez** taşıyor, düz regex hiç eşleşmedi ve kapı
    "temiz" dedi; derleme hatası CI'a kaldı.
    """
    text = path.read_text(errors="ignore")
    # Compose widget'ları da veri sınıfı gibi görünüyor (`Card`, `Column`);
    # dosyada androidx'ten import edilmiş bir ad, bizim veri sınıfımız değildir.
    # [37130] HER import: dosyada import edilen bir ad, bu deponun veri sınıfı
    # değildir. Önce yalnız `androidx.compose` dışlanıyordu ve `import
    # androidx.compose.foundation.layout.Rect` gibi bir satır, `Rect(bottom=…)`
    # çağrısını "bu alan yok" diye işaretledi — üstelik `Rect` başka bir
    # paketten geliyordu (`GroupEntry` aynı sınıf). Kapı, sahibi olmadığı bir
    # borcu ödetmemeli: ithal edilen tip bu dosyanın denetim alanı dışında.
    imported_names = set()
    for line in text.splitlines():
        st = line.strip()
        if not st.startswith("import "):
            continue
        name = st[len("import "):].strip()
        if " as " in name:
            name = name.split(" as ", 1)[1].strip()
        else:
            name = name.rsplit(".", 1)[-1].strip()
        if name and name != "*":
            imported_names.add(name)
    compose_imports = imported_names
    for m in re.finditer(r"\b(\w+)\s*\(", text):
        type_name = m.group(1)
        if type_name not in fields or type_name in compose_imports:
            continue
        # [37130] NİTELİKLİ ÇAĞRI ATLANIR: `androidx.compose.ui.geometry.Rect(...)`
        # içinde `Rect(` bir tip kurulumu değil, başka bir paketin fonksiyonu;
        # aynı ada sahip yerel bir veri sınıfı varsa kapı yanlış konuşuyordu.
        # Aynı sınıf `PendingNonTextSelection.GroupEntry(...)` için de geçerli.
        # Önceki karakter bir `.` (ve ondan önce tanımlayıcı) ise ad niteliklidir.
        j = m.start() - 1
        if j >= 0 and text[j] == ".":
            continue
        # [37126] ANOTASYON DEĞİL: `@Insert(onConflict = …)`, `@Query(...)` gibi
        # kullanımlar veri sınıfı kurmuyor. Adlandırılmış argümanları KSP ve
        # derleyici doğrular; kapı burada konuşursa sahibi olmadığı bir borcu
        # ödetir (bu kez ChatDao'ya bir @Query eklerken tam olarak bu oldu —
        # uyarı eklediğim satırla ilgili değildi).
        before = text[:m.start()].rstrip()
        if before.endswith("@"):
            continue
        args = _param_list(text, m.end() - 1)
        if "=" not in args:
            continue
        # Konumsal argüman varsa (ilk parça `x =` değil) karışık olabilir; susulur.
        if not re.match(r"\s*\w+\s*=(?!=)", args):
            continue
        named = [
            m2.group(1)
            for m2 in (re.match(r"^(\w+)\s*=(?!=)", a) for a in _top_level_args(args))
            if m2
        ]
        for n in named:
            if n in fields[type_name]:
                continue
            where = f"{path.relative_to(ROOT)}: {type_name}({n} = …) — bu alan yok"
            if type_counts.get(type_name, 0) == 1 and type_name not in function_names:
                problems.append(where)
            else:
                suspects.append(where + f" ({type_counts.get(type_name)} aynı adlı tip)")


def check_double_comma(path: Path, problems: list[str]) -> None:
    """[37126] `a = 1,,` — Kotlin'de her zaman sözdizimi hatası.

    Bu kural neden var: `RecordedMatrix` yazarken tek satır düzenlemesi çift
    virgül bıraktı ve **yedi dakikalık CI turu** yalnız bunun için yandı.
    Kapı Kotlin ayrıştırmıyor; ama `,,` ayrıştırma gerektirmeyen, yanlış
    pozitifi olmayan tek sınıf. Test etmek de bedava: bir dosyada iki virgül
    yan yana ise hata.

    String ve yorum içindeki `,,` sayılmaz — `strip_code` zaten ikisini de
    siliyor, o yüzden temizlenmiş metin üzerinde çalışılıyor.
    """
    text = strip_code(path.read_text(errors="ignore"))
    for i, line in enumerate(text.splitlines(), start=1):
        if ",," in line.replace(" ", ""):
            problems.append(f"{path.relative_to(ROOT)}:{i}: çift virgül (,,) — sözdizimi hatası")


def added_lines(path: Path) -> set[int]:
    """Bu dosyada git'e göre **eklenen** satır numaraları (1 tabanlı).

    Neden: kapı yalnız dokunulan satırı yargılamalı. Bir dosyada tek satır
    değiştirmek, oradaki yıllanmış bir yabancı yorumu "yeni hata" gibi
    göstermemeli — o zaman kapı, sahibi olmadığın borcu ödetir ve kapatılır.
    Dosya git'te yoksa (yeni dosya) tüm satırlar eklenmiş sayılır.
    """
    try:
        p = subprocess.run(
            ["git", "-C", str(ROOT), "diff", "-U0", "--unified=0", "--", str(path)],
            capture_output=True, text=True, timeout=30,
        )
    except Exception:  # noqa: BLE001 - kapı aracı, patlamamalı
        return set()
    if p.returncode != 0 or not p.stdout.strip():
        return set(range(1, len(path.read_text(errors="ignore").splitlines()) + 1))
    out: set[int] = set()
    for line in p.stdout.splitlines():
        # Hunk başlığı: "@@ -eski +yeni,adet @@"
        m = re.match(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", line)
        if not m:
            continue
        start = int(m.group(1))
        count = int(m.group(2) or 1)
        out.update(range(start, start + count))
    return out


def modified_files() -> list[Path]:
    """HEAD'e göre değişmiş Kotlin dosyaları (staged + unstaged)."""
    out = subprocess.run(
        ["git", "-C", str(ROOT), "status", "--porcelain"],
        capture_output=True, text=True,
    ).stdout.splitlines()
    files: list[Path] = []
    for line in out:
        raw = line[3:].strip()
        if " -> " in raw:
            raw = raw.split(" -> ", 1)[1]
        if not raw.endswith(".kt"):
            continue
        f = ROOT / raw.strip('"')
        if f.exists():
            files.append(f)
    return files


def main() -> int:
    args = sys.argv[1:]
    explicit = [(Path(a) if Path(a).is_absolute() else ROOT / a) for a in args if a.endswith(".kt")]
    if explicit:
        files = explicit
    elif "--all" in args:
        files = list(SRC.rglob("*.kt"))
    else:
        files = modified_files()

    if not files:
        print("kotlin-hizli: değişmiş Kotlin dosyası yok")
        return 0

    # KESİN kurallar: yanlış-pozitifi yok, kırmızı yapar.
    for path in files:
        text = path.read_text(errors="ignore")
        only = added_lines(path)
        check_foreign(path, text, only)
        check_unused_imports(path, text, only)
        # [37124] Kapatılmamış yorum: dosya bütünü özelliği, "yalnız değişen
        # satır" daraltması burada yanlış olur (bkz. check_comments).
        check_comments(path, text)
        # [37126] Ayrıştırma gerektirmeyen tek sözdizimi sınıfı: çift virgül.
        check_double_comma(path, problems)
        # [37127] İç içe dikey kaydırma: TaskHallScreen çökmesinin sınıfı.
        _nested_scroll.check_file(path, problems, ROOT)

    # ŞÜPHELİ: yalnız bilgi. Veri sınıfı alan denetimi üst kaynakta (builder
    # arayüzleri, iç içe sınıflar) yanlış-pozitif üretiyor; bu yüzden kapı
    # değil, göz. Kırmızı sayılırsa geliştirici kapıyı kapatır — o zaman hiç
    #bir şey yakalamaz.
    fields = collect_data_classes()
    type_counts = collect_type_names()
    function_names = collect_function_names()
    for path in files:
        check_calls_for(fields, path, type_counts, function_names)

    if problems:
        print(f"kotlin-hizli: {len(set(problems))} KESİN sorun ({len(files)} dosya)")
        for p in sorted(set(problems))[:60]:
            print("  -", p)
        return 1
    if suspects:
        print(f"kotlin-hizli: {len(files)} dosya temiz · {len(set(suspects))} şüpheli (bilgi)")
        for s in sorted(set(suspects))[:15]:
            print("  ?", s)
        return 0
    print(f"kotlin-hizli: {len(files)} dosya temiz")
    return 0


if __name__ == "__main__":
    sys.exit(main())
