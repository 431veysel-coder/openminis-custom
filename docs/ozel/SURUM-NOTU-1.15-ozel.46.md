# SÜRÜM NOTU — 1.15-ozel.46 / 37126

> **Önceki yayın:** `v1.15-ozel.45` / 37125 · **Kapılar:** 11/11 · 0 uyarı

Bu sürüm üç katmanı derinleştiriyor: **konsey · termux · projeler**.
Üçünde de aynı hastalık vardı — **yazılmış ama bağlanmamış** parçalar.
Ölçümle bulundu, çağrı ile kapatıldı, testle çivilendi.

## 1 · Konsey — duruş matrisi artık kalıcı

**Bulgu.** `Claim.stances` yalnız bellekte (`CouncilLiveRegistry`) yaşıyordu.
`TeamRunStore` özeti yazıyordu (kaç tur, kaç token, hangi iddia kaç oy) ama
**kim hangi iddiaya ne dedi** yazılmıyordu. Uygulama kapandığında `live` null
oluyor, karşılaştırma odası "No comparison yet" diyordu — yani **koşu bitti
diye tablo yok oluyordu**. Kullanıcı için en değerli an tam olarak sonrası.

- Yeni `agent/team/ClaimMatrixCodec.kt` — matrisin disk biçimi, saf ve testli.
- `TeamRunStore.record` artık `matrix` alanını yazıyor; `Header.matrix` okuyor.
- `ComparisonRoom(live, recorded)` — canlı yoksa diskten çiziyor ve bunu
  **söylüyor**: "Koşu sonunda kaydedildi — bu tablo canlı güncellenmez."
  Kaynağı söylemek zorunlu; canlı tablo güncellenirken donmuş kaydı aynı
  sanmak yanlış karar verdirir.
- **Bilinmeyen duruş atılır**, `UNCERTAIN` yapılmaz: modelin serbest metnini
  okurken varsayılan doğru, ama **diskte saklanmış** bozuk bir hücreyi
  "kararsız oy verdi" diye göstermek olmayan bir oy uydurmaktır.
- `ClaimMatrixCodecTest` — **5** test (gidiş-dönüş, boş matris, eski kayıt =
  `null`, bozuk duruş, kimliksiz iddia).

## 2 · Termux — zaman aşımı politikası nihayet bağlı

**Bulgu.** `ShellTimeoutPolicy` `.0`'dan beri yazılıydı: derleme 20 dk, ağ 3 dk,
kurulum 10 dk. **Hiçbir çağıran onu sormuyordu** (ölçüldü: 0 çağrı). Yani
`gradle assembleDebug` de `ls` de aynı 10 dakikayı alıyordu.

- `ShellExecutor.execute` varsayılanı artık `ShellTimeoutPolicy.forCommand(command)`.
  Kotlin'de varsayılan değer önceki parametreyi okuyabildiği için düzeltme
  **çağrı yerlerine dokunmadan** geçerli; açıkça süre veren çağıran etkilenmez.
- **Yeni ayrım: tarama komutları.** `grep`/`rg`/`find`/`sort`/`du` "hızlı"
  listesindeydi. Maliyetleri komuta değil **girdiğin ağaca** bağlı; `/` üzerinde
  bir `rg` dakikalar sürebilir. Bunlara 60 sn vermek, **çalışan bir taramayı
  hata gibi göstermek** olurdu — kullanıcı komutu bozuk sanar. Artık varsayılan
  bütçeyi alırlar.
- Sınıf artık günlüğe yazılıyor: `[timeout] build -> 1200s · gradle ...`.
  Etiket olmadan "neden bu komut 20 dakika sürdü" sorusunun cevabı logda yoktu.
- `ShellTimeoutPolicyTest` — **6** test.

## 3 · Projeler — ekran artık salt-okunur değil

