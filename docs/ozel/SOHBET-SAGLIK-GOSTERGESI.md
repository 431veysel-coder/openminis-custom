# Sohbet sağlık göstergesi — plan (37095)

> Kullanıcının tarifi: "sağ üst köşede ping gibi veriler olmalı, canlı güncellensin,
> modellerin cevap verip vermediği görülsün, çok büyük yazılar istemiyorum —
> küçük rakamlarla ve ikonla belli olsun."

## 1. Sorun — bugün ne görünmüyor

Sohbet ekranında yanıt beklerken kullanıcının elinde tek bir işaret var: üç nokta.
Şu dört durum **aynı** görünüyor:

| Gerçek durum | Bugünkü görüntü |
|---|---|
| İstek yolda, sağlayıcı henüz ilk parçayı göndermedi | ⋯ |
| Sağlayıcı 429 verdi, 6 saniyelik geri çekilme sayacı işliyor | ⋯ |
| Model düşünüyor (reasoning deltası geliyor, metin yok) | ⋯ |
| Bağlantı koptu, yeniden deneniyor (2/3) | ⋯ |

Sonuç: kullanıcı "takıldı mı, yavaş mı, koptu mu" ayrımını yapamıyor ve
"tekrar denemelere düşüyor" hissi doğrulanamıyor. Ölçüm **zaten var** (kodda
`streamStartMs`, `firstChunkSeen`, `_autoRetryAttempt`, `_autoRetryCountdown`,
`turnStreamMs`) ama hiçbiri ekrana çıkmıyor — yalnız `[T-STALL-DIAG]` log
satırlarında.

## 2. Karar — ne gösterilecek

Üç katman, hepsi **tek satır**, yüksekliği 24 dp'yi geçmez:

```
[● 820ms]        boşta: son yanıtın ilk parça gecikmesi, yeşil nokta
[◌ 3.2s]         bekliyor: geçen süre, canlı sayar, gri
[● 640ms 14t/s]  akıyor: TTFB + saniyedeki jeton, yeşil
[↻ 2/3 6s]       tekrar deneme: kaçıncı deneme + geri çekilme sayacı, sarı
[! 503]          hata: HTTP kodu / hata sınıfı kısaltması, kırmızı
[⊘ —]            çevrimdışı: sağlayıcıya hiç ulaşılamıyor, kırmızı
```

Tasarım kuralları (bunlar tartışmaya açık değil, ölçülebilir):

1. **Genişlik sınırı 96 dp.** Uzun model adı başlıkta kalır; chip asla başlığı
   sıkıştırmaz. Sayı 3 karakteri geçerse birim atılır (`1.2k`).
2. **Punto 10 sp, ikon 10 dp.** Başlık 16 sp; chip onun yanında ikincil kalır.
3. **Renk tek başına anlam taşımaz.** Her durumun ayrı ikonu var: renk körlüğü
   ve gri tonlamada da ayırt edilir. `contentDescription` durumu cümleyle söyler.
4. **Titreşimsiz sayı.** Ham son ölçüm değil, son 20 ölçümün **medyanı (p50)**
   gösterilir; tek bir yavaş yanıt rakamı zıplatmasın. p90 ayrıntı kartında.
5. **Boştayken sıfır uyandırma.** Canlı sayım yalnız `WAITING / STREAMING /
   RETRYING` durumlarında 250 ms'de bir; `IDLE / ERROR` durumunda zamanlayıcı
   tamamen durur. Pil maliyeti ölçülebilir olsun diye.
6. **Sayaç monoton saatten.** `SystemClock.elapsedRealtime()`; duvar saati
   değişirse negatif süre üretmez.

## 3. Durum makinesi (tek kaynak, Android'siz)

`ozel/health/ChatHealth.kt` — saf Kotlin, `android.*` importu yok, dilim
kapısından tek başına geçer.

```
IDLE ──requestStarted──> WAITING ──firstChunk──> STREAMING ──finished──> IDLE
                            │                       │
                            ├──retryScheduled──> RETRYING ──> WAITING
                            └──failed──────────> ERROR ──requestStarted──> WAITING
                     herhangi ──offline──> OFFLINE
```

`ChatHealthTracker(clock, windowSize = 20)`:
- `requestStarted(atMs)` — WAITING, sayaç başlar, `retry` korunur
- `firstChunk(atMs)` — STREAMING, TTFB hesaplanır, pencereye eklenir
- `retryScheduled(attempt, max, backoffMs)` — RETRYING
- `finished(atMs, outputTokens)` — IDLE, toplam süre + jeton/sn hesaplanır
- `failed(kind, httpStatus)` — ERROR, kısa etiket (`429`, `503`, `401`, `NET`)
- `offline()` — OFFLINE
- `reset()` — oturum değişince

