import io, pathlib

root = pathlib.Path("/var/minis/shared/mxrepo")
p = root / "src/android/app/src/main/java/com/openminis/app/ozel/ApprovalVoice.kt"
s = io.open(p, encoding="utf-8").read()

anchor = '''    /** Kullanıcı "şimdi dinle" derse — ayarlar ekranındaki sınama düğmesi. */'''

notice_fn = '''    /**
     * [37118] **Bilgi** duyurusu — kart yok, cevap beklenmiyor.
     *
     * `ALLOW_ALL` modunda geri alınamaz ya da sandbox dışı bir iş kart
     * çıkarmaz (kullanıcı bütün izinleri verdi) ama sessiz de geçmez: telefon
     * tek cümle söyler, titrer ve bir bildirim bırakır. Tekrar **yoktur** —
     * tekrar, cevap bekleyen bir soruya aittir; burada soru yok.
     *
     * Neden `PolicyGate`'ten çağrılıyor da `pending` akışından değil: bu iş
     * kuyruğa hiç girmiyor, dolayısıyla akışta görünmüyor. Kuyruğa sokup
     * sonra anında cevaplamak, aynı bilgiyi iki yoldan taşımak olurdu.
     */
    fun notice(context: Context, request: ApprovalRequest) {
        val ctx = context.applicationContext
        val settings = ApprovalSettings.voice(ctx)
        if (settings.vibrate) vibrate(ctx)
        postNotification(ctx, request, pending = 0)
        if (!settings.enabled) return
        val manager = manager(ctx) ?: return
        val line = ApprovalVoiceText.noticeLine(request, speakOperation = settings.speakOperation)
        manager.speak(line)
    }

''' + anchor

assert anchor in s
s = s.replace(anchor, notice_fn, 1)
io.open(p, "w", encoding="utf-8").write(s)
print("ApprovalVoice.notice eklendi")
