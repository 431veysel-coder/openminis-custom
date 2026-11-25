# SÜRÜM NOTU — 1.15-ozel.36 / 37117

> **Yayındaki sürüm:** 1.15-ozel.34 / 37115 (`.35` de derlenmedi).
> **Kapılar:** 11/11 yeşil. **Derleme yapılmadı** (kullanıcı kuralı).

Bu turun tek işi var: **"kapandı" iddiasını denetlenebilir yapmak.** Yeni
özellik eklemedim; ölçüm aracını dürüstleştirdim.

## 1 · Yeni kapı: `gorunurluk.py` (11. kapı, zorunlu)

Aynı hata sınıfı **dört kez** tekrar etmiş: anahtar açık, kullanıcı hiçbir şey
görmüyor. `integration_audit.py` "BAĞLI" derken **doğruyu söylüyordu**; eksik
olan ikinci soruydu.

| Sürüm | Anahtar | Gerçek |
|---|---|---|
| `.14` | `STATION` | `ui/station/` hiç yazılmadı |
| `.15` | sağlık | çekirdek var, çağrı yok |
| `.34` | `VIRTUAL_SCREEN` | yakalama hattı ve `Op` API'si 0 çağrı |
| `.36` | `CHAT_HEALTH` | `ChatHealthWiring` **hiçbir yerden** çağrılmıyor |

Kapı her anahtara üç soru sorar: **üretimde çağrılıyor mu** · **`ui/` altında
tüketicisi var mı** · **kapalıysa gerekçesi yazılı mı**.

- Taban `.ozel/gorunurluk/taban.json`, **araç üretir** (`--taban-yaz`); elle
  yazmak kapının o günkü hatasını sabitlerdi. Taban yalnız **daralır**.
- `--kendini-sina`: 4 sahte ağaç (görünür açık / yalnız testte / kapalı +
  gerekçe / kapalı + gerekçesiz). **4/4 geçti.** Kendini sınamayan kapı
  "hep temiz" der.
- İlk koşu **10.8 s** (ilk sürüm 52 s idi: her anahtar için ağacı yeniden
  okuyordu; 50 saniyeyi geçen kapı koşulmaz).

**Bugünkü ölçüm:** 27 anahtar · **18 açık+görünür** · **0 açık+görünmez** ·
**9 kapalı**, hepsi gerekçeli.

## 2 · Gerçek bulgu: `CHAT_HEALTH` hiç bağlanmamış

`.16` sürüm notu "sağlık göstergesi bu sürümde **gerçekten bağlandı**" diyordu.
Ölçüm: `ChatHealthWiring.requestStarted/firstChunk/finished` — **0 çağrı**,
yalnız kendi tanımı ve bir yorum. Yani hiçbir sohbet sağlık kaydı üretmiyor,
Canlı Görevler'de çizilecek çip yok.

**Karar:** anahtar `true` → **`false`**. Sebep: kullanıcının gördüğü durum
zaten "hiçbir şey"; açık anahtar yalnız yanlış vaat ekliyordu. Yeniden açılma
şartı KDoc'a yazıldı: `ChatViewModel` üç çağrıyı yapmalı **ve** `LiveTasksScreen`
çipi çizmeli. İkisi yoksa anahtar kapalı kalır.

## 3 · `olu-kod.py` yanlış pozitif onarımı

| Hata | Sonuç |
|---|---|
| Yorumdaki ad "kullanım" sayılıyordu | ölü kod **gizleniyordu** |
| `vm::saveExportTo` (metod referansı) sayılmıyordu | canlı kod **ölü** görünüyordu |
| `vm.previewOrphanCleanup { … }` (trailing lambda) | aynı |
| `@Composable` yalnız tanım satırında aranıyordu | üst satırdaki anotasyon kaçıyordu |

Düzeltilmiş sayı: **313** (eski 250 şişkindi). Doğrulama: `saveExportTo` ve
`previewOrphanCleanup` listeden **düştü** (gerçekten çağrılıyorlar),
`getThemeMode`, `showChatTitleEnabled`, `performShizukuAction`,
`downloadSkillFromUrl`, `resetRestoreState` listede **kaldı** (gerçekten ölü).

## 4 · Belge dürüstlüğü

- `ENTEGRASYON-DURUMU.md`: `.16 / 37097` damgası 20 sürüm eskimişti → `.36 /
  37117`, başına **"BAĞLI ≠ GÖRÜNÜR"** bölümü ve dört vakalık tablo.
- İki eski ölçüm yanlışım kayda geçti:
  - "manifest 29, belge 27" → **belge haklıydı**: 27 + 2 = 29. Hata bendeydi.
  - "250 ölü fonksiyon" → rakam şişkindi, doğrusu 313.
- Test sayısı nasıl okunur: **6 753 var · 262 zorunlu (CI) · 721 dosya
  advisory**. "6 753 test geçti" cümlesi hiçbir zaman doğru olmadı.
- `YAPILACAKLAR.md` damgası `.22` → `.36` + tur tablosu.

## Kapılar

```
apk-butce 1.9s · ceviri 0.5s · denetim 47.6s · entegrasyon 29.2s · etiket 3.7s
gorunurluk 10.7s · harita 7.2s · korpus 2.1s · kotlin 11.6s · olu-kod 0.4s
yapisal 16.7s
11/11 geçti · 0 uyarı · 54.6s
```

## Kapanan / açılan

| Madde | Durum |
|---|---|
| P0-3 "bağlı ama görünmez" sınıfı | **kapandı** — kapı + 1 gerçek bulgu + 1 karar |
| P1-5 `olu-kod` yanlış pozitifi | **kapandı** |
| P1-6 belge damgası / izin sayısı | **kapandı** (izinde hata bendeydi) |
| P1-7 test kapsamı dürüstlüğü | **kapandı** |
| `CHAT_HEALTH` yüzeyi | **açık** → `.41` (ya bağlanır ya kapalı kalır) |

## Sırada

`.37` sanal ekran yakalama + `screen` aracı · `.38` tek tuval · `.39` Terminal
apt · `.40` DNS · `.41` kapalı anahtar kararları (önerilerim uygulanacak).
