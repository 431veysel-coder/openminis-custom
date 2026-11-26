# SÜRÜM NOTU — 1.15-ozel.48 / 37128

> **Önceki yayın:** `v1.15-ozel.47` / 37127

## 1 · Durdur düğmesi — ve "durduruldu" diye bir sonucun ilk kez var olması

### Bulgu

`CouncilStop.CANCELLED` enum'da **tanımlıydı** (`CouncilModels.kt:270`). Ölçüm:
ağaçta onu **üreten tek bir satır yoktu**; yalnız rapor metninde okunuyordu.

Daha kötüsü: `AndroidCouncilRuntime.run`'da `CancellationException` yakalanıp
**yeniden fırlatılıyordu**. `finally` temizliği yapıyordu ama **kayıt yazılmıyordu**
— çünkü `TeamRunStore.record(...)` `try` bloğundan **sonra** geliyor ve istisna
oraya hiç ulaşmıyordu.

Yani bugüne kadar: kullanıcı durdurursa **koşu tamamen kaybolurdu**. Ne listede,
ne diskte. "Durdurdum ve her şey gitti" — tam olarak bu.

### Ne yapıldı

- **`CouncilRunRegistry`** (yeni, saf, 7 test): `runId → iptal eylemi`.
  Neden `CouncilLiveRegistry`'ye eklenmedi: o bir **görüntü** kaydı
  (`StateFlow<LiveRun>`), iptal bir **kontrol** işi. İki farklı sorumluluğu aynı
  nesneye koymak, bu depoda daha önce iki liste tipini birleştirmeye çalışıp
  derlemeyi düşürmeme benziyor. Ayrıca `Job` değil `() -> Unit` saklıyor: test
  gerçek coroutine kurmadan iptalin **çağrıldığını** doğruluyor.
- **`TeamRunStore.recordCancelled`**: yarım koşunun kaydı. Uydurma yok —
  `verified = false`, `consensus` **boş**, `matrix` **null**, rapor dosyası
  **yazılmaz**. Hepsi "olmayanı olmuş gibi göstermemek" için.
- **`CouncilScreen`**: yalnız koşarken görünen `Durdur` düğmesi + onay diyaloğu.
  Onay var çünkü iptal geri alınamaz: kalan turlar harcanmaz ama yarım koşu
  kaydedilir.
- `finally` bloğunda `forget` — bırakılmasa her koşu için bir lambda süreç
  ömrü boyunca birikir (bu özelliğin önlemeye çalıştığı sızıntının ta kendisi).

### Dürüstlük notu

Kaydedilen şey **sentez raporu değil**: iptal, sentez aşamasından önce oluyor.
Kayıtta `stoppedBy=CANCELLED`, o ana kadarki tur ve token sayısı var; "karar"
yok, çünkü karar oluşmadı. Ekran bunu `statusLine()` ile zaten "DOĞRULANMADI"
diye gösteriyor.

## 2 · Kapı altyapısı: 1102 s → 45.8 s

`.47`'de altı kapı sahte KIRMIZI verdi (600 s zaman aşımı). Sebep `gorunurluk`'tu:
`count_refs` her özellik anahtarı için ağacı **yeniden** yürüyordu
(`rglob("*.kt")`), 25 anahtar × 2 tarama = **50 tam dizin yürüyüşü**.

Düzeltme: ağaç **bir kez** yürünür (`_walk` önbelleği), 25 desen bellekteki
listeye uygulanır.

| | Önce | Sonra |
|---|---|---|
| `gorunurluk` tek başına | 302.9 s | **6.7 s** |
| Kapılar paralel | zaman aşımı (1102 s) | **45.8 s · 11/11** |

Dosya **okuma** maliyeti değişmedi (`_CACHE` zaten vardı); kalkan şey dosya
sistemi dolaşması. Kapı zaman aşımı 600 s'de kalıyor — artık bolca yeter, ama
`.51`'de geri çekilecek.

## 3 · Plan değişikliği: yerel model iptal

Kullanıcı kararı: **"Yerel model çalıştırma olmayacak, tamamen anahtarlar
üzerinden yapay zekaları kullanıyoruz. Uygulamanın amacı bu zaten."**

`PLAN-KONSEY-OFIS-DONANIM.md` §6'da `.54`/`.55` (llama.cpp sandbox CLI)
**İPTAL** olarak işaretlendi. Tablo ve gerekçe **tarihsel kayıt** olarak
duruyor — bir gün yerel çıkarım gerekirse hangi yolun seçilmiş olduğunu
hatırlatır. Konseyin tek üye kaynağı: **sağlayıcı anahtarları**.

## Ölçüm

```
kapi (paralel)     : 11/11 · 0 uyarı · 45.8 s
gorunurluk         : 25 anahtar · 21 açık+görünür · 0 açık+görünmez
integration_audit  : 11/11
ceviri             : taban 441 · 0 YENİ
yeni test          : 7 (CouncilRunRegistry)
yeni dize          : 3 × 2 dil
```

## Sıradaki

`.49` — sohbetten konsey: `sessions.team_id` + proje varsayılanı devralması,
üst bar simgesi, üç durumlu alt sayfa. `Durdur` artık var, simge ona yaslanabilir.
