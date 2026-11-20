# TOPLAMA DEFTERİ — hangi oturum ne üretti

> Kural: **hiçbir şey birleştirilmedi.** Her oturum kendi dosyalarını ayrı tutuyor.
> İşler bitince burada tek tek birleştirilip düzenlenecek. Bir oturumun dosyası
> diğerinin üzerine yazılmaz.

Son güncelleme: 2026-11-18 · kaynak ağaç `/root/om` · **hiçbiri derlenmedi / push edilmedi**

---

## Koleksiyonlar

| Klasör | Oturum / hat | Dilimler | Durum |
|---|---|---|---|
| `konsey-dosyalari/` | Bu sohbet — **konsey (çoklu model tartışma)** | 37037, 37050–37052, 37055, 37060–37065 | 32 yeni dosya (7.012 satır), 13 düzenlenmiş, 118 test |
| *(diğerleri)* | Başka oturumlar — proje hafızası 37070+ | 37070–37079 | **Henüz toplanmadı** — o oturumlar sürüyor |

## Neden ayrı tutuluyor

Aynı çalışma ağacında iki ajanın düzenlediği tek dosya `ui/chat/ChatViewModel.kt`.
Bölgeler çakışmıyor (benim 24, onların 14 bölgesi, etiketli) ama:

- biri diğerinin kopyasından yazarsa **diğerinin işi kaybolur**;
- 20 bin satırlık dosyada elle birleştirme, birleştiren kişinin dikkatine kalır.

Bu yüzden `konsey-dosyalari/MANIFEST.md` §2, benim bölgelerimin **satır satır**
listesini tutuyor. Birleştirme o tabloya göre yapılacak.

## Birleştirme sırası (önerilen)

1. **Yeni dosyalar** — çakışma yok, doğrudan kopyalanır.
2. **Tek oturumun düzenlediği dosyalar** — kopyalanır, ama önce `git diff` ile
   aradan başka bir oturum geçmiş mi bakılır.
3. **Paylaşılan dosyalar** (`ChatViewModel.kt`) — **en son**, hunk hunk, etiketlere göre.
4. `strings.xml` — anahtar çakışması yok (konsey: `teams_*` / `council_*` / `task_*`).
5. Birleştirme sonrası **tek** `preflight` + `audit_all` + ilk CI derlemesi.

## Bilinen ortak zemin (çakışmayan, ikisi de kalmalı)

```kotlin
// ui/chat/ChatViewModel.kt
internal val fsSessionId get() = helperConfig?.parentSessionId   // alt ajan: ebeveynin alanı
    ?: spaceId                                                   // KONSEY: üyenin kendi alanı
    ?: projectSpaceKey                                           // PROJE: projenin alanı
    ?: activeSessionId                                           // düz sohbet
```

Sıralama doğru: üye > proje > sohbet. `sandbox/FsSpace.kt` (diğer oturumun) `sp_<runId8>_<member>`
biçimini zaten tanıyor ve `minis-sessions/` altında bırakıyor → konseyin çalışma dizini
süpürücüsü (`TeamRunStore.sweepOrphanSpaces`) onunla uyumlu.

## Doğrulama durumu

- `preflight.py` → temiz
- `audit_all.py` → PASSED (0 yeni hata, 0 yeni uyarı)
- **Derleme yok** — sandbox'ta `kotlinc` yok, tek derleyici CI. İlk gerçek derleme
  birleştirmeden sonra yapılmalı; iki hat birleşince çıkacak hatalar tek tek
  hata ayıklanabilsin.
