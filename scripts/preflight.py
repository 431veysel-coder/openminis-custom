#!/usr/bin/env python3
"""Push öncesi denetim hattı — katmanlı, gürültüsüz, watchdog'lu.

## Neden var

11.1/11.2 boyunca aynı sınıf hatalar dört kez CI'da patladı, her biri 4-5
dakikalık tur kaybettirdi:

  1. `TranslationPrefs.engine(...)` import edilmemiş        → Unresolved reference
  2. `<string>Güncelle'ye</string>` kaçırılmamış            → aapt2 flatten hatası
  3. `Feature.BROWSER_TAB_LIMIT` bu dalda yok               → Unresolved reference
  4. `R.string.notes_title` tanımsız                       → AAPT error

Dördü de derleyici gerektirmeden görülebilir.

## Strateji (önceki iki denemeden çıkan ders)

Önceki sürümler O(dosya × tip) tarıyordu (25 s'de bitmiyordu) ve ikincisi 1926
sorun üretiyordu; %95'i yanlış pozitifti. Yanlış pozitif, kaçırmaktan kötüdür:
gürültülü kapıyı insan kapatır. Bu yüzden üç kural:

1. **Kesinlik > kapsam.** Her denetim, yalnız emin olduğunda konuşur. Şüphede
   susar. Yorumlar ve string literal'ler taranmaz (ilk 674 yanlışın kaynağı).
2. **Baseline.** `scripts/preflight-baseline.json` kabul edilmiş sorunları
   tutar. Kapı yalnız YENİ sorunda düşer. Böylece eski gürültü işi kilitlemez,
   yeni gerileme yine yakalanır.
3. **Watchdog.** Her katmanın süre bütçesi var; genel bir deadline var; aşılırsa
   çıkış kodu 2 (başarı DEĞİL). Takılan betik sessizce "temiz" sayılamaz.

## Katmanlar

| Katman | Ne | Süre |
|---|---|---|
| L0 | sürüm numarası, XML metin kaçışı | <0.3 s |
| L1 | R.string referansları, parantez dengesi | ~1 s |
| L2 | import edilmemiş proje tipi, enum sabiti | ~2 s |

`--staged` ile yalnız commit'lenecek `.kt` dosyaları taranır (L0 her zaman tüm
ağaçta çalışır: bir XML hatası tek dosyada değil, derlemede patlar).

## Kullanım

    python3 scripts/preflight.py              # kapı (baseline farkı)
    python3 scripts/preflight.py --all        # baseline'ı yok say, hepsini göster
    python3 scripts/preflight.py --staged     # commit öncesi (hook bunu çağırır)
    python3 scripts/preflight.py --selftest   # denetimler kendini sınar
    python3 scripts/preflight.py --bench      # katman süreleri
    python3 scripts/preflight.py --json       # CI için
    python3 scripts/preflight.py --update-baseline

Çıkış: 0 temiz/bilinen, 1 YENİ sorun, 2 watchdog (denetim tamamlanmadı)
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/android/app/src/main/res"
MAIN = ROOT / "src/android/app/src/main/java"
TEST = ROOT / "src/android/app/src/test/java"
GRADLE = ROOT / "src/android/app/build.gradle.kts"
BASELINE = ROOT / "scripts/preflight-baseline.json"

BURNED_AT_OR_BELOW = 37027

#: Watchdog: bu süre aşılırsa çıkış 2. Bir betiğin sonsuza kadar dönmesi,
#: "temiz" demesinden daha az zararlıdır ama yine de zararlıdır.
DEFAULT_DEADLINE_S = 45.0


# ────────────────────────────────────────────────────────────────────────────
# Metin normalizasyonu — tüm metin tarayan denetimlerin temeli
# ────────────────────────────────────────────────────────────────────────────

_BLOCK_COMMENT = re.compile(r"/\*(?:.|\n)*?\*/")
_LINE_COMMENT = re.compile(r"//[^\n]*")
_TRIPLE_STRING = re.compile(r'"""(?:.|\n)*?"""')
_STRING = re.compile(r'"(?:\\.|[^"\\\n])*"')
_CHAR = re.compile(r"'(?:\\.|[^'\\\n])'")


def strip_code(text: str) -> str:
    """Yorumları, string ve karakter literal'lerini boşlukla değiştirir.

    Satır numaralarını korumak için her eşleşme AYNI sayıda satır sonu ile
    değiştirilir. Yorumlardaki bir tip adı (KDoc'da `AgentToolSwitch`) referans
    değildir; string içindeki `{` parantez değildir. İlk denemenin 674 import +
    12 denge yanlışının tamamı buydu.
    """
    def blank(m: re.Match) -> str:
        s = m.group(0)
        return "\n" * s.count("\n") + " " * (len(s) - s.count("\n"))

    text = _BLOCK_COMMENT.sub(blank, text)
    text = _LINE_COMMENT.sub(blank, text)
    text = _TRIPLE_STRING.sub(blank, text)
    text = _STRING.sub(blank, text)
    text = _CHAR.sub(blank, text)
    return text


# ────────────────────────────────────────────────────────────────────────────
# Sonuç modeli
# ────────────────────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class Problem:
    check: str
    file: str
    line: int
    message: str

    @property
    def key(self) -> str:
        """Baseline anahtarı: dosya + denetim + mesaj (satır hariç).

        Satır numarası bilerek dışarıda: bir dosyaya üç satır eklemek 40
        baseline kaydını "yeni sorun" gibi göstermemeli.
        """
        return f"{self.check}|{self.file}|{self.message}"

    def render(self) -> str:
        loc = f"{self.file}:{self.line}" if self.line else self.file
        return f"[{self.check}] {loc}: {self.message}"

    def as_dict(self) -> dict:
        return {"check": self.check, "file": self.file, "line": self.line,
                "message": self.message, "key": self.key}


@dataclass
class Ctx:
    #: TÜM Kotlin dosyaları — tip/enum dizini buradan kurulur. `--staged`
    #: modunda bile tam ağaç taranır: bir tipin nerede tanımlı olduğunu bilmek
    #: için tanım dosyasının sahnelenmiş olması gerekmez. (Bu atlandığında
    #: `NoteStore` gibi gerçek bir eksik import HİÇ yakalanmadı.)
    kotlin: list[Path] = field(default_factory=list)
    #: Yalnız RAPORLANACAK dosyaların göreli yolları. Boşsa hepsi.
    targets: set[str] = field(default_factory=set)
    strings_files: list[Path] = field(default_factory=list)


@dataclass
class KFile:
    path: Path
    rel: str
    raw: str          # yorum/string dahil (XML kaçışı gibi denetimler için)
    code: str         # yorum/string temizlenmiş (referans denetimleri için)
    package: str
    imports: set[str]
    import_full: set[str]
    wildcard_imports: list[str]
    android_r: bool   # `import android.R` var mı — R.string X android kaynağı olabilir
    declared: set[str]


def _rel(p: Path) -> str:
    """ROOT'a göre yol; geçici dizinden gelen test dosyaları için tam yol."""
    try:
        return str(p.relative_to(ROOT))
    except ValueError:
        return str(p)


#: (yol, mtime_ns, boyut) → içerik. L0 ve L1 aynı strings dosyalarını tarıyor;
#: önbellek olmadan 477 dosya iki kez okunuyordu. Anahtar mtime taşıyor ki
#: testte yeniden yazılan bir dosya eski içerikle dönmesin (bu tam olarak bir
#: kez oldu ve selftest yakaladı).
_READ_CACHE: dict[tuple[str, int, int], str] = {}


def _read(p: Path) -> str:
    try:
        st = p.stat()
        key = (str(p), st.st_mtime_ns, st.st_size)
    except Exception:
        return ""
    if key in _READ_CACHE:
        return _READ_CACHE[key]
    try:
        text = p.read_text(encoding="utf-8")
    except Exception:
        text = ""
    _READ_CACHE[key] = text
    return text


_PACKAGE = re.compile(r"^package\s+([\w.]+)\s*$", re.M)
_IMPORT_LINE = re.compile(r"^import\s+([\w.]+)(\.\*)?(?:\s+as\s+(\w+))?\s*$", re.M)
_TYPE_DECL = re.compile(
    r"^[ \t]*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:internal |private |public |protected )?"
    r"(?:data |sealed |enum |abstract |open |annotation |inner |value )*"
    r"(?:object|class|interface)\s+([A-Z][A-Za-z0-9_]*)",
    re.M,
)


def parse(p: Path, rel_root: Path) -> KFile:
    raw = _read(p)
    code = strip_code(raw)
    pkg_m = _PACKAGE.search(code)
    pkg = pkg_m.group(1) if pkg_m else ""
    imports: set[str] = set()
    import_full: set[str] = set()
    wildcards: list[str] = []
    android_r = False
    for m in _IMPORT_LINE.finditer(code):
        full, star, alias = m.group(1), m.group(2), m.group(3)
        if star:
            wildcards.append(full)
        else:
            imports.add(alias or full.rsplit(".", 1)[-1])
            import_full.add(full)
        if full == "android.R":
            android_r = True
    declared = {m.group(1) for m in _TYPE_DECL.finditer(code)}
    return KFile(p, _rel(p), raw, code, pkg, imports, import_full,
                 wildcards, android_r, declared)


def load(staged_only: bool) -> Ctx:
    files = sorted(MAIN.rglob("*.kt"))
    if TEST.exists():
        files += sorted(TEST.rglob("*.kt"))
    targets: set[str] = set()
    if staged_only:
        out = subprocess.run(["git", "diff", "--cached", "--name-only",
                              "--diff-filter=ACMR"], cwd=ROOT,
                             capture_output=True, text=True).stdout.split()
        wanted = {str((ROOT / p).resolve()) for p in out if p.endswith(".kt")}
        targets = {_rel(f) for f in files if str(f.resolve()) in wanted}
    return Ctx(kotlin=files, targets=targets,
               strings_files=sorted(RES.glob("values*/strings*.xml")))


# ────────────────────────────────────────────────────────────────────────────
# L0 — XML metin kaçışı + sürüm
# ────────────────────────────────────────────────────────────────────────────

_STR_TAG = re.compile(r'<string name="([^"]+)"[^>]*>(.*?)</string>', re.S)


def l0_strings(ctx: Ctx) -> list[Problem]:
    """`<string>` gövdesinde kaçırılmamış `'`.

    aapt2: "Invalid unicode escape sequence". KAYNAK birleştirmede ölür, kod
    hatası gibi görünür. Türkçe metinde sürekli tekrarladı.
    """
    out: list[Problem] = []
    for path in ctx.strings_files:
        text = _read(path)
        if not text:
            continue
        rel = _rel(path)
        for m in _STR_TAG.finditer(text):
            name, body = m.group(1), m.group(2)
            # Zaten kaçırılmış olanlar: \'   Kaçırılmamış: (?<!\\)'
            if re.search(r"(?<!\\)'", body):
                out.append(Problem("strings", rel,
                                   text[: m.start()].count("\n") + 1,
                                   f'<string name="{name}"> kaçırılmamış kesme işareti (\\\' olmalı)'))
    return out


def l0_version(ctx: Ctx) -> list[Problem]:
    text = _read(GRADLE)
    if not text:
        return [Problem("version", "src/android/app/build.gradle.kts", 0, "okunamadı")]
    out: list[Problem] = []
    mc = re.search(r"versionCode = (\d+)", text)
    mn = re.search(r'versionName = "([^"]+)"', text)
    if not mc or not mn:
        return [Problem("version", "src/android/app/build.gradle.kts", 0,
                        "versionCode/versionName bulunamadı")]
    vc, vn = int(mc.group(1)), mn.group(1)
    if vc <= BURNED_AT_OR_BELOW:
        out.append(Problem("version", "src/android/app/build.gradle.kts",
                           text[: mc.start()].count("\n") + 1,
                           f"versionCode {vc} yanmış aralıkta (<= {BURNED_AT_OR_BELOW})"))
    if "-ozel" not in vn:
        out.append(Problem("version", "src/android/app/build.gradle.kts",
                           text[: mn.start()].count("\n") + 1,
                           f'versionName "{vn}" -ozel eki taşımıyor'))
    return out


# ────────────────────────────────────────────────────────────────────────────
# L1 — R.string referansları + parantez dengesi
# ────────────────────────────────────────────────────────────────────────────

_ANY_STRING_RES = re.compile(r'<string(?:-array)? name="([^"]+)"')
_R_STRING_REF = re.compile(r"(?<![\w.])R\.string\.([A-Za-z0-9_]+)")


def l1_string_refs(ctx: Ctx, files: list[KFile]) -> list[Problem]:
    """`R.string.X` — X herhangi bir `strings*.xml` içinde tanımlı mı?

    `import android.R` olan dosyalarda `R.string.copy` gibi referanslar Android
    çerçevesinin kaynağıdır; statik olarak ayırt edilemez, o dosyalar atlanır.
    """
    defined: set[str] = set()
    for p in ctx.strings_files:
        defined |= set(_ANY_STRING_RES.findall(_read(p)))
    # values/ içindeki TÜM xml'ler (plurals, arrays, ids farklı dosyalarda olabilir)
    for p in RES.glob("values*/*.xml"):
        defined |= set(re.findall(r'<(?:string|string-array|plurals|item)[^>]*name="([^"]+)"',
                                  _read(p)))
    if not defined:
        return []
    out: list[Problem] = []
    for f in files:
        if f.android_r:
            continue
        for m in _R_STRING_REF.finditer(f.code):
            name = m.group(1)
            if name in defined:
                continue
            out.append(Problem("R.string", f.rel, f.code[: m.start()].count("\n") + 1,
                               f"R.string.{name} tanımlı değil"))
    return out


def l1_balance(files: list[KFile]) -> list[Problem]:
    """Küme parantezi dengesi (yorum/string temizlenmiş metinde)."""
    out: list[Problem] = []
    for f in files:
        s = f.code
        o, c = s.count("{"), s.count("}")
        if o != c:
            out.append(Problem("balance", f.rel, 0,
                               f"küme parantezi dengesiz: {o} aç / {c} kapa"))
    return out


# ────────────────────────────────────────────────────────────────────────────
# L2 — import edilmemiş tip + enum sabiti
# ────────────────────────────────────────────────────────────────────────────

_SIMPLE_REF = re.compile(r"(?<![\w.`])([A-Z][A-Za-z0-9_]*)\.")

#: Asla "import edilmemiş proje tipi" diye işaretlenmeyecek adlar.
#: JDK/Kotlin çerçeve tipleri (import gerekmez, farklı paket kuralı işlemez) ve
#: tek harfli generic parametreler (S, T, E …) — bunlar tip referansı değildir.
_NOT_PROJECT_TYPES = {
    "System", "String", "StringBuilder", "Object", "Math", "Thread", "Runtime",
    "Process", "ProcessBuilder", "File", "Files", "Path", "Paths", "Arrays",
    "Collections", "Objects", "Integer", "Long", "Double", "Float", "Boolean",
    "Byte", "Short", "Character", "Exception", "Throwable", "Error", "Class",
    "Result", "Builder", "Unit", "Any", "Nothing", "Pair", "Triple", "Lazy",
}


def _is_target(f: KFile, targets: set[str]) -> bool:
    """--staged modunda yalnız sahnelenen dosya raporlanır."""
    return not targets or f.rel in targets


def l2_unimported(files: list[KFile], targets: set[str] | None = None) -> list[Problem]:
    """Proje tipinin import edilmeden basit adla kullanımı.

    Kurallar (hepsi kesinlik için):
      - yalnız BİR kez tanımlı tip adları (aynı ad iki yerde → belirsiz, sus),
      - aynı paketteki tip import gerektirmez (sus),
      - dosyada wildcard import varsa hangi tipi kapsadığı bilinemez (sus),
      - yalnız yorum/string dışı metin taranır.

    Gerçek vaka: `TranslationPrefs.engine(context)` — import yok.
    """
    decl_count: dict[str, int] = {}
    decl_pkg: dict[str, str] = {}
    for f in files:
        # Filtre yok: `files` zaten YALNIZ bu deponun Kotlin dosyaları. AndroidX
        # tipleri hiç ayrıştırılmadığı için onların gürültüsü zaten yok.
        for name in f.declared:
            decl_count[name] = decl_count.get(name, 0) + 1
            decl_pkg.setdefault(name, f.package)

    targets = targets or set()
    out: list[Problem] = []
    for f in files:
        if not _is_target(f, targets):
            continue
        if f.wildcard_imports:
            continue                     # kapsam bilinemez
        seen: set[str] = set()
        for m in _SIMPLE_REF.finditer(f.code):
            name = m.group(1)
            if name in seen or name in f.declared or name in f.imports:
                continue
            if name in _NOT_PROJECT_TYPES or len(name) < 3:
                continue              # JDK tipi ya da generic parametre (S, T, E)
            if decl_count.get(name, 0) != 1:
                continue
            if decl_pkg.get(name) == f.package:
                continue
            seen.add(name)
            out.append(Problem("import", f.rel, f.code[: m.start()].count("\n") + 1,
                               f"`{name}` import edilmeden kullanılıyor"))
    return out


def _enum_members(body: str) -> set[str]:
    """Enum gövdesinden sabit adları. Gövde brace-matched gelmiş olmalı."""
    # Sabit bölümü ilk `;` ya da ilk fonksiyon/virgül dışı blok başlangıcına kadar.
    head = body.split(";", 1)[0]
    out = set()
    for m in re.finditer(r"(?:^|,)\s*([A-Z][A-Z0-9_]*)\s*(?=[,(=]|$|\n)", head, re.M):
        out.add(m.group(1))
    # Companion içindeki sabitler/özellikler `Enum.DEFAULT` diye çağrılır ama
    # enum girdisi değildir. Onları da "var" say; yoksa geçerli kod işaretlenir.
    for m in re.finditer(r"(?:const\s+)?val\s+([A-Z][A-Z0-9_]*)\b", body):
        out.add(m.group(1))
    return out


def l2_enum(files: list[KFile], targets: set[str] | None = None) -> list[Problem]:
    """`Enum.SABIT` — sabit enum'da var mı?

    Kurallar: yalnız proje içinde TANIMLI ve TEK olan enum'lar; gövde brace
    eşlemesiyle çıkarılır (önceki sürüm ilk `\\n}`de kesiyordu, bu yüzden iç
    içe enum'ların sabitlerini kaçırıyordu).
    """
    enums: dict[str, set[str]] = {}
    enum_pkg: dict[str, str] = {}
    ambiguous: set[str] = set()
    for f in files:
        code = f.code
        for m in re.finditer(r"\benum\s+class\s+([A-Z][A-Za-z0-9_]*)", code):
            name = m.group(1)
            brace = code.find("{", m.end() - 1)
            if brace < 0:
                continue
            depth, i = 0, brace
            while i < len(code):
                if code[i] == "{":
                    depth += 1
                elif code[i] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            if depth != 0:
                continue
            members = _enum_members(code[brace + 1: i])
            if not members:
                continue
            if name in enums:
                ambiguous.add(name)      # aynı ad iki yerde → belirsiz
            enums[name] = members
            enum_pkg.setdefault(name, f.package)
    for name in ambiguous:
        enums.pop(name, None)
    if not enums:
        return []

    # Tek genel kalıp + sözlük araması. Önceden tüm enum adlarından dev bir
    # alternation kuruluyordu ve 1520 dosyada 5.5 s yiyordu; bu hâli tek geçiş.
    pattern = re.compile(r"(?<![\w.])([A-Z][A-Za-z0-9_]*)\.([A-Z][A-Z0-9_]*)\b")
    builtin = {"entries", "valueOf", "values", "name", "ordinal", "Companion"}
    out: list[Problem] = []
    for f in files:
        if f.wildcard_imports:
            continue                      # kapsam bilinemez
        for m in pattern.finditer(f.code):
            enum_name, used = m.group(1), m.group(2)
            if enum_name not in enums:
                continue
            if used in enums[enum_name] or used in builtin:
                continue
            # Yalnız EMİN olduğunda konuş. `Channel.CONFLATED` bizim enum'umuz
            # değil, kotlinx'in: aynı BASİT ad iki farklı tipe işaret edebilir.
            # Bu yüzden tam nitelikli import eşleşmesi arıyoruz — basit ad
            # eşleşmesi (önceki hâli) tam da bu yüzden yanlış pozitif veriyordu.
            decl_pkg = enum_pkg.get(enum_name)
            if decl_pkg != f.package and f"{decl_pkg}.{enum_name}" not in f.import_full:
                continue
            out.append(Problem("enum", f.rel, f.code[: m.start()].count("\n") + 1,
                               f"`{enum_name}.{used}` bu enum'da yok"))
    return out


# ────────────────────────────────────────────────────────────────────────────
# Hat + watchdog
# ────────────────────────────────────────────────────────────────────────────

class Watchdog:
    def __init__(self, budget_s: float) -> None:
        self.deadline = time.monotonic() + budget_s
        self.budget_s = budget_s

    def expired(self) -> bool:
        return time.monotonic() > self.deadline

    def remaining(self) -> float:
        return max(0.0, self.deadline - time.monotonic())


def run(staged_only: bool, deadline_s: float, verbose: bool) -> tuple[list[Problem], bool]:
    """Hattı çalıştırır. (sorunlar, watchdog_tetiklendi)"""
    wd = Watchdog(deadline_s)
    ctx = load(staged_only)
    parsed = [parse(p, ROOT) for p in ctx.kotlin]
    if verbose:
        print(f"  dizin: {len(parsed)} Kotlin dosyası, {len(ctx.strings_files)} strings dosyası",
              file=sys.stderr)

    problems: list[Problem] = []

    def layer(label: str, fn) -> None:
        if wd.expired():
            return
        t0 = time.monotonic()
        try:
            problems.extend(fn())
        except Exception as e:  # bir denetim bozuksa hat diye durmasın
            print(f"  ! {label} denetimi çöktü: {e.__class__.__name__}: {e}", file=sys.stderr)
        if verbose:
            print(f"  {label}: {time.monotonic() - t0:.2f}s", file=sys.stderr)

    layer("L0 strings", lambda: l0_strings(ctx))
    layer("L0 version", lambda: l0_version(ctx))
    layer("L1 R.string", lambda: l1_string_refs(ctx, parsed))
    layer("L2 import", lambda: l2_unimported(parsed, ctx.targets))
    layer("L2 enum", lambda: l2_enum(parsed, ctx.targets))
    # Denge denetimi BİLEREK kapıyı tutmuyor: sözdizimi hatası derlemede anında
    # ve net bir mesajla patlar, yani burada yakalanacak sessiz bir hata yok.
    # Buna karşılık string şablonları ve karakter literal'leri yüzünden yanlış
    # pozitif üretiyor (66 dosya). Danışma olarak basılır, sayılmaz.
    if verbose:
        advisory = l1_balance(parsed)
        if advisory:
            print(f"  (danışma) {len(advisory)} dosyada parantez dengesi şüpheli "
                  f"— derleyici doğru cevabı verir, kapı saymaz", file=sys.stderr)
    return problems, wd.expired()


# ────────────────────────────────────────────────────────────────────────────
# Baseline
# ────────────────────────────────────────────────────────────────────────────

def load_baseline() -> set[str]:
    if not BASELINE.exists():
        return set()
    try:
        data = json.loads(BASELINE.read_text(encoding="utf-8"))
        return {e["key"] for e in data.get("accepted", [])}
    except Exception:
        return set()


def save_baseline(problems: list[Problem]) -> None:
    accepted = sorted({p.key for p in problems})
    BASELINE.write_text(json.dumps(
        {
            "_comment": [
                "Kabul edilmiş denetim sorunları. Kapı yalnız bu listede OLMAYAN",
                "sorunda düşer. Amaç: eski gürültü işi kilitlemesin, yeni gerileme",
                "yine yakalansın. Bir kaydı silmek = o sorunu düzeltmeyi taahhüt.",
                "Boş liste hedeftir.",
            ],
            "count": len(accepted),
            "accepted": [{"key": k} for k in accepted],
        },
        ensure_ascii=False, indent=2,
    ) + "\n", encoding="utf-8")


# ────────────────────────────────────────────────────────────────────────────
# Kendini sınama
# ────────────────────────────────────────────────────────────────────────────

def selftest() -> int:
    import tempfile

    fails: list[str] = []

    def want(name: str, got: list[Problem], check: str, expect: bool = True) -> None:
        hit = any(p.check == check for p in got)
        if hit != expect:
            fails.append(f"{name}: '{check}' {'tetiklenmedi' if expect else 'yanlış tetiklendi'}")

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)

        # XML kaçışı
        v = root / "values-tr"; v.mkdir(parents=True)
        bad = v / "strings.xml"
        bad.write_text("<resources><string name=\"x\">Güncelle'ye</string></resources>", encoding="utf-8")
        want("xml kaçışı", l0_strings(Ctx(strings_files=[bad])), "strings")
        good = v / "s2.xml"
        good.write_text("<resources><string name=\"x\">Güncelle\\'ye</string></resources>", encoding="utf-8")
        want("xml doğru kaçış", l0_strings(Ctx(strings_files=[good])), "strings", expect=False)

        # Yorumdaki tip adı referans değildir (674 yanlışın kaynağı)
        a = root / "a"; b = root / "b"
        a.mkdir(); b.mkdir()
        decl = a / "Prefs.kt"
        decl.write_text("package a\nobject Prefs { fun x() = 1 }\n", encoding="utf-8")
        use = b / "Use.kt"
        use.write_text("package b\n// Prefs.x() burada anıldı ama kullanılmadı\nfun f() = 1\n",
                       encoding="utf-8")
        want("yorumdaki ad", l2_unimported([parse(decl, root), parse(use, root)]), "import",
             expect=False)
        use.write_text("package b\n\nfun f() = Prefs.x()\n", encoding="utf-8")
        want("gerçek eksik import", l2_unimported([parse(decl, root), parse(use, root)]), "import")
        use.write_text("package b\n\nimport a.Prefs\n\nfun f() = Prefs.x()\n", encoding="utf-8")
        want("import edilmiş", l2_unimported([parse(decl, root), parse(use, root)]), "import",
             expect=False)

        # Enum: brace-matched, iç içe gövde
        e = a / "E.kt"
        e.write_text(
            "package a\nenum class Color {\n    RED,\n    GREEN;\n    fun x() = 1\n}\n", encoding="utf-8")
        u = b / "U.kt"
        u.write_text("package b\nimport a.Color\nval c = Color.RED\nval d = Color.BLUE\n",
                     encoding="utf-8")
        got = l2_enum([parse(e, root), parse(u, root)])
        if not any("BLUE" in p.message for p in got):
            fails.append("enum: olmayan sabit yakalanmadı")
        if any("RED" in p.message for p in got):
            fails.append("enum: var olan sabit yanlış işaretlendi")

        # Denge: string içindeki `{` sayılmaz
        s = b / "S.kt"
        s.write_text('package b\nval x = "{\\n"\nfun f() {}\n', encoding="utf-8")
        want("string içinde brace", l1_balance([parse(s, root)]), "balance", expect=False)

    # Watchdog gerçekten tetikleniyor mu (0 bütçe = anında süresi dolar)
    wd = Watchdog(0.0)
    if not wd.expired():
        fails.append("watchdog: 0 bütçede süre dolmadı")

    if fails:
        print(f"selftest BAŞARISIZ ({len(fails)}):")
        for f in fails:
            print("  ", f)
        return 2
    print("selftest geçti: 9 vaka (5 denetim + watchdog)")
    return 0


