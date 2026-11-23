<!-- ÜRETİLDİ: scripts/ozel/harita.py — elle düzenlemeyin. -->

# Kod ağacı — 879 dosya, 316846 satır

| Paket | Dosya | Satır |
|---|---|---|
| `accessibility/` | 4 | 897 |
| `agent/` | 51 | 13951 |
| `auth/` | 17 | 4718 |
| `backup/` | 27 | 8567 |
| `browser/` | 13 | 6164 |
| `budget/` | 1 | 183 |
| `config/` | 28 | 8707 |
| `crash/` | 5 | 1517 |
| `data/` | 95 | 24363 |
| `debug/` | 17 | 7460 |
| `deeplink/` | 2 | 327 |
| `diagnostics/` | 13 | 2715 |
| `i18n/` | 2 | 148 |
| `logging/` | 2 | 714 |
| `mcp/` | 7 | 1579 |
| `network/` | 15 | 3221 |
| `notification/` | 6 | 797 |
| `offload/` | 13 | 2461 |
| `ozel/` | 12 | 2009 |
| `plan/` | 1 | 106 |
| `policy/` | 8 | 1530 |
| `power/` | 1 | 193 |
| `provider/` | 46 | 16624 |
| `providers/` | 1 | 224 |
| `quota/` | 6 | 4397 |
| `remote/` | 1 | 97 |
| `replay/` | 1 | 129 |
| `sandbox/` | 61 | 24405 |
| `scheduled/` | 15 | 3557 |
| `screen/` | 3 | 501 |
| `service/` | 15 | 6752 |
| `share/` | 7 | 1593 |
| `shared/` | 4 | 376 |
| `speech/` | 42 | 11096 |
| `terminal/` | 2 | 126 |
| `tools/` | 15 | 3077 |
| `ui/` | 307 | 149178 |
| `usage/` | 6 | 805 |
| `util/` | 2 | 266 |
| `webapp/` | 5 | 1316 |

Ekran (`*Screen.kt`): **75** · UI paketi: **19**

## Nerede ne var (sabit sınırlar)

| Dizin | Sorumluluk | Kural |
|---|---|---|
| `ozel/` | çatala ait karar mantığı | Android'siz çekirdek `ozel/health/` içinde (dilim 37095) |
| `ozel/PolicyGate.kt` | onay kapısının Android tarafı | çekirdek `ApprovalInbox` ayrı, test edilebilir |
| `policy/` | risk tablosu (veri) + sınıflandırıcı (mantık) | tablo ile kod ayrı dosyada |
| `sandbox/` | PRoot, kök, kabuk, araç zinciri | en riskli alan; her dosya kendi başlığında "neden" taşır |
| `ui/` | Compose ekranları | iş mantığı yok, yalnız sunum |
| `data/` | Room, prefs, model | şema değişimi göç ister |
| `provider/` | LLM adaptörleri | sağlayıcıya özel tuhaflık burada izole |