**Bulgu.** Depo tam CRUD sunuyordu: `createFolder`, `renameFolder`,
`toggleFolderPin`, `dissolveFolder`. **Projeler ekranı hiçbirini çağırmıyordu**
(ölçüm: 4 fonksiyonun 0 çağıranı). Kullanıcı proje açmak için Sohbetler
ekranına gidip grup kurmak zorundaydı — ayrı ekranda, ayrı adla. Proje
detayında da **içeri sohbet taşımanın yolu yoktu**: boş proje açılıyor,
eklenecek hiçbir şey sunulmuyordu.

- `ProjectsScreen`: "Yeni proje" satırı + ad/açıklama diyaloğu; satıra **uzun
  basma** → yeniden adlandır / sabitle / sil. Sabitlenen proje `PushPin`
  simgesiyle ayrılır.
- `ProjectDetailScreen`: "Sohbet ekle" → dosyasız sohbetler listesi → seçilen
  tek çağrıyla taşınır (`setFolderForSessions`), liste tazelenir.
- Yeni `ChatDao.unfiledSessionBriefs(limit)` — `folder_id IS NULL OR ''`
  (eski kayıtlarda ikisi karışık; birini dışlamak sessiz veri kaybı olurdu).
  Alt oturumlar hariç: onlar sayfa, sohbet değil.
- Yeni `ui/projects/ProjectRules.kt` — ad doğrulama + **çözme cümlesinin
  şekli**. Silme tek veri kaybettiren işlem: `dissolveFolder` klasörü siler,
  sohbetleri **silmez**, grupsuz bırakır. Cümle bunu söylemek zorunda, yoksa
  kullanıcı iki yönde de yanlış karar verir.
  Çekirdek **dil bilmiyor**: hangi kalıbın kullanılacağını söylüyor
  (`DissolveShape`), cümleyi dize kaynağı kuruyor. (Bu depoda bir kez tersini
  yapmıştım: saf çekirdek Türkçe cümle kurdu, İngilizce arayüzde Türkçe
  göründü.)
- Kaydet düğmesi ad geçersizken **kapalı**: `renameFolder` boş adı sessizce
  yok sayıyor, yani kullanıcı basıp hiçbir şey olmamasını yaşardı. Sessiz
  hiçbir-şey, bozuk düğmeden ayırt edilemez.
- `ProjectRulesTest` — **4** test.

## Kapı onarımı (kendi aracımda yanlış pozitif)

`kotlin-hizli.py` `@Insert(onConflict = …)` / `@Query(…)` gibi **anotasyon**
kullanımlarını veri sınıfı kurulumu sanıyordu; ChatDao'ya bir `@Query` eklerken
"bu alan yok" dedi — uyardığı satırla hiç ilgisi olmayan, var olan bir anotasyon
için. Anotasyon argümanlarını KSP ve derleyici doğrular; kapı burada konuşursa
sahibi olmadığı bir borcu ödetir. Düzeltildi ve **bilerek hatayla sınandı**:
sahte `ZzProbe(omega = 1)` hâlâ KESİN hata olarak yakalanıyor.

## Ölçülen durum

```
kapi               : 11/11 · 0 uyarı · 48.9s
integration_audit  : 11/11 dilim üretim kodundan çağrılıyor
gorunurluk         : 25 anahtar · 21 açık+görünür · 0 açık+görünmez · 4 kapalı
ceviri             : taban 441 · 0 YENİ → YEŞİL
yeni test          : 15 (5 kodek + 6 zaman aşımı + 4 proje kuralı)
```

## Dürüst kalan

1. **Canlı içerik yok**: `screen capture` gerçek PNG üretiyor ama içinde
   sayfa/terminal değil bölme durumu var; kare `state frame` diye etiketli.
2. **Cihazda sınanmadı**: ses, `ALLOW_ALL`, DNS, TalkBack.
3. **Konsey iptali**: `CouncilLoop` iptali yukarı bırakıyor (doğru tasarım),
   ama ekranda "durdur" düğmesi yok — tur sırasında vazgeçmenin yolu yok.
4. **Çeviri 441**: batch başına 40.
