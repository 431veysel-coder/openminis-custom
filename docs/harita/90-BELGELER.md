<!-- ÜRETİLDİ: scripts/ozel/harita.py — elle düzenlemeyin. -->

# Belgeler — 85 dosya

| Belge | Başlık | İlk satır |
|---|---|---|
| `docs/ozel/37094-YAYIN-HATTI-DENETIMI.md` | 37094 — yayın hattı denetimi (push öncesi) | `pipefail` eklemeden önce dosyada 10 `| head` ve 8 `| grep -q` vardı. |
| `docs/ozel/BUILD-GECMISI.md` | DERLEME GEÇMİŞİ | `python3 scripts/build_digest.py` ile üretilir. **Elle düzenlemeyin.** |
| `docs/ozel/DEGISIKLIK-GUNLUGU-11-16.md` | Değişiklik günlüğü — `.11.3.1` → `.18` | `.17` hiç yayınlanmadı; bu sürüm onun üstüne binen düzeltme turunu taşıyor. |
| `docs/ozel/DENETIM-37096.md` | DENETİM 37096 — `1.15-ozel.15` kaynak incelemesi ve düzeltmeler | sebep §6. |
| `docs/ozel/DENETIM-37098.md` | DENETİM 37098 — Termux kodunun kendi incelemesi | indirip yapısını doğrulama (`unzip -l`) + korpus koşumu |
| `docs/ozel/DENETIM-37099.md` | DENETİM — `37099` / düzeltme turu | `.17` paketi satır satır yeniden okundu. **Sekiz gerçek hata** bulundu; |
| `docs/ozel/DENETIM-BASLIKLARI.md` | DENETİM BAŞLIKLARI — 13 eksen | Kullanıcının verdiği başlıklar: görev paylaşımı · kodlama · yazılım · güvenlik · |
| `docs/ozel/DILIM-KAPISI.md` | DİLİM KAPISI — her dilim ayrı derlenir, ayrı rapor verir | Tek büyük derlemede 11 dilimin hatası tek log'a düşer ve birbirini maskeler. |
| `docs/ozel/ENTEGRASYON-DURUMU.md` | MINIS X — entegrasyon durumu ve kullanıcı yüzeyi | `scripts/integration_audit.py` yalnız **bir** soruyu cevaplar: *üretim kodu bu |
| `docs/ozel/HARITA.md` | HARİTA — Minis X | Bu dosya "ne, kim için, nasıl" sorusunun tek cevabı. Sayılar **ölçülmüş**; |
| `docs/ozel/IS-LISTESI-2026-11-20.md` | İŞ LİSTESİ — 20 Kasım 2026 | `versionCode` yalnız **yayın anında** yakılır. 37095–37098 aynı sürümde (37092) |
| `docs/ozel/KONSEY-DILIMLERI.md` | Konsey hattı — dilim haritası (37037–37069) | Dal: `toplama-11.4` · taban `db2566e` · **derleme yok** (bu dal `apk.yml` |
| `docs/ozel/OZELLIK-PAKETI.md` | ÖZELLİK PAKETİ — 37080–37090 (11 dilim) | Bu dosya, planlanan işlerin (konsey 37037–37069, proje hafızası 37070–37079, |
| `docs/ozel/PLAN-35-45.md` | PLAN .35 → .46 — tur tur, kategori kategori, madde madde | Tek cümle: **çekirdek sağlam, kabuk yarım; belgeler bir sürüm geride.** |
| `docs/ozel/SANAL-EKRAN.md` | SANAL EKRAN — ne mümkün, ne değil | <!-- ELLE YAZILDI: tasarım kararı. Ölçüm bloğu OTO ile tazelenir. --> |
| `docs/ozel/SIRADAKI-SURUM.md` | SIRADAKİ SÜRÜM — `37098` / `1.15-ozel.17` · iş listesi | seferde yapılacak, iki ayrı bekleyen sürüm tutmak yanıltıcı. |
| `docs/ozel/SOHBET-SAGLIK-GOSTERGESI.md` | Sohbet sağlık göstergesi — plan (37095) | Sohbet ekranında yanıt beklerken kullanıcının elinde tek bir işaret var: üç nokta. |
| `docs/ozel/SURUM-GECMISI-1.15-ozel.18-31.md` | SÜRÜM GEÇMİŞİ — 1.15-ozel.18 → .31 | tek bir derlemede doğrulanacak; bu yüzden `compile` adımı hâlâ ilk kez |
| `docs/ozel/SURUM-NOTU-1.15-ozel.15.md` | SURUM-NOTU-1.15-ozel.15.md | """Sürüm notu üreticisi — 1.15-ozel.15 için. |
| `docs/ozel/SURUM-NOTU-1.15-ozel.16.md` | SURUM-NOTU-1.15-ozel.16.md | """Sürüm notu üreticisi — 1.15-ozel.16 için. |
| `docs/ozel/SURUM-NOTU-1.15-ozel.17.md` | SURUM-NOTU-1.15-ozel.17.md | """Sürüm notu üreticisi — 1.15-ozel.17 için. |
| `docs/ozel/SURUM-NOTU-1.15-ozel.32.md` | SÜRÜM NOTU — 1.15-ozel.32 / 37113 | değil**: üye üye `absentWhy` listeleniyor ve "bu bir sonuç değil, arıza |
| `docs/ozel/SURUM-NOTU-1.15-ozel.34.md` | SÜRÜM NOTU — 1.15-ozel.34 / 37115 | `.22`–`.33` arası **on iki sürümlük birikim** tek derlemede doğrulandı: |
| `docs/ozel/SURUM-NOTU-1.15-ozel.35.md` | SÜRÜM NOTU — 1.15-ozel.35 / 37116 | Kullanıcının iki cümlesi bu sürümün tamamı: |
| `docs/ozel/SURUM-NOTU-1.15-ozel.36.md` | SÜRÜM NOTU — 1.15-ozel.36 / 37117 | Bu turun tek işi var: **"kapandı" iddiasını denetlenebilir yapmak.** Yeni |
| `docs/ozel/SURUM-NOTU-1.15-ozel.37.md` | SÜRÜM NOTU — 1.15-ozel.37 / 37118 | Kullanıcı: *"sen onay istesen ben onay versem de vermesem de onay vermişim gibi |
| `docs/ozel/SURUM-NOTU-1.15-ozel.38.md` | SÜRÜM NOTU — 1.15-ozel.38 / 37119 | Bu tur planın **en riskli** turuydu ve sınırını baştan söylüyorum: tek tuval |
| `docs/ozel/SURUM-NOTU-1.15-ozel.39.md` | SÜRÜM NOTU — 1.15-ozel.39 / 37120 | Plan `.39`'u Terminal/Termux'a ayırmıştı. Ölçüm iki şeyi değiştirdi: |
| `docs/ozel/SURUM-NOTU-1.15-ozel.40.md` | SÜRÜM NOTU — 1.15-ozel.40 / 37121 | Kullanıcı "kararı bende olanları önerilerin neyse onları uygula" dedi. Bu turda |
| `docs/ozel/SURUM-NOTU-1.15-ozel.41.md` | SÜRÜM NOTU — 1.15-ozel.41 / 37122 | Önce: satır başına bir metin bloğu (`Ayşe ✓  Mehmet ✗`). 5 üye × 8 iddia'da |
| `docs/ozel/SURUM-NOTU-1.15-ozel.42.md` | SÜRÜM NOTU — 1.15-ozel.42 / 37123 | `CouncilLoop` tur içinde `answered` sayısını **biliyordu** (`StanceMath.evaluate` |
| `docs/ozel/SURUM-NOTU-1.15-ozel.43.md` | SÜRÜM NOTU — 1.15-ozel.43 / 37124 | Yeni araç `scripts/ozel/ceviri-erit.py` — batch başına 40 anahtar. |
| `docs/ozel/SURUM-NOTU-1.15-ozel.44.md` | SÜRÜM NOTU — 1.15-ozel.44 | `.41`'de matris yamasını yazarken `DecisionTab` fonksiyonunu yanlışlıkla |
| `docs/ozel/SURUM-NOTU-1.15-ozel.45.md` | SÜRÜM NOTU — 1.15-ozel.45 / 37125 | Bu sürüm `.44`'ten devraldığı üç açık maddeyi kapatıyor ve **ilk kez** |
| `docs/ozel/TERMUX-APK-KARARI.md` | KARAR — Termux: katman mı, ayrı APK mı? | Soru (kullanıcı, 2026-11-22): *"Termux araç zincirleri bölümünde olmalı, tek |
| `docs/ozel/TERMUX-ENTEGRASYONU.md` | TERMUX ENTEGRASYONU — tasarım, güvenlik, ön/arka yüz, etkiler | Bu belge bir niyet beyanı değil. Her bölüm ya **ölçülmüş** bir gerçeğe, ya |
| `docs/ozel/YAPILACAKLAR.md` | YAPILACAKLAR — geniş kapsamlı, kategori kategori | hâlâ üye başına tek satır**. Sağlayıcı hatası, JSON bozukluğu, kota, model yok |
| `docs/ozel/arsiv/ANA-BAGLAM.md` | Minis Özel — ANA BAĞLAM | Bu dosya cihazda yaşar. Her oturumun başında okunur. Yeni özellik eklerken buradaki |
| `docs/ozel/arsiv/DENETIM-HATTI.md` | DENETİM HATTI — nasıl çalışır, neden böyle | Aynı hata ailesi dört kez CI'da patladı, her biri 4–5 dakikalık tur: |
| `docs/ozel/arsiv/DURUM-11.1.md` | 11.1 DURUM — ne hazır, ne değil | Son commit: `c151536` · Sürüm `1.15-ozel.11.1` (37028) · **CI yeşil** |
| `docs/ozel/arsiv/GERI-DONUS.md` | GERİ DÖNÜŞ (ROLLBACK) — 2-3 sürüm geri, çakışmasız | `PackageInstaller`, kurulu `versionCode`'dan **küçük** bir APK'yı reddeder: |
| `docs/ozel/arsiv/ILK-MESAJ-SABLONU.md` | İLK MESAJ — boş şablon (kopyala, doldur, `docs/ozel/durum/<konu>.md` olarak kayd | KONU: <tek satır, ölçülebilir hedef> |
| `docs/ozel/arsiv/ILK-MESAJ-SOZLESMESI.md` | İLK MESAJ SÖZLEŞMESİ — v1 | Üçü de **ölçülebilir** alanlara dönüşür: yol, hash, zaman, sahip, deneme sayısı. |
| `docs/ozel/arsiv/ISTASYON-MIMARISI.md` | İSTASYON MİMARİSİ — tek ortam, ayrı alanlar | Kullanıcının cümlesiyle: *"ortam her ne kadar bir olsa da alanları birbirinden alakasız olmalı"*. |
| `docs/ozel/arsiv/KONSEY-MIMARISI.md` | KONSEY — çoklu model tartışma motoru | Bu belge **11.6** sürüm hattının tamamını tanımlar. Yürürlükteki `11.5` haritası |
| `docs/ozel/arsiv/ONERILER.md` | ÖNERİLER — planlananların DIŞINDA (2026-11-18) | Kapsam dışı bırakılanlar (başka sohbetlerde yürüyor, buraya karışılmadı): |
| `docs/ozel/arsiv/OZELLIK-DEFTERI.md` | ÖZELLİK DEFTERİ — Minis Özel 11.1 | Durum kodları: `planlı` · `kodlanıyor` · `derlendi` · `CI yeşil` · `yayında` |
| `docs/ozel/arsiv/PROJE-HAFIZA.md` | Proje Bazlı Hafıza ve Alan İzolasyonu — tasarım | Durum: **plan** (kod yok). Önceki belge `ekip-ve-projeler.md` §6'yı (Projeler) |
| `docs/ozel/arsiv/README.md` | Arşiv — sohbet klasöründen devralınan belgeler | Bu belgeler `/var/minis/shared/openminis-ozel/` içinde, yani **uygulamanın |
| `docs/ozel/arsiv/REPO-DENETIMI.md` | REPO DENETİMİ — Konsey kodlamasından önce | Bu belge **planı değiştiren** bulguları içerir. Okumadan kod yazılmaz. |
| `docs/ozel/arsiv/ROTA-HARITASI.md` | ROTA HARİTASI — Minis Özel | Kural: yeni özellik için yeni `composable()` uydurma. Hub'a satır ekle. |
| `docs/ozel/arsiv/SAGLAYICI-DUVARLARI.md` | SAĞLAYICI DUVARLARI — her örnek kendi hesabı | Bu doğrudur ve kasıtlıdır: |
| `docs/ozel/arsiv/TOPLAMA-DEFTERI.md` | TOPLAMA DEFTERİ — hangi oturum ne üretti | Son güncelleme: 2026-11-18 · kaynak ağaç `/root/om` · **hiçbiri derlenmedi / push edilmedi** |
| `docs/ozel/ekip-ve-projeler.md` | Ekip + Projeler — tasarım belgesi | Durum: tasarım (kod yok). Hedef sürüm hattı: 37031+. |
| `docs/ozel/sozlesmeler/A-Ayar.md` | A · Ayar sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/B-Bagimlilik.md` | B · Bağımlılık sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/BAGLANTI.md` | Sözleşmeler arası bağlar | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/C-Claim.md` | C · Claim sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/D-Derleme.md` | D · Derleme sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/DIZIN.md` | Sözleşme ailesi — dizin | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/E-Emeklilik.md` | E · Emeklilik sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/F-Geri-bildirim.md` | F · Geri bildirim sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/G-Gorev.md` | G · Görev sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/H-Hatirlama.md` | H · Hatırlama sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/IZN-Izin.md` | IZN · Izin sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/K-Karar.md` | K · Karar sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/L-Terminal.md` | L · Terminal sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/M-Mesaj.md` | M · Mesaj sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/N-Nakil.md` | N · Nakil sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/O-Ortam.md` | O · Ortam sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/OLC-Olcum.md` | OLC · Olcum sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/P-Programlama.md` | P · Programlama sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/R-Risk.md` | R · Risk sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/S-Surum.md` | S · Sürüm sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/SH-Kabuk.md` | SH · Kabuk sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/T-Test.md` | T · Test sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/UY-Arayuz.md` | UY · Arayuz sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/V-Veri.md` | V · Veri sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/Y-Yapilacaklar.md` | Y · Yapılacaklar sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/Z-Zaman.md` | Z · Zaman sözleşmesi | <!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin --> |
| `docs/ozel/sozlesmeler/rapor/F-sozlesme-37091.md` | F-sozlesme-37091 |  |
| `docs/ozel/sozlesmeler/rapor/G-h96.md` | G-h96 |  |
| `docs/specs/debug-server-api.md` | Debug Server API | MinisApp includes a built-in JSON-RPC 2.0 debug server (DEBUG builds only) for runtime view inspection. |
| `docs/specs/ios-sandbox-ish-summary.md` | iOS Sandbox Environment: iSH Virtualization Summary | MinisApp uses a customized fork of [iSH](https://github.com/OpenMinis/ish-arm64) (OpenMinis/ish-arm64) to prov |
| `docs/specs/minis-url-scheme.md` | Minis URL Scheme Specification | `minis://` is a **session-scoped** unified resource locator for persistent, addressable resources within the M |
