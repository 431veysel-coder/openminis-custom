import re, io, sys

EN = {
 "approval_mode_header": "When to ask",
 "approval_mode_footer": "One decision here covers every chat. Irreversible actions and anything reaching outside the sandbox always ask — that is the sandbox boundary, not a preference.",
 "approval_mode_settings_only": "Ask only for what cannot be pre-granted",
 "approval_mode_settings_only_sub": "Default. Everything safe is allowed app-wide; cards appear only for irreversible or off-device actions.",
 "approval_mode_allow_all": "Allow everything except hard blocks",
 "approval_mode_ask_each": "Ask in every chat",
 "approval_mode_ask_each_sub": "The old behaviour: each risky call opens a card until you grant it.",
 "approval_mode_allow_all_sub": "No cards at all except the fixed blocks (wiping the system, formatting disks). Use caps stay on.",
 "approval_voice_header": "Spoken approval alert",
 "approval_voice_footer": "When a card is waiting, the phone says so, vibrates and leaves a notification. It stops the moment the card is answered, times out or is cancelled.",
 "approval_voice_enabled": "Read the card aloud",
 "approval_voice_state_off": "Off — notification only",
 "approval_voice_state_once": "Says it once",
 "approval_voice_state_repeating": "Repeats every %1$d s until answered",
 "approval_voice_repeat_once": "Say it only once",
 "approval_voice_repeat_once_sub": "Off means it keeps repeating while the card waits.",
 "approval_voice_speak_operation": "Include the command",
 "approval_voice_speak_operation_sub": "Adds the operation itself, clipped to 70 characters.",
 "approval_voice_vibrate": "Vibrate",
 "approval_voice_vibrate_sub": "Two short pulses, distinguishable from a normal notification.",
 "approval_voice_repeat_every": "Repeat every %1$d s",
 "approval_voice_try": "Try it now",
 "approval_voice_try_sub": "Speaks a sample line with your current settings.",
 "approval_voice_notif_title": "Waiting for your approval",
 "approval_voice_channel_name": "Approval requests",
 "approval_voice_channel_desc": "A tool call is waiting for your decision.",
}

TR = {
 "approval_mode_header": "Ne zaman sorulsun",
 "approval_mode_footer": "Buradaki tek karar bütün sohbetler için geçerli. Geri alınamaz işler ve sandbox dışına taşanlar her zaman sorar — bu bir tercih değil, sandbox sınırı.",
 "approval_mode_settings_only": "Yalnız önceden izin verilemeyenler için sor",
 "approval_mode_settings_only_sub": "Varsayılan. Güvenli olan her şey uygulama geneli izinli; kart yalnız geri alınamaz ya da cihaz dışı işlerde çıkar.",
 "approval_mode_allow_all": "Sabit engeller dışında her şeye izin ver",
 "approval_mode_ask_each": "Her sohbette sor",
 "approval_mode_ask_each_sub": "Eski davranış: izin verilene kadar her riskli çağrıda kart açılır.",
 "approval_mode_allow_all_sub": "Sabit engeller (sistemi silmek, disk biçimlendirmek) dışında kart yok. Kullanım tavanları yine de çalışır.",
 "approval_voice_header": "Sesli onay uyarısı",
 "approval_voice_footer": "Kart beklerken telefon söyler, titrer ve bildirim bırakır. Kart cevaplanınca, süresi dolunca ya da iptal edilince susar.",
 "approval_voice_enabled": "Kartı sesle oku",
 "approval_voice_state_off": "Kapalı — yalnız bildirim",
 "approval_voice_state_once": "Bir kez söyler",
 "approval_voice_state_repeating": "Cevaplanana kadar %1$d sn'de bir tekrarlar",
 "approval_voice_repeat_once": "Yalnız bir kez söyle",
 "approval_voice_repeat_once_sub": "Kapalıysa kart bekledikçe tekrarlamaya devam eder.",
 "approval_voice_speak_operation": "Komutu da söyle",
 "approval_voice_speak_operation_sub": "İşlemin kendisini ekler, 70 karakterde keser.",
 "approval_voice_vibrate": "Titreşim",
 "approval_voice_vibrate_sub": "İki kısa darbe; normal bildirimden ayırt edilir.",
 "approval_voice_repeat_every": "%1$d sn'de bir tekrarla",
 "approval_voice_try": "Şimdi dene",
 "approval_voice_try_sub": "Geçerli ayarlarınla örnek bir cümle söyler.",
 "approval_voice_notif_title": "Onayın bekleniyor",
 "approval_voice_channel_name": "Onay istekleri",
 "approval_voice_channel_desc": "Bir araç çağrısı kararını bekliyor.",
}

def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

def patch(path, mapping):
    text = io.open(path, encoding="utf-8").read()
    added = 0
    for k, v in mapping.items():
        if re.search(r'<string name="%s"' % re.escape(k), text):
            continue
        block = '    <string name="%s">%s</string>\n' % (k, esc(v))
        text = text.replace("</resources>", block + "</resources>")
        added += 1
    io.open(path, "w", encoding="utf-8").write(text)
    print(path, "eklenen:", added)

base = "/var/minis/shared/mxrepo/src/android/app/src/main/res"
patch(base + "/values/strings.xml", EN)
patch(base + "/values-tr/strings.xml", TR)