# ────────────────────────────────────────────────────────────────────────────
# Giriş
# ────────────────────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(description="Push öncesi denetim hattı")
    ap.add_argument("--staged", action="store_true", help="yalnız commit'lenecek .kt")
    ap.add_argument("--all", action="store_true", help="baseline'ı yok say")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--bench", action="store_true", help="katman süreleri")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--update-baseline", action="store_true")
    ap.add_argument("--deadline", type=float, default=DEFAULT_DEADLINE_S,
                    help=f"watchdog bütçesi (sn, varsayılan {DEFAULT_DEADLINE_S:g})")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    t0 = time.monotonic()
    problems, timed_out = run(args.staged, args.deadline, verbose=args.bench)
    elapsed = time.monotonic() - t0

    if timed_out:
        print(f"watchdog: {args.deadline:g}s bütçesi doldu — denetim TAMAMLANMADI.\n"
              f"Bu 'temiz' demek değildir. Sınırı artırın (--deadline) ya da\n"
              f"denetimi bölün (--staged).", file=sys.stderr)
        return 2

    if args.update_baseline:
        save_baseline(problems)
        print(f"baseline güncellendi: {len({p.key for p in problems})} kayıt → "
              f"{BASELINE.relative_to(ROOT)}")
        return 0

    known = set() if args.all else load_baseline()
    new = [p for p in problems if p.key not in known]

    if args.json:
        print(json.dumps({
            "ok": not new, "timed_out": False, "elapsed_s": round(elapsed, 2),
            "total": len(problems), "known": len(problems) - len(new),
            "new": [p.as_dict() for p in new],
        }, ensure_ascii=False, indent=2))
        return 0 if not new else 1

    if new:
        print(f"\n{len(new)} YENİ sorun ({elapsed:.1f}s · toplam {len(problems)}, "
              f"{len(problems) - len(new)} bilinen):\n")
        grouped: dict[tuple[str, str], list[Problem]] = {}
        for p in new:
            grouped.setdefault((p.check, p.file), []).append(p)
        for (check, f), group in sorted(grouped.items()):
            print(f"  [{check}] {f}  ({len(group)})")
            for p in group[:6]:
                print(f"      satır {p.line}: {p.message}")
            if len(group) > 6:
                print(f"      … {len(group) - 6} tane daha")
        print("\nDüzeltmeden commit etme. Kabul edilebilirse: --update-baseline")
        return 1

    extra = f" · {len(problems)} bilinen" if problems else ""
    print(f"preflight temiz — {elapsed:.1f}s{extra}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
