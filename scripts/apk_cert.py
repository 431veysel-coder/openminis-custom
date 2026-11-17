"""Extract the signing certificate from an APK's v2/v3 signing block.

apksigner needs a JVM and the JVM refuses to start in this sandbox
("Failed to mark memory page as executable"), so the one check that must never
be skipped by eye — which key signed this APK — is done here instead.

Prints, per certificate: the SHA-256 of the DER certificate, in the same form
`apksigner verify --print-certs` reports, so it can be compared with the value
recorded in GLOBAL.md / SECURITY.md.
"""

import hashlib
import struct
import sys
import zipfile

MAGIC = b"APK Sig Block 42"

# v2 scheme id. v3 has a different id but the certificate layout inside its
# signer block is the same up to the extra min/max SDK fields, so both are read.
V2_ID = 0x7109871A
V3_ID = 0xF05368C0


def read_block(path):
    with open(path, "rb") as f:
        data = f.read()
    i = data.rfind(MAGIC)
    if i < 0:
        return None, []
    # Layout, from the end of the block backwards:
    #   [pairs][uint64 size][16-byte magic]
    # and `size` counts everything AFTER the leading uint64, i.e. the pairs
    # plus the trailing size field plus the magic. Hence the leading size field
    # sits at (magic_end - 8 - size) and the pairs start 8 bytes after it.
    magic_end = i + len(MAGIC)
    size = struct.unpack_from("<Q", data, i - 8)[0]
    block_start = magic_end - 8 - size
    block = data[block_start + 8:i - 8]
    pos = 0
    pairs = []
    while pos + 12 <= len(block):
        pair_len = struct.unpack_from("<Q", block, pos)[0]
        if pair_len < 4:
            break
        pair_id = struct.unpack_from("<I", block, pos + 8)[0]
        value = block[pos + 12: pos + 8 + pair_len]
        pairs.append((pair_id, value))
        pos += 8 + pair_len
    return data, pairs


def read_len_prefixed(buf, pos):
    n = struct.unpack_from("<I", buf, pos)[0]
    return buf[pos + 4: pos + 4 + n], pos + 4 + n


def certs_from_signers(block):
    signers_seq, _ = read_len_prefixed(block, 0)
    out = []
    pos = 0
    while pos < len(signers_seq):
        signer, pos = read_len_prefixed(signers_seq, pos)
        signed_data, _ = read_len_prefixed(signer, 0)
        # signed_data = digests, certificates, [minSdk, maxSdk], attrs
        p = 0
        _, p = read_len_prefixed(signed_data, p)
        certs_seq, p = read_len_prefixed(signed_data, p)
        cp = 0
        while cp < len(certs_seq):
            cert, cp = read_len_prefixed(certs_seq, cp)
            out.append(cert)
    return out


def main():
    path = sys.argv[1]
    data, pairs = read_block(path)
    if not pairs:
        print("no APK signing block — the file is unsigned or v1-only")
        return 1

    seen = set()
    for pair_id, value in pairs:
        if pair_id not in (V2_ID, V3_ID):
            continue
        scheme = "v2" if pair_id == V2_ID else "v3"
        try:
            for cert in certs_from_signers(value):
                digest = hashlib.sha256(cert).hexdigest()
                if digest in seen:
                    continue
                seen.add(digest)
                print(f"{scheme}  SHA-256 {digest}")
        except Exception as exc:  # noqa: BLE001 - a parse failure is a finding
            print(f"{scheme}  could not parse: {type(exc).__name__}: {exc}")

    with zipfile.ZipFile(path) as z:
        names = z.namelist()
    print()
    print(f"entries: {len(names)}")
    print("libproot: " + str(sorted({n for n in names if 'libproot' in n})[:4]))
    print("rootfs:   " + str([n for n in names if 'rootfs' in n][:4]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
