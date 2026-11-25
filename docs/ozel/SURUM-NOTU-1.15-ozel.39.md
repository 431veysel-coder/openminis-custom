# SÜRÜM NOTU — 1.15-ozel.39 / 37120

> **Yayındaki sürüm:** 1.15-ozel.34 / 37115. **Kapılar:** 11/11 · 48.7 s.
> **Derleme yapılmadı.**

## Neden bu tur DNS, neden Terminal değil

Plan `.39`'u Terminal/Termux'a ayırmıştı. Ölçüm iki şeyi değiştirdi:

1. **§2.1 zaten kapalı.** `.31`'de `TerminalSession.termuxNotice()` eklendi:
   Terminal açılırken "bu sekme kök kimliğiyle çalışır, apt reddeder, paket
   kurmak için `shell_execute` kullan" satırını yazıyor. Planın kendi kabul
   ölçütü iki seçenekten birini istiyordu ("apt çalışır **ya da** ekran baştan
   söyler") — ikincisi karşılanmış durumda. Aynı işi ikinci kez "kapatmak"
   sahte bir kapanış olurdu.
2. **§7.1 her oturumda tekrar ediyor.** Sandbox'ta `getaddrinfo` çözemediğinde
   push/indirme kırılıyor ve çözüm `/etc/hosts`'a elle IP yazmak — oturumluk.
   Sıradaki turların hepsi (`.42`–`.45`) git push gerektirdiği için bu, her
   turda ödenen bir vergiydi.

Kalan Terminal maddeleri (2.2 `apt-cache` çevrimdışı davranışı, 2.3 yarım
kurulum, 2.4 `apt update` süresi) `.42`'ye taşındı; hiçbiri diğer turlara engel
değil.

## Ne yapıldı: `sandbox/DnsBootstrap.kt`

Konuk kökünün **kendi** `etc/resolv.conf`'u. Android'in sistem DNS'ine
dokunulmuyor (yetki yok ve olmamalı: aynı değişiklik cihazdaki bütün
uygulamaları ve kullanıcının VPN'ini etkiler).

| Mod | Davranış |
|---|---|
| `AUTO` (varsayılan) | Dosyada gerçek bir `nameserver` yoksa yazar; varsa **dokunmaz** |
| `DIRECT` | Her zaman kendi bloğunu yazar (1.1.1.1 + 8.8.8.8) |
| `FAKEIP` | Bloğu **kaldırır** — 198.18/15 fake-IP resolver kullanan VPN'lerde sabit resolver bağlantıyı koparır |

Blok işaretli (`# >>> minis-dns` … `# <<< minis-dns`) ve **tektir**: kullanıcının
satırları korunur, ikinci çalıştırma bloğu çoğaltmaz.

**Bağlandığı iki yer** (üçüncü bir "kurulumda aç" yolu eklemek yerine):
- `TermuxInstaller` adım 7b'nin yanında — kurulumda bir kez,
- `ExecutionCoordinator.requiresFreshProcess` — **var olan kurulumlar** için
  komut yolunda onarım (`.34`'teki `ensureAptDirs` deseninin ikizi).

Dört PRoot kurucusunun her birine eklemedim: bu depoda "dört kurucudan birini
unutmak" hatası üç kez yaşandı. Tek çağrı yeri, unutulacak yer sayısını bire
indiriyor.

## Testte yakalanan gerçek hata (kayıt)

`DnsBootstrapTest.auto_modda_yazilan_blogu_ikinci_kez_yazmaz` yazılınca
görüldü: `AUTO` modu `nameserver` kontrolünü **blogumuz çıkarıldıktan sonraki**
metinde yapıyordu. Yani kendi yazdığımız bloğu görmüyor, her çalıştırmada
`changed=true` dönüp dosyayı yeniden yazıyordu. İçerik aynı olduğu için ekranda
hiçbir şey bozulmuyordu — tam olarak "sessiz israf" sınıfı.

Düzeltme: `existing.contains(BEGIN)` önce kontrol ediliyor. Ayrıca `changed`
artık içerik karşılaştırmasından geliyor, sabit `true` değil.

Not: bu hatayı CI görmedi, **test yazarken Python'da aynı algoritmayı
simüle ederek** gördüm. JVM bu cihazda çalışmadığı için simülasyon, testin
kendisinden önceki tek savunma hattı.

## Kapılar

```
apk-butce · ceviri · denetim · entegrasyon · etiket · gorunurluk
harita · korpus · kotlin · olu-kod · yapisal
11/11 geçti · 0 uyarı · 48.7s
```

## Kapanan / taşınan

| Madde | Durum |
|---|---|
| §7.1 DNS kalıcılığı | ✅ **kapandı** (konuk kökü sınırıyla; 11 test) |
| §2.1 Terminal'de apt | ✅ **zaten kapalıydı** (`.31` uyarı satırı) — yanlış "açık" kaydı düzeltildi |
| §2.2 / §2.3 / §2.4 | → `.42`'ye taşındı |
| §5.1 canlı içerik | açık (`.38`'de bilerek) |

## Cihazda sınanacak

Terminal'de `getent hosts github.com` — bir çıktı vermeli. Vermezse
`resolv.conf` içeriğine bakılır: blok var mı, mod ne (`AUTO` kullanıcı
resolver'ını korumuş olabilir).
