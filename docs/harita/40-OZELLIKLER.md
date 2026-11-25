<!-- ÜRETİLDİ: scripts/ozel/harita.py — elle düzenlemeyin. -->

# Özellik anahtarları — 25

| Anahtar | Ayar adı | Varsayılan |
|---|---|---|
| `LIVE_TASKS` | `live_tasks` | açık |
| `TOOLCHAINS` | `toolchains` | açık |
| `TRANSLATION` | `translation` | açık |
| `QS_TILE` | `qs_tile` | açık |
| `BACKGROUND_GATES` | `background_gates` | açık |
| `DEV_SERVER` | `dev_server` | kapalı |
| `PROVIDER_TEST` | `provider_test` | açık |
| `NOTES` | `notes` | açık |
| `VAULT` | `vault` | açık |
| `VAULT_AGENT_READ` | `vault_agent_read` | kapalı |
| `JOURNAL` | `journal` | açık |
| `ACCEPTANCE_CRITERIA` | `acceptance_criteria` | açık |
| `COUNCIL` | `council` | açık |
| `APPROVAL_GATE` | `approval_gate` | açık |
| `BUDGET_CAP` | `budget_cap` | açık |
| `AUTO_BACKUP` | `auto_backup` | açık |
| `PLAN_PREVIEW` | `plan_preview` | açık |
| `TURN_DIFF` | `turn_diff` | açık |
| `TOOL_ROUTER` | `tool_router` | kapalı |
| `LESSON_BOOK` | `lesson_book` | açık |
| `EVIDENCE_GATE` | `evidence_gate` | açık |
| `WRITE_INTENT` | `write_intent` | açık |
| `REPLAY` | `replay` | açık |
| `CHAT_HEALTH` | `chat_health` | kapalı |
| `VIRTUAL_SCREEN` | `virtual_screen` | açık |

Kural: **açık** bir anahtarın üretim kodunda okuyucusu olmak zorunda.
`.14`'te `STATION` bu kuralı çiğnedi (açık, okuyucu yok) ve kapatıldı.
