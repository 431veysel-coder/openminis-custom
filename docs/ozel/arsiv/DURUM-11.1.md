# 11.1 DURUM — ne hazır, ne değil

Son commit: `c151536` · Sürüm `1.15-ozel.11.1` (37028) · **CI yeşil**
APK doğrulandı: `MinisApp-1.15-ozel.11.1-build37028-arm64-v8a.apk`, 76 MB

---

## Hazır ve APK içinde doğrulandı

| # | Özellik | Kanıt |
|---|---|---|
| 1 | Çeviri paketleri APK'ya gömülü | `assets/packs/` içinde 3 dosya |
| 2 | Cihaz dili → paket eşleme, önce onlar | `languagesMatch` + 4 test |
| 3 | Çevrimdışı katalog (boş ekran yok) | `catalog()` ağ yoksa gömülüyü döner |
| 4 | Sağlayıcı bağlantı testi | dex'te `ProviderConnectionTest`, 401/403/404/429 ayrı |
| 5 | Notlar + Kasa | `NoteStore`, `VaultCrypto`, `NoteTool` dex'te |
| 6 | Ajan notları prompt'ta | enjeksiyon metni dex'te doğrulandı |
| 7 | Minis Özel hub (gruplu) | Notlar/Kasa, İstasyon, Sağlayıcılar |
| 8 | Evren ön ayarı kaldırıldı | kodda tek iz yok |
| 9 | Vercel AI Gateway | `ai-gateway.vercel.sh` dex'te, 509 model |
| 10 | Ücretsiz model tarama + rozet | "Scan for free models", "limit tracked" kaynak tablosunda |
| 11 | Günlük bütçe sayacı | her biten istekte, satıcı saat diliminde |
| 12 | 12 sağlayıcı limit profili | `assets/free-tier-limits.json` |

---

## EKSİK — henüz yok

| # | Özellik | Durum | Not |
|---|---|---|---|
| A | **İstasyon (çoklu panel)** | **hiç yazılmadı** | Mimari belgesi hazır ([ISTASYON-MIMARISI.md](ISTASYON-MIMARISI.md)), kod yok |
| B | **Düzenleme modu kalemi** | **hiç yazılmadı** | Yalnız özellik anahtarı var; overlay yok |

Bu ikisi 11.1'in en büyük iki işiydi. Dürüst olmak gerekirse: diğer 12 madde bitti,
bu ikisi başlamadı. "Hazır" demek için bunlar şart.

---

## Test edilmesi gerekenler (cihazda, elle)

Kurulum: Release'ten APK indir ya da GitHub Actions artefaktını yükle.

### 1. Çeviri paketleri
- [ ] Ayarlar › Minis Özel › Çeviri › Paketler → **açılışta liste dolu gelmeli**
- [ ] Cihaz dili Türkçe ise `Türkçe — Teknik` ve `Türkçe — Kod/CLI` **en üstte**, "cihaz dili" rozetiyle
- [ ] Uçak modunda aç → liste **hâlâ dolu**, altta "depoya ulaşılamadı" notu
- [ ] Bir paketi kaldır → "Gizlenen paketler" altında görünür, geri getirilebilir

### 2. Sağlayıcı bağlantı testi
- [ ] Sağlayıcı detay › "Bağlantıyı test et" → yeşil, "N model listelendi"
- [ ] Anahtarı boz → "anahtar reddedildi (HTTP 401)"
- [ ] Temel URL'ye `/v1/v1` ekle → "bu adreste model listesi yok (HTTP 404)"

### 3. Notlar + Kasa
- [ ] Ayarlar › Minis Özel › Notları aç → Notlar sekmesi
- [ ] Kasa sekmesi → yeni kayıt, değer `••••••`, "Göster" çalışıyor
- [ ] Sohbette: "bunu not al: X" → ajan `note` tool'unu çağırır
- [ ] Uygulamayı kapat-aç → notlar duruyor
- [ ] Yeni sohbet aç → ajan notu **hatırlıyor** (prompt enjeksiyonu)

### 4. Ücretsiz modeller
- [ ] Vercel ekle (anahtar) → detay › "Ücretsiz modelleri tara" → "N tanesi ücretsiz"
- [ ] Model seçicide o modellerde **yeşil "ücretsiz"** rozeti
- [ ] OpenRouter ekle → `:free` modeller rozetli
- [ ] Günlük limit gir (ör. 50) → birkaç istek at → "3 / 50 requests" artıyor

### 5. Kasa güvenliği (kritik)
- [ ] Ajan kasayı okuyamıyor (varsayılan): "kullanıcı izin vermedi" hatası
- [ ] "Ajan kasayı okuyabilsin" aç → okuyabiliyor
- [ ] Ajana "kasaya yeni kayıt ekle" dedirt → **reddedilmeli**

---

## Sıradaki iş sırası

1. **Düzenleme modu** (küçük, bağımsız, ~400 satır) — kalem overlay + not kaydı
2. **İstasyon A** (2 panel, araçsız) — ~800 satır
3. **İstasyon B** (panel başına terminal + araç) — ~1200 satır
4. **İstasyon C** (3–4 panel, tarayıcı) — ~600 satır
5. Örnek silinince sayaç temizliği (küçük)

Her biri ayrı commit + ayrı CI yeşili.