## 4. Ölçümün bağlanacağı 4 nokta (ChatViewModel)

| Nokta | Satır (bugün) | Ne yapılır |
|---|---|---|
| istek gönderilmeden hemen önce | ~12224 `streamStartMs` | `tracker.requestStarted(now)` |
| ilk parça | ~12355 `firstChunkSeen.compareAndSet` | `tracker.firstChunk(now)` |
| akış `finally` | ~12794 `turnStreamMs +=` | `tracker.finished(now, outputTokens)` |
| `catch (e: Exception)` | ~12800+ | `tracker.failed(kindOf(actual), httpOf(actual))` |
| geri çekilme | `_autoRetryCountdown` yazan yer | `tracker.retryScheduled(...)` |

`health: StateFlow<ChatHealth>` tek akış; ekran yalnız onu okur. ChatViewModel'de
hesap yok — hesap `ChatHealthTracker` içinde, test edilebilir.

## 5. Arayüz

`ui/chat/ChatHealthChip.kt`:
- `ChatHealthChip(health: StateFlow<ChatHealth>, onOpen: () -> Unit)` → `Row`
- `TopAppBar(actions = { ChatHealthChip(...) })` — başlığın sağında, insets'e uyar
- Dokunma → `ChatHealthSheet`: model, sağlayıcı, TTFB p50/p90, son toplam süre,
  tekrar sayısı, son hata metni (kısaltılmış), jeton/sn, "şimdi ölç" düğmesi.

`şimdi ölç` = mevcut sağlayıcıya 1 jetonluk istek; sonucu chip'e yazar
(`WAITING → STREAMING → IDLE` yolunu elle tetikler). Yeni ağ kodu yok;
var olan `ProviderConnectionTest` kullanılabilir — önce oku, gerekiyorsa uzat.

## 6. Kalıcılık (ömür)

- Bellek: son 20 TTFB ölçümü (p50 için).
- Disk: `filesDir/ozel-health.json`, **halka tampon 50 kayıt**, tur başına en
  fazla bir yazma. Amaç "dün akşam da yavaştı" sorusuna cevap; her jetonu yazmak
  pil ve I/O israfı olur.
- Bozuk dosya → `.bad.json` olarak saklanır, silinmez (mevcut `ozel-approvals.json`
  deseniyle aynı).
- Anahtar: `Feature.CHAT_HEALTH`, varsayılan **AÇIK** (kapalıyken chip görünmez,
  ölçüm de durur — ölü ölçüm yapılmaz).

## 7. Testler (dilim 37095)

`src/test/.../ozel/health/ChatHealthTrackerTest.kt` — 9 vaka, saf JUnit:
1. `requestStarted` → WAITING, `waitingSinceMs` dolu
2. `firstChunk` → STREAMING, TTFB = fark
3. `finished` → IDLE, toplam süre, jeton/sn = token/saniye
4. p50: 5 ölçüm {100,200,300,400,1000} → 300 (medyan, ortalama değil)
5. pencere 20'yi aşınca en eski düşer, p50 yalnız son 20'den
6. `retryScheduled(2,3)` → RETRYING, `2/3`, geri çekilme saniyesi
7. `failed(429)` → ERROR, etiket `429`; `failed(NetworkError)` → `NET`
8. `fmtLatency`: 820 → `820ms`, 3200 → `3.2s`, 12500 → `12s`
9. `reset` → IDLE, son ölçüm korunur ama sayaç sıfır (oturum değişimi)

## 8. Sıra

| Dilim | İş | Doğrulama |
|---|---|---|
| 37095 | `ChatHealth.kt` + 9 test + dilim kaydı | slice-gate (kotlinc, ~1.5 dk) |
| 37096 | ChatViewModel bağlantısı + `health` akışı | `compile` işi (~2 dk) |
| 37097 | `ChatHealthChip` + `ChatHealthSheet` + strings | APK debug koşusu |
| 37098 | `ozel-health.json` halka tampon + ayar satırı | unit test |

Her adım ayrı commit: biri kırılırsa diğerleri birleşebilir (kullanıcının
"99 dilim temizse 99'u birleşsin" kuralı).

## 9. Kapsam dışı (bilerek)

- Sağlayıcı **fiyat/kota** göstergesi — `ModelQuotaStatusDot` zaten var, ikinci
  bir kota göstergesi kafa karıştırır.
- Token sayacı grafiği — `UsageCapsuleRow` mesaj altında var.
- Ağ hızı testi (speedtest) — ping değil, gürültü.
- iOS paritesi — bu ekran Android'e özgü.
