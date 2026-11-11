#!/bin/sh
# Builds the drug catalog now (if missing) and then daily at 05:17 UTC.
# A failed build is logged and the previous files stay in place.
set -eu

CATALOG_DIR=${CATALOG_DIR:-/catalog}
RPL_URL=https://rejestry.ezdrowie.gov.pl/api/rpl/medicinal-products/public-pl-report/6.0.0/overall.xml

build() {
  python test_build.py
  curl -fsSL --retry 5 --retry-delay 30 --max-time 600 -o /tmp/overall.xml "$RPL_URL"
  rm -rf /tmp/out
  python build.py /tmp/overall.xml /tmp/out
  # database first, then version.json: clients never see a version pointing at an old file
  mv /tmp/out/leki.sqlite.gz "$CATALOG_DIR/.leki.tmp"
  mv "$CATALOG_DIR/.leki.tmp" "$CATALOG_DIR/leki.sqlite.gz"
  mv /tmp/out/version.json "$CATALOG_DIR/.version.tmp"
  mv "$CATALOG_DIR/.version.tmp" "$CATALOG_DIR/version.json"
  rm -f /tmp/overall.xml
}

run_build() {
  echo "$(date -u +%FT%TZ) catalog build starting"
  if (build); then
    echo "$(date -u +%FT%TZ) catalog build ok"
  else
    echo "$(date -u +%FT%TZ) catalog build FAILED, keeping previous files"
  fi
}

seconds_until_next_run() {
  now=$(date -u +%s)
  target=$(date -u -d "$(date -u +%F) 05:17:00" +%s)
  if [ "$target" -le "$now" ]; then
    target=$((target + 86400))
  fi
  echo $((target - now))
}

mkdir -p "$CATALOG_DIR"
if [ ! -f "$CATALOG_DIR/version.json" ]; then
  run_build
fi
while true; do
  wait_s=$(seconds_until_next_run)
  echo "$(date -u +%FT%TZ) next build in ${wait_s}s"
  sleep "$wait_s"
  run_build
done
