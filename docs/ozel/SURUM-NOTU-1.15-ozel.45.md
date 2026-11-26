# SÜRÜM NOTU — 1.15-ozel.45 / 37125

> **Önceki yayın:** `v1.15-ozel.43` / 37124 · **Kapılar:** 11/11
> **Derleme:** CI (bu dalda artık gerçekten koşuyor — `.44`'ün asıl bulgusu)

Bu sürüm `.44`'ten devraldığı üç açık maddeyi kapatıyor ve **ilk kez**
tüm özelliklerin bağlı olduğu ölçülmüş bir durumda yayınlanıyor.

## 1 · §8.4 Erişilebilirlik — tablo artık okunabilir

Matris hücresi görsel olarak tek karakterdi: `✓`. TalkBack onu "onay işareti"
diye okur; hangi üye, hangi iddia, hangi duruş belirsiz kalırdı — yani tablonun
**tüm bilgisi** görme engelli kullanıcı için erişilemezdi.

- Yeni `ui/team/TeamA11y.kt` (saf, testli): hücre etiketi
  `"Ayşe · C3 · destekliyor"`. Duruş kelimesi yerelleştirilmiş, gerisi veriden.
- `CouncilScreens` hücresine `contentDescription`, sekme şeridine
  `Role.Tab` + `selected`. `selected` olmadan TalkBack hangi odada olduğunu
  söylemiyor; kullanıcı ekranı her seferinde yeniden keşfediyordu.
- `TeamA11yTest` — **6** test.

Dokunma hedefleri: sekme `TextButton`, eylem `SettingsRow` — ikisi de M3
varsayılanıyla ≥48 dp. Elle ölçü eklemedim, çünkü olmayan bir sorunu
"düzelttiğimi" yazmak istemedim.

## 2 · §8.1 Boş durum eylemleri

Boş durum "neden boş" diyordu ama "ne yapayım" demiyordu: kullanıcı altı odaya
girip tek cümle görüp çıkıyordu.

- Koşu listesinin boş durumuna eylem satırı eklendi.
- `onStartRun` **varsayılanı `null`**: çağrı yeri hedef bağlamadıysa düğme
  çizilmez. Hedefi olmayan düğme, bu depoda kınanan "vaat" sınıfı — tıklanınca
  hiçbir şey olmayan satır "düğme bozuk" dedirtir.

## 3 · §7.3 Çeviri tabanı: 481 → **441**

İkinci batch (40 anahtar) `.ozel/ceviri/batch/02.json`. Toplam iki turda
**80 anahtar** çevrildi (521 → 441). Araç `ceviri-erit.py`, batch geri
alınabilir; taban yalnız gerçekten çözülen anahtar için daralıyor.

## Ölçülen entegrasyon durumu (bu sürümün asıl iddiası)

```
integration_audit  : 11/11 dilim üretim kodundan çağrılıyor
gorunurluk         : 25 anahtar · 21 açık+görünür · 0 açık+görünmez · 4 kapalı
ceviri             : taban 441 · 0 YENİ eksik → YEŞİL
kapi               : 11/11 · 0 uyarı
```

**0 açık+görünmez**: "anahtar açık ama kullanıcı hiçbir şey görmüyor" sınıfı
sıfır. Geriye kalan 4 kapalı anahtarın her biri ya ölçümle kapatıldı
(`CHAT_HEALTH`, 0 çağrı) ya da tek güvenlik varsayımını koruyor
(`DEV_SERVER` port açar, `VAULT_AGENT_READ` sırrı sağlayıcıya gönderir,
`TOOL_ROUTER` ölçüm bekliyor) ve dördünün de gerekçesi KDoc'unda yazılı.

## Bu seride ne değişti (.35 → .45, 11 sürüm)

| Sürüm | Ana iş |
|---|---|
| .35 | Onay modu (3 kademe) + sesli onay uyarısı |
| .36 | **11. kapı `gorunurluk.py`** + `CHAT_HEALTH` ölçümle kapatıldı |
| .37 | `screen` aracı (8 eylem) + onay tamamen kalktı (`ALLOW_ALL`) |
| .38 | Tek tuval yerleşimi + kare üreticisi (durum karesi, etiketli) |
| .39 | `DnsBootstrap` — konuk kökü DNS kalıcılığı |
| .40 | 8 kapalı anahtarın kararı (3 aç / 2 sil / 3 gerekçeli) |
| .41 | Karşılaştırma matrisi (sabit iddia sütunu, yatay kaydırma) |
| .42 | Konsey katılım satırı + kuyruk görünürlüğü |
| .43 | Çeviri tabanı 521→481 + sözlük dürüstlüğü testleri |
| .44 | **CI derleme boşluğu** + 16 gerçek derleme hatası |
| .45 | Erişilebilirlik + boş durum eylemleri + taban 441 |

## Dürüst kalan (yayın sonrası)

1. **Canlı içerik yok**: `screen capture` gerçek PNG üretiyor ama içinde
   sayfa/terminal değil **bölme durumu** var; karenin altında
   `state frame — live page/terminal content is not rendered yet` yazıyor.
2. **Cihazda sınanmadı**: sesin Türkçe çıkması, `ALLOW_ALL`'ın kart
   çıkarmaması, `getent hosts github.com`.
3. **TalkBack turu**: etiketler yazıldı ve test edildi, ama gerçek TalkBack
   oturumu cihazda koşulmadı.
4. **Çeviri 441**: eritme devam ediyor, batch başına 40.
