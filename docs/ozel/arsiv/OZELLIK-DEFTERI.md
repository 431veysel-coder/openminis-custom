# ÖZELLİK DEFTERİ — Minis Özel 11.1

Durum kodları: `planlı` · `kodlanıyor` · `derlendi` · `CI yeşil` · `yayında`

| # | Özellik | Anahtar | Durum | Dosyalar |
|---|---|---|---|---|
| 0 | 11.1 tabanı (dal + sürüm) | — | planlı | `build.gradle.kts`, `ozel-11` dalı |
| 1 | Çeviri paketleri: gömülü + cihaz dili | `translation` | planlı | `TranslationPacks.kt`, `assets/packs/`, `TranslationPacksScreen.kt` |
| 2a | Sağlayıcı bağlantı testi | `provider_test` | planlı | `ProviderDetailScreen.kt` |
| 2b | Evren ön ayarı kaldırma | — | planlı | `ChatProviderPreset.kt`, `AddProviderScreen.kt`, `OzelFeatures.kt`, strings |
| 3 | Minis Özel hub + anahtar kayıt defteri | — | planlı | `OzelFeaturesScreen.kt`, `OzelFeatures.kt`, `SettingsScreen.kt` |
| 4 | Notlar + güvenlik kasası | `notes`, `vault` | planlı | `NoteRepository.kt` (yeniden), `NoteTool.kt`, `NotesScreen.kt`, `EncryptedPrefsFactory` |
| 5a | İstasyon A: 2 panel, araçsız | `station` | planlı | yeni `ui/station/*` |
| 5b | İstasyon B: panel başına terminal + araç | `station` | planlı | `sandbox/`, `BrowserTabPoolRegistry` |
| 5c | İstasyon C: 3–4 panel, layout | `station` | planlı | `ui/station/*` |

## Sevk edilmiş (10.1 ve öncesi)

özellik anahtarları · canlı görevler · araç zincirleri · düşünme çevirisi · arka plan
kapıları · QS karosu · geliştirici sunucusu (varsayılan kapalı) · güncelleme denetleyici
+ imza koruması · 4 sağlayıcı ön ayarı (NVIDIA, DeepSeek, Together, Fireworks, HuggingFace)

## Kaldırılanlar

- GeckoView (geri alındı — APK şişmesi)
- Evren API ön ayarı (11.1'de kaldırılıyor; kullanıcı örneği varsa çalışmaya devam eder)
