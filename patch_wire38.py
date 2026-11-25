import io, pathlib

root = pathlib.Path("/var/minis/shared/mxrepo")
p = root / "src/android/app/src/main/java/com/openminis/app/MinisApp.kt"
s = io.open(p, encoding="utf-8").read()

anchor = """        com.openminis.app.ozel.ApprovalVoice.start(this)"""
assert anchor in s
wiring = """        com.openminis.app.ozel.ApprovalVoice.start(this)

        // [37119] Sanal ekranın kare üreticisi. `screen` aracının `capture`
        // eylemi buraya bağlı: kare üretemezse "gördüm" demiyor, susuyor.
        // Tek kanca — araç yürütücüsü (ChatViewModel) çizim katmanına sahip
        // değil ve olması mimariyi tersine çevirirdi.
        //
        // Sınır (karede de yazılı): üretilen kare **durum karesi**, canlı
        // sayfa görüntüsü değil. Bölme içeriği canlı yüzeye bağlanınca
        // (SANAL-EKRAN.md §4 P1) bu satır o kaynağa çevrilecek.
        com.openminis.app.tools.ScreenTool.frameSource =
            com.openminis.app.tools.ScreenTool.FrameSource { pane ->
                canvasFrameRenderer.frame(pane)
            }"""
s = s.replace(anchor, wiring, 1)

# renderer alanini sinif govdesine ekle (son "}" once)
marker = "\n}\n"
idx = s.rindex("private val canvasFrameRenderer") if "private val canvasFrameRenderer" in s else -1
if idx == -1:
    # App sinifinin sonuna yakin bir uye ekle: onAttachBaseContext'ten once
    hook = "    private val canvasFrameRenderer by lazy {"
    add = """    /** [37119] `screen` aracının kare üreticisi (bkz. onCreate'teki kanca). */
    private val canvasFrameRenderer by lazy {
        com.openminis.app.screen.CanvasFrameRenderer(this)
    }

"""
    # onCreate tanimindan once ekle
    k = s.index("    override fun onCreate()")
    s = s[:k] + add + s[k:]
io.open(p, "w", encoding="utf-8").write(s)
print("MinisApp kancasi eklendi")
