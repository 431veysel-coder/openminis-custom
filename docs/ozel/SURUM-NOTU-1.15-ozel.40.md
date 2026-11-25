# SÜRÜM NOTU — 1.15-ozel.40 / 37121

> **Yayındaki sürüm:** 1.15-ozel.34 / 37115. **Kapılar:** 11/11 · 49.6 s.
> **Derleme yapılmadı.**

Kullanıcı "kararı bende olanları önerilerin neyse onları uygula" dedi. Bu turda
kod yazmadım, **karar** verdim ve kararları kodun içine yazdım.

## Ölçüm: 27 anahtar → 25 · 21 açık+görünür · **0 açık+görünmez** · 4 kapalı

| Anahtar | Karar | Gerekçe |
|---|---|---|
| `REPLAY` | **açıldı** | `ReplayStore` bağlı (`integration_audit`: BAĞLI), Ayarlar'da satırı var. Bağlı bir yüzeyi kullanıcıdan saklamak, ölçümü yok saymaktı |
| `EVIDENCE_GATE` | **açıldı** | Kanıtsız "bitti"de tur **durmuyor**, ikinci turdan sonra hatırlatıcı ekliyor. Geri dönüşü olan bir uyarı; kapalı tutmanın tek etkisi eski hatayı geri getirmek |
| `AUTO_BACKUP` | **açıldı** | Ayrım KDoc'a yazıldı: anahtar açık ≠ yedek al. `AutoBackupPolicy` varsayılanı `enabled=false`, yani gerçek yedek yine kullanıcı istemeden başlamıyor. `.36`'daki `gorunurluk` kapısı bu ayrımı yazamadığı için anahtarı görünmez sayıyordu |
| `TOOL_ROUTER` | **kapalı kaldı** | İlk turda doğru araç oranını düşürebilir. Ölçüm yok: "token tasarrufu kanıtsız" — ölçüm eklendiğinde açılacak |
| `DEV_SERVER` | **kapalı kaldı** | Ağa port açar; sağlayıcı anahtarlarını okuyabilen yerel bir HTTP yüzeyi. Varsayılanı açmak, tek güvenlik varsayımını kırar |
| `VAULT_AGENT_READ` | **kapalı kaldı** | Kasa ajana okunmaz. Açmak, sırrı sağlayıcıya sohbetin parçası olarak göndermek demek |
| `CHAT_HEALTH` | **kapalı kaldı** | `.36`'da ölçümle kapatıldı (`ChatHealthWiring` 0 çağrı). Yeniden açılma şartı KDoc'ta yazılı |
| `STATION` | **silindi** | `ui/station/` hiç yazılmadı, 0 çağrı. **Kapalı bir anahtar var olmayan bir özelliği reklam eder** — silmek dürüst olan |
| `REMOTE_CONSOLE` | **silindi** | `remote/RemotePolicy.kt` jeton modeli olarak duruyor ama anahtar bir vaat taşıyordu ve yüzeyi yoktu. Karar: **kod kalsın, vaat kalksın** |

Silmenin ölçülebilir sonucu: `gorunurluk` çıktısındaki `ui=0` kapalı satır sayısı
2 → **0**. Yani artık "görünmez ama açık" ya da "vaat edip göstermeyen" tek bir
anahtar yok.

## `AUTO_BACKUP` dersi (kayıt)

`.36`'da bu anahtarı `ui=0` gördüm ve "görünmez" saydım. Gerçek farklıydı:
anahtar `OzelFeaturesScreen`'de **yok** (görünürlük 0) ama davranışı
`AutoBackupTrigger` üzerinden **var**. Yani iki ayrı soru vardı:

- "Kullanıcı bu anahtarı görebiliyor mu?" → hayır (satır yoktu),
- "Anahtar açık olsa bir şey olur mu?" → evet.

Kapı yalnız birincisini soruyor. Bu turda anahtarı açtım çünkü ikinci sorunun
cevabı "evet" ve kullanıcı satırı görmeli. Kapının bu ayrımı ölçmesi `.45`'e
(yeni bir kural: `ui=0` ama `enabled=true` → anahtarın **niyeti** KDoc'ta
yazılı olmalı) bırakıldı.

## Kapılar

```
11/11 geçti · 0 uyarı · 49.6s
gorunurluk: 25 anahtar · 21 acik+gorunur · 0 acik+gorunmez · 4 kapali
```

## Kapanan

| Madde | Durum |
|---|---|
| P0-3 kalanı (8 kapalı anahtarın kararı) | ✅ **kapandı** — 3 açıldı, 2 silindi, 3 gerekçeli kapalı |
| "Vaat eden ama göstermeyen anahtar" sınıfı | ✅ kapandı (0 `ui=0` kapalı anahtar) |

## Sırada

`.41` konsey derinliği (matris, ≈ eşleme, ret listesi) · `.42` alt ajan/ekip
yüzeyi + Terminal 2.2–2.4 · `.43` çeviri + taban eritme · `.44` ürün kapanışı
(TalkBack, render, dil) · `.45` tam denetim + APK (izinle).
