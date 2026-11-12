#!/bin/sh
# Builds the drug catalog now (if missing) and then daily at 05:17 UTC.
# A failed build is logged and the previous files stay in place.
set -eu

CATALOG_DIR=${CATALOG_DIR:-/catalog}
RPL_URL=${RPL_URL:-https://rejestry.ezdrowie.gov.pl/api/rpl/medicinal-products/public-pl-report/6.0.0/overall.xml}

# Every step must succeed or the function fails: `if build` disables `set -e`,
# so failures are propagated explicitly. Publishing happens only after all steps passed.
build() {
  python test_build.py || return 1
  curl -fsSL --retry 5 --retry-delay 30 --max-time 600 -o /tmp/overall.xml "$RPL_URL" || return 1
  rm -rf /tmp/out
  python build.py /tmp/overall.xml /tmp/out || return 1
  [ -s /tmp/out/leki.sqlite.gz ] && [ -s /tmp/out/version.json ] || return 1
  # database first, then version.json: clients never see a version pointing at an old file
  mv /tmp/out/leki.sqlite.gz "$CATALOG_DIR/.leki.tmp" || return 1
  mv "$CATALOG_DIR/.leki.tmp" "$CATALOG_DIR/leki.sqlite.gz" || return 1
  mv /tmp/out/version.json "$CATALOG_DIR/.version.tmp" || return 1
  mv "$CATALOG_DIR/.version.tmp" "$CATALOG_DIR/version.json" || return 1
  rm -f /tmp/overall.xml
  return 0
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
