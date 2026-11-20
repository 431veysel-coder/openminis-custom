# ROTA HARİTASI — Minis Özel

Kural: yeni özellik için yeni `composable()` uydurma. Hub'a satır ekle.

## Giriş noktası (tek)

```
Ayarlar › Minis Özel            rota: ozel
├── Sohbet                      → mevcut sohbet ekranları (rota yok)
├── Sağlayıcılar                → providers / provider/{instanceId}
├── Çeviri                      → translation_settings · translation_packs
├── Notlar ve Kasa              → notes  (kapsam parametreli)
├── İstasyon (çoklu panel)      → station/{stationId}
└── Geliştirici                 → dev_server · logs · toolchains
```

Alt ekran gerekiyorsa: `ozel/{section}` (tek parametreli). Bugün 60+ sabit rota var;
sayı artmayacak.

## Mevcut rotalar (özet, `AppNavigation.kt`)

| Grup | Rotalar |
|---|---|
| Sohbet | `sessions`, `chat/{sessionId}`, `chat_files/{sessionId}`, `agent_transcript/{sessionId}` |
| Sağlayıcı | `providers`, `add_provider`, `provider/{instanceId}`, `model_groups`, `model_group/{groupId}`, `agent_loop_models`, `model_entry/{instanceId}/{entryId}` |
| Sandbox | `terminal`, `file_browser`, `file_preview`, `rootfs_management`, `toolchains` |
| Özel | `ozel`, `ozel_features`, `notes/{sessionId}`, `translation_settings`, `translation_packs`, `live_tasks` |
| Ağ | `network`, `network_tailscale`, `network_proxies`, `container_dns` |
| Veri | `storage`, `backup`, `memory`, `env_vars`, `logs` |
| Ajan | `agents`, `sub_agents`, `skills`, `mcp`, `permissions` |

## Özellik nereye eklenir

| İş | Dosya |
|---|---|
| Anahtar tanımı | `data/OzelFeatures.kt` → `Feature` enum |
| Hub satırı | `ui/settings/OzelFeaturesScreen.kt` |
| Yeni ekran | `ui/settings/<Ad>Screen.kt` + `AppNavigation.kt`'ye **tek** satır |
| Ajan aracı | `tools/<Ad>Tool.kt` + `ChatViewModel` araç kaydı |
| Ayar metni | `res/values/strings.xml` + `values-tr/strings.xml` |

## Çoklu panel (istasyon) — planlanan

- `station/{stationId}` → panel ızgarası
- Panel başına: child `ChatViewModel` (`PoolKind.CHILD`) + terminal oturumu
  + çalışma dizini `/var/minis/workspace/station-<id>/p<n>`
- Paneller arası `delegate_task` **kapalı** (izolasyon kasıtlı)
