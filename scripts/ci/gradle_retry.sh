#!/usr/bin/env bash
# [H-013] Gradle adımı için ağ-hatası sarmalayıcısı.
#
# ## Neden var
#
# `.54`'te test derlemesi kırmızı çıktı; sebep kod değil, Maven Central'ın
# `429 Too Many Requests` yanıtıydı. Kırmızı "kodun bozuk" diye okundu ve
# bir tur boşa gitti. H-013'te düzeltme olarak "3 denemeli retry sarmalayıcı"
# yazıldı ama **dosya hiç oluşturulmadı** — `ci-hata-ayir.py` docstring'i
# `ci-retry.sh` diye var olmayan bir betiğe işaret ediyordu. Bu, bu deponun
# en pahalı dersinin tekrarı: *yorumda vaat edilen araç, olmayan araçtır.*
#
# ## Ne yapar
#
# Komutu çalıştırır, çıktısını hem ekrana hem log dosyasına yazar (bu yüzden
# `tee` sözleşmesi korunur). Düşerse logu `ci-hata-ayir.py`'ye verir:
#
#   çıkış 2 → yalnız geçici ağ imzaları var → **yeniden dene** (en fazla 3)
#   çıkış 1 → gerçek hata → hemen dur, log yukarıda
#   çıkış 0 → log temiz ama komut düştü → gerçek hata say
#
# ## Neden `set -e` yok
#
# Bu betiğin işi başarısızlığı **yönetmek**. `set -e`, ilk düşen komutta
# betiği öldürür ve retry mantığı hiç çalışmaz.
set -uo pipefail

LOG="${1:?kullanım: gradle_retry.sh <log-dosyası> <komut...>}"
shift

if [ "$#" -eq 0 ]; then
  echo "gradle_retry.sh: komut verilmedi" >&2
  exit 1
fi

HERE="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$(dirname "$LOG")"

MAX_TRY=3
try=1
while :; do
  echo "── deneme $try/$MAX_TRY: $* ──" | tee -a "$LOG"
  # `tee` boru hattında çıkış kodunu yutar; PIPESTATUS[0] komutun kendi kodudur.
  "$@" 2>&1 | tee -a "$LOG"
  code="${PIPESTATUS[0]}"
  if [ "$code" -eq 0 ]; then
    exit 0
  fi

  python3 "$HERE/scripts/ozel/ci-hata-ayir.py" "$LOG"
  kind=$?
  if [ "$kind" -eq 2 ] && [ "$try" -lt "$MAX_TRY" ]; then
    echo "── ağ hatası (429/5xx); 20 sn sonra yeniden denenecek ──" | tee -a "$LOG"
    sleep 20
    try=$((try + 1))
    continue
  fi
  if [ "$kind" -eq 2 ]; then
    echo "── ağ hatası sürdü; $MAX_TRY deneme bitti ──" | tee -a "$LOG"
  fi
  exit "$code"
done
