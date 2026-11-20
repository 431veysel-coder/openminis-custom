#!/usr/bin/env python3
"""Sürüm notu üreticisi — 1.15-ozel.16 için.

`.15`'in notu "sağlık göstergesinin çekirdeği" diyordu ve dürüsttü: çekirdek
vardı, arayüz yoktu. Bu sürümün işi, o cümlenin borcunu kapatmak ve `.15`'te
ölçülen üç sessiz boşluğu doldurmak.

Kullanım: metin olarak bas (release gövdesine ver).
"""

METIN = """\
## Ne değişti

### Risk korpusu — kural tablosu artık test ediliyor, dize dize değil

`test/resources/ozel/risk-corpus.txt` tek kaynak: 48 komut, her biri
`SILENT / ASK / DENY` kararıyla ve **neden**iyle yazılı. `RiskCorpusTest`
sınıflandırıcıyı gerçekten çalıştırıp bu kararları sınıyor; `scripts/ozel/
risk-corpus.py` aynı dosyayı okuyup JVM olmadan aynı tabloyu basıyor. Yeni bir
kaçış yolu bulduğunda önce komutu dosyaya yazıyorsun, test kırmızı oluyor,
sonra kuralı ekliyorsun. Karar tek yerde görünüyor.

### Kapatılan üç kaçış yolu (ölçülmüş, tahmin değil)

**Yorumlayıcı.** `python3 -c "import shutil; shutil.rmtree('/var/minis/shared')"`
hiçbir kurala uymuyordu — `RM` kalıbı `rm\\s` arıyor, `rmtree` eşleşmiyordu.
Artık satır içi kod çalıştıran her çağrı kart açıyor (`shell:interpreter`), ve
içinde silme/üzerine-yazma API'si geçiyorsa **HIGH + geri dönüşsüz**
(`shell:interpreter-destroy`) — yani yalnız bu sohbette hatırlanabilir.

**Sessiz veri kaybı.** `mv`, `truncate`, `> dosya` ve yıkıcı SQL (`drop table`,
`delete from`) için kural yoktu. Veri silinmeden de gidebilir; dördü de artık
hedefe göre seviyeleniyor. `> /dev/null` ve `/tmp` yazımı sessiz kalıyor, yoksa
her komut kart açardı.

**Tırnak içi bölünme.** Kesici `;` ve `|` üzerinde körlemesine bölüyordu,
tırnak içinde olsa bile. Sonuç: `sh -c "a; rm -rf /x"` iki parçaya ayrılıyor ve
ikinci parça hiçbir kuralın ayırıcı önekiyle eşleşmiyordu — **silme, metinde
görünürken görünmez oluyordu**. Kesme artık tırnak farkında.

### Kendi kendini silme yasağı düzeltildi

`pm uninstall com.minis.x` **geçiyordu**: yasak listesi `.14`'te değişen paket
adını ve `.14` öncesi eski adı tanımıyordu. Ayrıca `pm clear` (veriyi silen,
uygulamayı bırakan yol) hiç kapsanmıyordu. Üç ad birden kapsanıyor artık, ve
`pm disable-user` eklendi — kendini kapatan ajanı geri açacak kimse kalmaz.

### Onay kutusu test edilebilir oldu

Bir turu beş dakika bekletebilen mantık `PolicyGate`'in içinde, `Context` ve
`OzelFeatures` ile sarılı duruyordu; hiçbir test dokunamıyordu. `ApprovalInbox`
Android'siz: zaman aşımı, geç gelen cevap, çift cevap, "hepsini reddet", aynı
kimlikli kartın birleşmesi — 7 test. Bir davranış değişti: cevap artık
`Boolean` dönüyor, yani zaman aşımından sonra gelen dokunuş **yutulmuyor**,
görülebiliyor.

### Sohbet sağlık göstergesi bağlandı

`.15`'te çekirdek vardı, üretim kodunda tek referansı kendi tanımıydı, anahtar
ise **varsayılan açıktı** — yani kullanıcı açık bir anahtar görüp hiçbir şey
görmüyordu. Tam olarak `.14`'te kınanan hatanın aynısı. `ChatHealthBridge` üç
gerçek anı bağlıyor (istek çıktı · ilk parça geldi · tur kapandı) ve Canlı
Görevler kartında tek satır olarak görünüyor: `820ms`, `3.2s`, `1/3`, `429`.
Hata durumu bitişte ezilmiyor — kullanıcı 429 yediğini görebilmeli.

### Çeviri: kısmi sonuç da önbelleğe giriyor

7 parçanın 5'i çevrilip 6.'da ağ koptuysa kullanıcı kısmi metni görüyor ama
pencereyi kapatıp açtığında **baştan ödüyordu**. Kısmi sonuç ayrı anahtara
yazılıyor; tam çeviri gelince kısmi kayıt düşüyor. Tamamlanmış bir çeviri asla
yarım kayıttan okunmuyor.

### Ölü anahtar kaldırıldı

`STATION` ayar satırı silindi. `ui/station/` hâlâ yazılmadı ve anahtarın üretim
kodunda okuyucusu yok; kapatılmış ama **görünen** bir anahtar da bir vaattir.
Paneller gelince satır panellerle birlikte döner.

---

## Doğrulama

Bu cihazda JVM başlamıyor (PRoot: *"Failed to mark memory page as executable"*),
yani `./gradlew test` burada koşulamadı. Onun yerine koşan üç kapı:

| Kontrol | Komut | Sonuç |
|---|---|---|
| Risk korpusu | `python3 scripts/ozel/risk-corpus.py --fail-on-mismatch` | **48 komut · 0 sapma · 34 kural** |
| Yapısal denetim | `python3 scripts/ozel/kotlin-denetim.py` | **1642 dosya · yapısal hata yok** |
| Entegrasyon | `python3 scripts/integration_audit.py` | **10/11 dilim üretimden çağrılıyor** |
| Etiket | `python3 scripts/ozel/tag-denetim.py` | **5 etiket yanlış commit'te** (aşağıda) |

`RiskCorpusTest` ve `ApprovalInboxTest` CI'da koşacak. Kırmızıysa sürüm
çıkmamalı — `apk.yml`'deki `needs: tests` bunu zaten zorunlu kılıyor. Yeni
`ozel-guard.yml` işi korpusu, yapıyı ve etiketleri saniyeler içinde denetliyor.

## Bilinen sınırlar

- **Kara liste hâlâ kara liste.** Dil içinde dil (base64 ile kod, `eval`, betiğe
  yazıp çalıştırma) metinden çözülemez. İddia "her şeyi yakalar" değil,
  "bilinen her yol bir kart açar".
- **`v1.15-ozel.11.3.1`, `.11.4`, `.13`, `.14`, `.15` etiketlerinin beşi de
  `11.2` commit'ini gösteriyor.** Release *nesnesi* doğru APK'yı taşıyor; yanlış
  olan etiketin gösterdiği commit. Kendi güncelleme kanalı bundan etkilenir.
  Düzeltme planı: `python3 scripts/ozel/tag-denetim.py --fix-plan`. Uygulanmadı —
  uzak etiket silme geri dönüşsüz, karar senin.
- Konsey/Compare koşuları `TeamRunStore`'a yazılmıyor (tip uyuşmazlığı; sahte
  boş konsensüs uydurmak yerine kayıt hiç yazılmıyor). Rapor transkriptte.
- `FORK.md` §5/§6 `.10` anlatıyor; kimlik `.14`'te, sürüm şeması `.13`'te
  değişti. Bu sürümde dokunulmadı — yarım düzeltme yanlıştan kötü.
"""

if __name__ == "__main__":
    print(METIN)
