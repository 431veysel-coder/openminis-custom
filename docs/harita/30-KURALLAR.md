<!-- ÜRETİLDİ: scripts/ozel/harita.py — elle düzenlemeyin. -->

# Risk kuralları — 43

| Kural | Seviye | Kapsam | Geri |
|---|---|---|---|
| `termux:sources` | HIGH | ACCOUNT | RECOVERABLE |
| `shell:interpreter-destroy` | HIGH | DEVICE | PERMANENT |
| `shell:interpreter` | MEDIUM | DEVICE | RECOVERABLE |
| `shell:pipe-to-shell` | MEDIUM | DEVICE | RECOVERABLE |
| `shell:eval` | MEDIUM | DEVICE | RECOVERABLE |
| `shell:script-file` | MEDIUM | DEVICE | RECOVERABLE |
| `shell:stdin-shell` | MEDIUM | DEVICE | RECOVERABLE |
| `shell:source` | MEDIUM | DEVICE | RECOVERABLE |
| `sql:destructive` | HIGH | DEVICE | PERMANENT |
| `shell:rm-recursive` | HIGH | SANDBOX | PERMANENT |
| `shell:rm` | MEDIUM | SANDBOX | PERMANENT |
| `shell:find-delete` | HIGH | SANDBOX | PERMANENT |
| `shell:dd` | HIGH | DEVICE | PERMANENT |
| `shell:mkfs` | HIGH | DEVICE | PERMANENT |
| `shell:truncate` | HIGH | SANDBOX | PERMANENT |
| `shell:redirect-overwrite` | HIGH | SANDBOX | PERMANENT |
| `shell:move` | MEDIUM | SANDBOX | RECOVERABLE |
| `shell:chmod-recursive` | MEDIUM | SANDBOX | UNDOABLE |
| `git:push-force` | HIGH | NETWORK | PERMANENT |
| `git:reset-hard` | MEDIUM | SANDBOX | RECOVERABLE |
| `git:clean-f` | MEDIUM | SANDBOX | PERMANENT |
| `git:branch-D` | MEDIUM | SANDBOX | RECOVERABLE |
| `device:sudo` | HIGH | DEVICE | PERMANENT |
| `device:settings` | HIGH | DEVICE | RECOVERABLE |
| `device:pm-bad` | HIGH | DEVICE | PERMANENT |
| `device:am` | LOW | DEVICE | UNDOABLE |
| `device:power` | HIGH | DEVICE | UNDOABLE |
| `device:shizuku-exec` | MEDIUM | DEVICE | RECOVERABLE |
| `net:upload` | HIGH | NETWORK | PERMANENT |
| `net:raw` | HIGH | NETWORK | PERMANENT |
| `net:ssh` | MEDIUM | NETWORK | RECOVERABLE |
| `net:cloud-cli` | HIGH | ACCOUNT | PERMANENT |
| `credential:read` | HIGH | ACCOUNT | UNDOABLE |
| `termux:pkg` | MEDIUM | SANDBOX | UNDOABLE |
| `termux:sshd` | HIGH | NETWORK | PERMANENT |
| `pkg:install` | LOW | SANDBOX | UNDOABLE |
| `pkg:uninstall` | MEDIUM | SANDBOX | RECOVERABLE |
| `plan:long-run` | MEDIUM | SANDBOX | UNDOABLE |

### DENY — kart bile açılmaz

| Kural | Kapsam | Geri |
|---|---|---|
| `deny:root` | DEVICE | PERMANENT |
| `deny:storage` | DEVICE | PERMANENT |
| `deny:fork-bomb` | DEVICE | UNDOABLE |
| `deny:pm-all` | DEVICE | PERMANENT |
| `deny:deny-self` | DEVICE | RECOVERABLE |

Sınıf: **DENY** farz yasak · **ASK** caiz izinle · **SILENT** serbest.
Seviye ≠ sınıf: MEDIUM ve üstü karta düşer, ama hedef kuralı yükseltir
(ajanın kendi çöpünde LOW, `/var/minis/shared`'de HIGH, kökte DENY).

Tek tek sormak için: `python3 scripts/ozel/risk-corpus.py -v "<komut>"`
