# SAĞLAYICI DUVARLARI — her örnek kendi hesabı

> Soru: "Evren'den 3 tane ekledim, 3 ayrı anahtar = 3 ayrı hesap. Model isimleri aynı
> olabilir ama iletişim yolları farklı. Bunlar kalıcı olarak birbirine karışmasın."
>
> Cevap: mimari bunu zaten böyle kuruyor — aşağıda her katmanın kanıtı ve kalan gerçek risk.

## 1. Katman katman kanıt (kod okunarak doğrulandı)

| Katman | Anahtar | Ayrı mı | Kanıt |
|---|---|---|---|
| API anahtarı | `apikey_<instanceId>` | evet | `ProviderRepository.loadApiKey` |
| Model önbelleği | `baseURL + "\|" + apiKey` | evet | `OpenAIModelsApi.kt:184`, `AnthropicModelsApi.kt:45` |
| Model listesi | `providerInstanceId` + `uuid` | evet | `ModelEntry` |
| Kota ölçümü | subject = **kimlik bilgisi** | evet | `instanceSubjects[instanceId]` → `QuotaSubject` |
| Ağ rotası | `prov:<instanceId>` | evet | `NetworkRuntime.providerTag` |
| Düşünme kuralı | örnek başına | evet | `ProviderThinkingRuleEntity` |
| User-Agent | `customUserAgent` | evet | `ProviderInstance` |
| Bağlantı durumu | örnek başına (11.1'de eklendi) | evet | `ProviderConnectionTest` |
| Ücretsiz model kimliği | `free_<instanceId>` | evet | `FreeModelCatalog` |
| Günlük sayaç | `<instanceId>\|<gün>` | evet | `FreeTierLimits.UsageCounter` |

**Önemli ayrım:** kota ölçümü anahtara (kimlik bilgisine) bağlıdır, örneğe değil.
Bu doğrudur ve kasıtlıdır:

- Evren'e **3 farklı anahtar** → **3 ayrı subject** → 3 bağımsız bütçe. İstenen bu.
- Aynı anahtarı 2 örneğe yapıştırırsan → **1 subject** → tek bütçe. Bu da doğru:
  sağlayıcı açısından tek hesap. İki ayrı ilerleme çubuğu göstermek yalan olurdu.

## 2. Aynı model adı, farklı limit — nasıl ayrı kalıyor

Model adı hiçbir yerde kimlik değildir. Kimlik `(instanceId, modelId)` çiftidir:

```
ModelEntry(providerInstanceId = "evren-a", baseModel.id = "qwen3.8-flash-next")   <- A hesabı
ModelEntry(providerInstanceId = "evren-b", baseModel.id = "qwen3.8-flash-next")   <- B hesabı
```

İki satır, iki `uuid`, iki önbellek anahtarı, iki sayaç. Model seçicide aynı adı iki kez
görürsün ve hangisinin hangi hesap olduğu **örnek etiketiyle** ayrılır ("Evren — iş" /
"Evren — kişisel" gibi yeniden adlandırılabilir).

## 3. Ücretsiz / limitli / ücretli — üç ayrı şey

Bu ayrımı tek "free" rozetine indirmek, kullanıcıya bütçe yaktıran hatanın ta kendisi.

| Grup | Ne demek | Nasıl tespit edilir |
|---|---|---|
| **Ücretsiz** | Fiyatı gerçekten 0 | Sağlayıcının kendi `pricing` bloğu (Vercel) ya da models.dev `cost` alanı; veya `:free` ad eki (OpenRouter) |
| **Limitli** | Ücretli ama günlük/saatlik kota dahil | Satıcı profilinde `rpd`/`tpd`/`credit` boyutu **ve** kullanıcı limitini girmiş olması |
| **Ücretli** | Sadece bakiye | Geri kalanı |

Yanlış rozet üreten iki durum özellikle engellendi:
- önbellek okuma fiyatı 0 ise model **ücretsiz sayılmaz** (giriş/çıkış ücretli olabilir),
- fiyat bilinmiyorsa **ücretsiz sayılmaz** (bilinmeyen != bedava).

## 4. Limit sayıları neden gömülü değil

Groq / Gemini / OpenRouter limit tablolarını canlı okudum (2026-11-16): üçü de tabloyu
**hesaba göre** üretiyor. APK'ya gömülen bir sayı haftalar içinde yanlışa döner ve yanlış
limit, limit olmamasından kötüdür — kullanıcı ona göre plan yapar, sonra istek patlar.

Bu yüzden gömülen dosya yalnız **değişmeyen yapıyı** taşır:
`dimensions`, `resetTz`, `perModel`, varsa `counter` uç noktası, `freeModelSuffix`.

Sayı üç yoldan gelir, tercih sırasıyla:
1. **Sağlayıcı sayacı** — OpenRouter `GET /api/v1/key` → `free_model_daily_requests`
   (doğrulandı: alan gerçekten var). Yerel saymaktan üstün, çünkü aynı anahtarı kullanan
   diğer cihazları da içerir.
2. **Kullanıcının girdiği limit** — kendi panosundan bir kez girer; sayaç bizde.
3. **Bilinmiyor** — ekran "limit bilinmiyor" der, sayı uydurmaz.

## 5. CLI / npx meselesi

Bazı sağlayıcıların kendi CLI'ı var (Vercel `npx`, Google `gcloud`, HF `huggingface-cli`).
Bu uygulama **sağlayıcı CLI'ı kurmaz ve çağırmaz**: konuşma yolu düz HTTPS'tir
(`/v1/chat/completions` vb.). Sandbox'a inen paketler (`apk add`, `pip install`, `npx`)
bir sağlayıcıya değil **sandbox'a** aittir ve rootfs içinde yaşar — sağlayıcı örneği
silinse de kalır, iki örnek arasında paylaşılır.

Sonuç: "Vercel npx bileşeni indiriyor" gibi bir davranış yalnızca **ajan shell komutu
çalıştırdığında** olur, sağlayıcı eklenirken değil. İstasyon panellerinde bu yüzden her
panelin kendi çalışma dizini var (`/var/minis/spaces/<spaceId>/work`) ama kurulu paketler
ortak — 4 panelin aynı 200 MB'lık Node'u 4 kez indirmesi saçma olurdu.

## 6. Kalan gerçek riskler (dürüst liste)

1. **Aynı örnek iki panelde** → aynı anahtar, aynı hız sınırı, iki kat yük. Engellenmez,
   panelde uyarı gösterilir.
2. **Örnek silinince** `free_<id>` ve `rpd_<id>` anahtarları kalır — zararsız (birkaç bayt),
   ama aynı uuid ile yeni örnek gelirse eski sayacı devralır. `deleteInstance` yolunda
   temizlenmeli; 11.1'de eklenecek.
3. **Önbellek anahtarı anahtar metnini içerir** ve dosya adı onun SHA-256'sı. Anahtar
   cihazdan çıkmaz ama dosya adı anahtara bağlıdır; anahtar değişince eski önbellek yetim
   kalır. Zararsız, temizlik işi.
