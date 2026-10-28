#!/usr/bin/env bash
# Deploys Idea Radar on this machine (the CasaOS server). Run from the repo root.
#
# Used by the GitHub Actions runner on every push to main, and can be run by hand:
#   MONGO_PASSWORD=... scripts/deploy.sh
#
# Steps: copy repo -> DEPLOY_DIR (keeping data/, exports/, reports/, .env)
#        -> write .env (only if secrets were passed in) -> docker compose up --build -> health check.
#
# Env:
#   DEPLOY_DIR       target folder            (default /DATA/AppData/idea-radar)
#   HEALTH_URL       collector URL            (default http://localhost:8090)
#   MONGO_PASSWORD   required on first deploy; when set, .env is (re)written from the variables below
#   REDDIT_CLIENT_ID, REDDIT_CLIENT_SECRET, REDDIT_USERNAME, BRAVE_API_KEY   optional
set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/DATA/AppData/idea-radar}"
HEALTH_URL="${HEALTH_URL:-http://localhost:8090}"
SRC="$(cd "$(dirname "$0")/.." && pwd)"

log() { echo "==> $*"; }
fail() { echo "::error::$*" >&2; exit 1; }

for cmd in docker rsync curl; do command -v "$cmd" >/dev/null || fail "$cmd is not installed on this machine"; done
docker compose version >/dev/null 2>&1 || fail "docker compose plugin is missing"

# 1. Copy files. Server-only state is excluded, so --delete never touches it.
log "Syncing $SRC -> $DEPLOY_DIR"
mkdir -p "$DEPLOY_DIR"
rsync -a --delete \
  --exclude '.git/' --exclude '.github/' --exclude '.idea/' \
  --exclude 'collector/build/' --exclude 'collector/.gradle/' --exclude 'play-sidecar/node_modules/' \
  --exclude 'data/' --exclude 'exports/' --exclude 'reports/' --exclude '.env' \
  "$SRC"/ "$DEPLOY_DIR"/

# 2. .env: write from secrets when provided, otherwise keep the existing one.
if [ -n "${MONGO_PASSWORD:-}" ]; then
  log "Writing .env from provided secrets"
  ( umask 077
    {
      echo "MONGO_PASSWORD=$MONGO_PASSWORD"
      echo "REDDIT_CLIENT_ID=${REDDIT_CLIENT_ID:-}"
      echo "REDDIT_CLIENT_SECRET=${REDDIT_CLIENT_SECRET:-}"
      echo "REDDIT_USERNAME=${REDDIT_USERNAME:-}"
      echo "BRAVE_API_KEY=${BRAVE_API_KEY:-}"
    } > "$DEPLOY_DIR/.env" )
elif [ -f "$DEPLOY_DIR/.env" ]; then
  log "No secrets passed; keeping existing .env"
else
  fail "No .env in $DEPLOY_DIR and MONGO_PASSWORD not set"
fi

# 3. Build and (re)start. Unchanged services are left running.
cd "$DEPLOY_DIR"
log "docker compose up -d --build"
docker compose up -d --build --remove-orphans
docker compose ps

# 4. Health check (collector restart takes a few seconds; first build can take minutes before this point).
log "Waiting for $HEALTH_URL/health"
for _ in $(seq 1 36); do
  if curl -fsS "$HEALTH_URL/health" >/dev/null 2>&1; then
    log "Collector is up. Status:"
    curl -fsS "$HEALTH_URL/status"; echo
    docker image prune -f >/dev/null || true
    exit 0
  fi
  sleep 5
done

docker compose logs --tail 80 || true
fail "Collector did not answer on $HEALTH_URL/health within 3 minutes"
