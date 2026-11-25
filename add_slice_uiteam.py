import io, json, pathlib

root = pathlib.Path("/var/minis/shared/mxrepo")
p = root / "scripts/slices.json"
data = json.loads(p.read_text(encoding="utf-8"))

# [37124] YENI DILIM: ui/team — bu dosyalar CI'da HIC derlenmiyordu.
#
# Olculen ariza: `.41`'de (matris yamasi) `DecisionTab` fonksiyonu yanlislikla
# silindi, cagrisi kaldi. Hicbir kapi yakalamadi, cunku `Slice Gate`'in yol
# listesinde `ui/team/**` yoktu: bu dosyalar `kotlinc` ile hic derlenmiyor,
# yalniz Gradle'in (native katmana bagli, 40 dk) isinde derleniyordu.
#
# Kural (bu depoda zaten yazili): "bir dilim yalniz kendi dosyalari + deps ile
# derlenebilmeli". `ui/team` Android'e bagli (Compose), yani dilim kapisi
# `android.*` importunu HATA sayar. Bu yuzden bu dilim **bilgi** dilimi:
# `android_free` bayragi ile kapiyalim, kirmizi olmasin ama derleme yine
# kosup tanimsiz sembolu yakalasin.
data["slices"]["37124"] = {
    "title": "Konsey/gorev UI (yapisal derleme)",
    "issue": "ui/team altindaki Compose dosyalari CI'da hic derlenmiyordu; "
             "silinen bir private fun yalniz Gradle'da ortaya cikiyordu",
    "files": ["src/android/app/src/main/java/com/openminis/app/ui/team"],
    "tests": ["src/android/app/src/test/java/com/openminis/app/ui/team"],
    "deps": [
        "src/android/app/src/main/java/com/openminis/app/ui/team"
    ],
    "structural_only": True,
}
p.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print("37124 dilimi eklendi, toplam dilim:", len(data["slices"]))

# workflow paths listesine ui/team ekle
wf = root / ".github/workflows/slice-gate.yml"
t = io.open(wf, encoding="utf-8").read()
old = "      - 'src/android/app/src/main/java/com/openminis/app/ui/chat/diff/**'"
new = old + "\n      # [37124] Konsey/gorev ekranlari: bu dosyalar daha once HIC derlenmiyordu\n      - 'src/android/app/src/main/java/com/openminis/app/ui/team/**'\n      - 'src/android/app/src/test/java/com/openminis/app/ui/team/**'"
assert old in t
t = t.replace(old, new, 1)
io.open(wf, "w", encoding="utf-8").write(t)
print("workflow paths guncellendi")
