import io, pathlib
p = pathlib.Path("/var/minis/shared/mxrepo/src/android/app/src/main/java/com/openminis/app/ozel/ApprovalVoiceCore.kt")
s = io.open(p, encoding="utf-8").read()
anchor = '''    /** Bildirim gövdesi — ses cümlesinin yazı hâli, tek satır. */'''
new = '''    /**
     * [37118] **Bilgi** cümlesi — kart yokken söylenen.
     *
     * Fark tek kelimede: "Onay bekliyorum" değil "Şunu yapıyorum". Bekleyen bir
     * soru olmadığı için "karta dokun" da yok; yerine ne olduğu ve geri dönüşü
     * söylenir. Aynı cümleyi kullanmak, kullanıcıyı cevaplanacak bir kart
     * aramaya gönderirdi (ve 5 dakika sonra "neden bir şey olmadı" sorusu).
     */
    fun noticeLine(request: ApprovalRequest, speakOperation: Boolean = true): String {
        val parts = mutableListOf("Şunu yapıyorum.")
        parts += "${request.title}."
        if (speakOperation) operation(request)?.let { parts += "$it." }
        recoveryPhrase(request)?.let { parts += "$it." }
        reachPhrase(request)?.let { parts += "$it." }
        parts += "Ayarlar'dan onay isteğini geri açabilirsin."
        return parts.joinToString(" ")
    }

''' + anchor
assert anchor in s
s = s.replace(anchor, new, 1)
io.open(p, "w", encoding="utf-8").write(s)
print("noticeLine eklendi")
