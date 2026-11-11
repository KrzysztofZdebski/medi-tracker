#!/usr/bin/env bash
# Ships server/ and catalog/ to kryzys-server and runs `cargo test` in a rust container.
# Usage: server/scripts/test.sh [cargo test args...]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REMOTE=kryzys-server
ARGS=""
for a in "$@"; do ARGS+=" $(printf '%q' "$a")"; done

tar -C "$REPO_ROOT" --exclude='server/target' --exclude='server/.env' -cf - server catalog \
  | ssh "$REMOTE" 'set -e
      D="$HOME/meditracker-src"
      mkdir -p "$D/server"
      # remove stale sources, keep server/target (mount point of the named volume)
      find "$D/server" -mindepth 1 -maxdepth 1 ! -name target -exec rm -rf {} +
      rm -rf "$D/catalog"
      tar -C "$D" -xf -'

set +e
ssh "$REMOTE" "docker run --rm \
  -e HOST_UID=\$(id -u) -e HOST_GID=\$(id -g) \
  -v \$HOME/meditracker-src:/src \
  -v meditracker-cargo:/usr/local/cargo/registry \
  -v meditracker-target:/src/server/target \
  -w /src/server rust:1-slim-bookworm \
  sh -c 'cargo test \"\$@\"; rc=\$?; chown \$HOST_UID:\$HOST_GID /src/server/Cargo.lock 2>/dev/null; exit \$rc' sh$ARGS"
rc=$?
# Keep the lockfile in sync with what cargo resolved remotely (success or failure).
scp -q "$REMOTE:meditracker-src/server/Cargo.lock" "$REPO_ROOT/server/Cargo.lock" || true
exit $rc
