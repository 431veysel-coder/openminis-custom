#!/bin/sh
# Push öncesi kapı — Python hattının ETRAFINA işletim sistemi düzeyinde watchdog.
#
# Neden iki kat watchdog: preflight.py kendi içinde bir süre bütçesi tutuyor ve
# aşılırsa çıkış 2 veriyor. Ama bir betiğin kendi watchdog'u, betik yanıt
# vermeyecek biçimde takılırsa (sonsuz regex, kilitli okuma) çalışmaz. `timeout`
# süreç düzeyinde çalışır: Python hiç cevap vermese bile kapı kapanır.
#
# Kural: bu betik ASLA sessizce "temiz" demez. Timeout = çıkış 2 = commit
# engellenir ya da --force ile bilinçli olarak atlanır.
#
# Kullanım:
#   scripts/preflight.sh              # tam denetim
#   scripts/preflight.sh --staged     # hook modu
#   scripts/preflight.sh --all        # baseline'ı yok say
#   PREFLIGHT_TIMEOUT=25 scripts/preflight.sh
set -u

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TIMEOUT="${PREFLIGHT_TIMEOUT:-90}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "preflight: python3 bulunamadı — denetim çalıştırılamadı" >&2
  exit 2
fi

# `timeout` her yerde yok (Alpine busybox'ta var, macOS'ta yok).
if command -v timeout >/dev/null 2>&1; then
  timeout "$TIMEOUT" python3 "$DIR/preflight.py" "$@"
  rc=$?
  # busybox `timeout` SIGTERM ile 143 döndürür, GNU coreutils 124; SIGKILL 137.
  # Üçü de "denetim tamamlanmadı" demektir ve ASLA 0 sayılmamalı.
  if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ] || [ "$rc" -eq 143 ]; then
    echo "preflight: ${TIMEOUT}s sert sınırı aşıldı — denetim TAMAMLANMADI." >&2
    echo "           Bu 'temiz' demek değil. PREFLIGHT_TIMEOUT ile artırın." >&2
    exit 2
  fi
  exit "$rc"
fi

# `timeout` yok: arka planda çalıştır, kendimiz öldür.
python3 "$DIR/preflight.py" "$@" &
pid=$!
( sleep "$TIMEOUT"; kill -9 "$pid" 2>/dev/null ) &
watcher=$!
wait "$pid"
rc=$?
kill "$watcher" 2>/dev/null
if [ "$rc" -eq 137 ] || [ "$rc" -eq 143 ]; then
  echo "preflight: ${TIMEOUT}s sınırı aşıldı — denetim TAMAMLANMADI." >&2
  exit 2
fi
exit "$rc"
