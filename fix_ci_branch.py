import io, json, pathlib

root = pathlib.Path("/var/minis/shared/mxrepo")

# 1) YANLIS COZUMU GERI AL: ui/team dilimi Compose iceriyor, `kotlinc` ile
# derlenemez (Compose compiler plugin gerekir). Dilimi eklemek kapıyı sonsuza
# kadar kırmızı yapardı — "kapatılan kapı" sınıfı.
p = root / "scripts/slices.json"
data = json.loads(p.read_text(encoding="utf-8"))
if "37124" in data["slices"]:
    del data["slices"]["37124"]
    p.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("37124 dilimi geri alindi (Compose kotlinc ile derlenmez)")

wf = root / ".github/workflows/slice-gate.yml"
t = io.open(wf, encoding="utf-8").read()
t = t.replace(
    "\n      # [37124] Konsey/gorev ekranlari: bu dosyalar daha once HIC derlenmiyordu\n"
    "      - 'src/android/app/src/main/java/com/openminis/app/ui/team/**'\n"
    "      - 'src/android/app/src/test/java/com/openminis/app/ui/team/**'",
    "",
)
io.open(wf, "w", encoding="utf-8").write(t)

# 2) GERCEK COZUM: apk.yml'nin dal filtresine `ozel/**` ekle.
apk = root / ".github/workflows/apk.yml"
a = io.open(apk, encoding="utf-8").read()
old = """  push:
    branches:
      - main
"""
new = """  push:
    branches:
      - main
      # [37124] GERCEK BOSLUK: bu filtre yalniz `ozel-*` (tire) desenini
      # tasiyordu, ama calisma dali `ozel/16-duzeltmeler` (SLASH). Yani
      # `.35`-`.43` arasindaki DOKUZ surumun hicbiri CI'da **derlenmedi**:
      # `Slice Gate` yalniz dilim dosyalarini `kotlinc` ile derliyor ve
      # `ui/**` orada yok. Sonuc: `.41`'de silinen bir `private fun`
      # (`DecisionTab`) dokuz surum boyunca fark edilmedi; cagrisi duruyordu.
      # Derleme artik bu dalda da kosuyor; yayin karari degismiyor (surum
      # kapisi ayni versionCode'u iki kez yayinlatmiyor).
      - 'ozel/**'
      - 'ozel-*'
"""
assert old in a, "apk.yml dal blogu bulunamadi"
a = a.replace(old, new, 1)
io.open(apk, "w", encoding="utf-8").write(a)
print("apk.yml: ozel/** eklendi")
