#!/usr/bin/env bash
# Ships server/ and catalog/ to kryzys-server (~/meditracker) and (re)starts the compose project.
# Never removes volumes. Usage: server/deploy.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE=kryzys-server

tar -C "$REPO_ROOT" --exclude='server/target' --exclude='server/.env' -cf - server catalog \
  | ssh "$REMOTE" 'set -e
      D="$HOME/meditracker"
      mkdir -p "$D/server"
      # replace sources, keep server/.env
      find "$D/server" -mindepth 1 -maxdepth 1 ! -name .env -exec rm -rf {} +
      rm -rf "$D/catalog"
      tar -C "$D" -xf -'

ssh "$REMOTE" 'set -e
  cd "$HOME/meditracker/server"
  if [ ! -f .env ]; then
    sed -e "s/^SERVER_SECRET=.*/SERVER_SECRET=$(openssl rand -hex 32)/" \
        -e "s/^EMAIL_MODE=.*/EMAIL_MODE=log/" .env.example > .env
    chmod 600 .env
    echo "created .env (EMAIL_MODE=log)"
  fi
  docker compose -p meditracker up -d --build'
