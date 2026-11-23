#!/usr/bin/env python3
"""Sandbox DNS doktoru — "çözülmüyor ama curl çalışıyor" durumunu teşhis eder.

## Neden var (ölçülen belirti)

Bu cihazda `getent hosts github.com` boş dönerken `curl https://github.com`
200 veriyordu. Sebep iki ayrı çözümleme yolu:

  * `curl` / `wget` → sistemin TUN/vekil yolu (gerçek IP'yi görür),
  * `getent`, `python getaddrinfo`, `git` → `/etc/resolv.conf`'taki
    nameserver (vekilin Fake-IP aralığı, ör. 198.18.0.0/15).

Fake-IP DNS yalnız TUN içinden erişilebilir; PRoot konuğunun soket yolundan
erişilemez. Sonuç: "internet var ama git çalışmıyor" — ve bunu her turda
yeniden teşhis etmek 10 dakika yiyor.

## Ne yapmaz

DNS ayarını **değiştirmez**. `auto` mod Fake-IP'yi bilerek kullanıyor
(`ContainerDnsFields` açıklaması); buradan yazmak kullanıcının vekilini
bozabilir. Araç yalnız durumu söyler ve üç yolu işaret eder.

Kullanım:
    python3 scripts/ozel/dns-doktor.py
    python3 scripts/ozel/dns-doktor.py --curl   # curl ile gerçek çözümlemeyi de dene
"""
from __future__ import annotations

import ipaddress
import shutil
import subprocess
import sys
from pathlib import Path

FAKE_IP = [ipaddress.ip_network("198.18.0.0/15")]


def read(path: str) -> str:
    try:
        return Path(path).read_text(errors="ignore")
    except OSError:
        return ""


def resolvers(text: str) -> list[str]:
    out: list[str] = []
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        parts = line.split()
        if len(parts) >= 2 and parts[0] == "nameserver":
            out.append(parts[1].split("%")[0])
    return out


def is_fake(server: str) -> bool:
    try:
        ip = ipaddress.ip_address(server)
    except ValueError:
        return False
    return any(ip in net for net in FAKE_IP)


def run(cmd: list[str], timeout: int = 8) -> tuple[int, str]:
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return p.returncode, (p.stdout + p.stderr).strip()
    except Exception as e:  # noqa: BLE001 - teşhis aracı, patlamamalı
        return 1, str(e)


def main() -> int:
    want_curl = "--curl" in sys.argv

    servers = resolvers(read("/etc/resolv.conf"))
    fake = [s for s in servers if is_fake(s)]

    print("── /etc/resolv.conf ──")
    print("  nameserver: " + (", ".join(servers) if servers else "(yok)"))

    rc_getent, out_getent = (1, "getent yok")
    if shutil.which("getent"):
        rc_getent, out_getent = run(["getent", "hosts", "github.com"])
    # /etc/hosts yaması çözümlemeyi "çalışıyor" gösterebilir; bu bir yalan
    # olurdu. Önce onu söyle.
    hosts_override = [
        line for line in read("/etc/hosts").splitlines()
        if "github.com" in line and not line.strip().startswith("#")
    ]
    if hosts_override:
        print("── /etc/hosts yaması ──")
        for line in hosts_override:
            print("  " + line.strip())
        print("  (bu satır çözümlemeyi taklit ediyor; IP değişince bayatlar)")

    print("── getent github.com ──")
    print("  " + (out_getent.splitlines()[0] if out_getent else "(boş)"))

    rc_curl, out_curl = (1, "atlandı")
    if want_curl and shutil.which("curl"):
        rc_curl, out_curl = run(
            ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code} %{remote_ip}", "--max-time", "12",
             "https://github.com"]
        )
        print("── curl github.com ──")
        print("  " + out_curl)

    print()
    if rc_getent == 0 and hosts_override:
        print("SONUÇ: çözümleme YALNIZ /etc/hosts yamasıyla çalışıyor.")
        print("  Gerçek çözümleyici hâlâ denenmedi: yamayı sil, tekrar koş.")
        return 0
    if rc_getent == 0:
        print("SONUÇ: çözümleme çalışıyor.")
        return 0
    if fake:
        print("SONUÇ: çözümleme bozuk — nameserver vekilin Fake-IP aralığında ("
              + ", ".join(fake) + ").")
        print("  Bu adres yalnız TUN içinden erişilebilir; PRoot konuğu göremez.")
        print("  Üç yol:")
        print("   1) Ayarlar › Ağ › Sandbox DNS = custom, gerçek bir çözümleyici yaz")
        print("      (ör. 1.1.1.1). Vekil Fake-IP kullanıyorsa bu, adları gerçek")
        print("      IP'ye çevirir ve vekil dışından çıkabilir — bilinçli seçim.")
        print("   2) Yalnız tek seferlik iş için /etc/hosts satırı ekle;")
        print("      IP değişince bayatlar, kalıcı çözüm değil.")
        print("   3) Aracın kendi çözümleyicisini kullan (curl/wget) — DNS'e hiç gitmez.")
        return 1
    print("SONUÇ: çözümleme bozuk, Fake-IP de değil — çözümleyici erişilemiyor olabilir.")
    print("  Ayarlar › Ağ › Sandbox DNS'i kontrol et; custom modda sunucu listesi boşsa")
    print("  davranış auto'ya döner.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
