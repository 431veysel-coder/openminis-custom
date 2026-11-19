#!/usr/bin/env python3
"""APK imza bloğundan (v2/v3) sertifikayı çıkarır, SHA-256 parmak izini verir.

Neden: bu sandbox'ta JVM yok, `apksigner` çalışmıyor. İmzanın bizim anahtarımızla
atıldığını doğrulamak için APK Signing Block doğrudan okunuyor.

Kullanım: python3 apk_cert.py <apk> [beklenen-sha256]
Çıkış kodu 0 = eşleşti (veya beklenti verilmedi), 1 = uyuşmazlık/hata.
"""
from __future__ import annotations

import hashlib
import struct
import sys

MAGIC = b"APK Sig Block 42"
ID_V2 = 0x7109871A
ID_V3 = 0xF05368C0
ID_V31 = 0x1B93AD61


def u32(buf, off):
    return struct.unpack_from("<I", buf, off)[0]


def lp(buf, off):
    """Length-prefixed alan: (veri, sonraki_ofset)."""
    n = u32(buf, off)
    return buf[off + 4: off + 4 + n], off + 4 + n


def der_len(buf, off):
    """ASN.1 DER uzunluğu: (içerik_uzunluğu, başlık_baytı)."""
    b = buf[off + 1]
    if b < 0x80:
        return b, 2
    n = b & 0x7F
    val = 0
    for i in range(n):
        val = (val << 8) | buf[off + 2 + i]
    return val, 2 + n


def find_cert_in_signed_data(sd):
    """signed_data içinde ilk X.509 sertifikasını bulur.

    Yapı (v2 ve v3 aynı): [digests][certificates]. Sertifikalar length-prefixed
    DER. Sayıya güvenmek yerine DER başlığı doğrulanıyor: imza bloğunda tek bir
    bayt kayması sessizce yanlış sertifika okumak demek.
    """
    try:
        _digests, off = lp(sd, 0)
        certs, _ = lp(sd, off)
    except struct.error:
        return None
    off = 0
    while off + 4 < len(certs):
        der, nxt = lp(certs, off)
        if not der or der[0] != 0x30:
            break
        inner, hdr = der_len(der, 0)
        if hdr + inner == len(der):
            return der
        off = nxt
    return None


def signers(value):
    """İmzalayan dizisini gezer; her turda signed_data döner."""
    try:
        total = u32(value, 0)
    except struct.error:
        return
    off = 4
    end = min(len(value), off + total)
    while off < end:
        try:
            signer_len = u32(value, off)
            off += 4
            sd, _ = lp(value, off)
            yield sd
            off += signer_len
        except struct.error:
            return


def extract(path):
    data = open(path, "rb").read()
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        return []
    cd_off = struct.unpack_from("<I", data, eocd + 16)[0]
    magic_off = cd_off - 16
    if data[magic_off:magic_off + 16] != MAGIC:
        return []
    # [uint64 size][pairs][uint64 size][magic]: size covers everything after the
    # first size field, so the block starts at magic + 8 - size. Being off by
    # eight reads a pair id from inside the previous value — the first version of
    # this script did exactly that and reported "no certificates".
    block_size = struct.unpack_from("<Q", data, magic_off - 8)[0]
    start = magic_off + 8 - block_size
    off = start + 8
    out = []
    while off + 12 <= magic_off - 8:
        plen = struct.unpack_from("<Q", data, off)[0]
        pid = u32(data, off + 8)
        value = data[off + 12: off + 8 + plen]
        if pid in (ID_V2, ID_V3, ID_V31):
            for sd in signers(value):
                cert = find_cert_in_signed_data(sd)
                if cert:
                    label = {ID_V2: "v2", ID_V3: "v3", ID_V31: "v3.1"}[pid]
                    out.append((label, hashlib.sha256(cert).hexdigest(), len(cert)))
                    break
        off += 8 + plen
    return out


def main(argv):
    if len(argv) < 2:
        print("kullanım: apk_cert.py <apk> [beklenen-sha256]")
        return 2
    found = extract(argv[1])
    if not found:
        print("sertifika bulunamadı — imza bloğu okunamadı")
        return 1
    expected = argv[2].lower() if len(argv) > 2 else None
    for label, fp, size in found:
        mark = ""
        if expected:
            mark = "  OK eşleşti" if fp == expected else "  FAIL bekleneden farklı"
        print(f"{label}: {fp}{mark}  ({size} bayt)")
    if expected and not any(fp == expected for _, fp, _ in found):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
