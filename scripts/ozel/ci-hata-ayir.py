#!/usr/bin/env python3
"""[H-013] Ağ hatası ile kod hatasını ayırır.

## Neden var

`.54` derlemesinde `:app:kspGithubDebugUnitTestKotlin FAILED` kırmızı çıktı.
Log'a bakınca sebep kod değildi:

    Could not resolve junit:junit:4.13.2
      > Could not GET '...junit-4.13.2.pom'.
        Received status code 429 from server: Too Many Requests

Maven Central hız sınırı. Yani CI "kodun bozuk" dedi, gerçekte "internet
yorgundu". İki hafta içinde iki kez olursa kırmızı ciddiye alınmaz.

## Ne yapar

Bir log dosyasını (veya stdin'i) tarar. Yalnız **geçici ağ imzaları** varsa
çıkış kodu 2 döner ("yeniden dene"), kod hataları varsa 1 ("gerçekten
bozuk"), temizse 0.

Kullanım:
    gh run view <id> --log-failed > /tmp/log.txt
    python3 scripts/ozel/ci-retry.sh /tmp/log.txt   # 2 → yeniden koş

`ci-retry.sh` bu betiği çağıran ince bir sarmalayıcıdır.
"""
import re
import sys

AG_IMZALARI = [
    r"status code 429",
    r"Too Many Requests",
    r"status code 5\d\d",
    r"Connection (timed out|reset|refused)",
    r"Could not GET 'https?://",
    r"Could not resolve all (files|dependencies)",
    r"Read timed out",
    r"Temporary failure in name resolution",
    r"SSLHandshakeException",
    r"Remote host terminated the handshake",
]

KOD_IMZALARI = [
    r"^e: file://",            # Kotlin derleyici
    r"error: unresolved reference",
    r"Compilation error\. See the compiler error output",
    r"Cannot access '",
    r"Unresolved reference:? ",
    r"Type mismatch",
]

yol = sys.argv[1] if len(sys.argv) > 1 else None
metin = open(yol, encoding="utf-8", errors="ignore").read() if yol else sys.stdin.read()

ag = [p for p in AG_IMZALARI if re.search(p, metin, re.M)]
kod = [p for p in KOD_IMZALARI if re.search(p, metin, re.M)]
# Kotlin derleyici satırları varsa kod hatası baskındır: bağımlılık çözülmüş
# ve gerçekten derleme denenmiş demektir.
gercek_kod = kod

if gercek_kod:
    print(f"KOD HATASI ({len(gercek_kod)} imza) — yeniden denemek zaman kaybı.")
    for p in gercek_kod:
        print(f"  · {p}")
    sys.exit(1)

if ag:
    print(f"GEÇİCİ AĞ HATASI ({len(ag)} imza) — yeniden koşulabilir.")
    for p in ag:
        print(f"  · {p}")
    sys.exit(2)

print("Belirgin hata imzası yok.")
sys.exit(0)
