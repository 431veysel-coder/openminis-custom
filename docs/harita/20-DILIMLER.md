<!-- ÜRETİLDİ: scripts/ozel/harita.py — elle düzenlemeyin. -->

# Dilimler — 13 birim

| Dilim | Ad | Not | Dep |
|---|---|---|---|
| `37080` | Onay kapısı | komut risk sınıflandırma + kapsam defteri + politika + kuyruk | 0 |
| `37081` | Bütçe tavanı | sohbet/gün/ay üç kademe; tavanda yeni tur başlamaz | 0 |
| `37082` | Otomatik yedek zamanlaması | aralık + pencere; değişiklik yoksa çalışmaz; budama tarihe göre | 0 |
| `37083` | Plan önizleme | pahalı işten önce tek kart; eşik ölçülebilir | 0 |
| `37084` | Tur özeti diff'i | tur sonunda '3 dosya +120/−8' + geri alma sırası | 0 |
| `37085` | Araç kapısı | niyet sözlüğü → 6-10 aday araç; LLM'siz | 0 |
| `37086` | Ders defteri | hata/düzeltme/döngü → öneri; kendiliğinden yazmaz | 0 |
| `37087` | Kanıtlı 'bitti' | beyan son 3 turda üretilmiş kanıt ister | 0 |
| `37088` | Yazma niyeti | çok adımlı iş boyunca bloklamayan beyan + çakışma sicili | 1 |
| `37089` | Yeniden oynatma | araç çıktıları dondurulmuş yeniden koşu; ıskalama metrik | 0 |
| `37090` | Uzaktan konsol jeton modeli | yalnız yetki modeli; uygulama yok (ürün kararı bekliyor) | 0 |
| `37095` | Sohbet sağlık göstergesi (çekirdek) | TTFB/p50, tekrar deneme, hata etiketi, biçimlendirme — Android'siz | 0 |
| `37098` | Termux katmani (cekirdek: karar mantigi) | TermuxInstaller + TermuxPrompt baglanti katmanidir (android.* import e | 0 |

Kural: bir dilim kendi dosyaları + `deps` ile **Android'siz** derlenebilmeli.
Kapı `android.*` importunu hata sayar. Kaynak: `scripts/slices.json`.
