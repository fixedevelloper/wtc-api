#!/usr/bin/env bash
# Démarre le back-office (Next.js, mode développement) contre l'API d'essai, sur http://localhost:3000.
set -euo pipefail
source "$(dirname "$0")/env.sh"
WEB="$E2E_ROOT/../wetransfertjs"
cd "$WEB"
[ -d node_modules ] || npm install
export NEXT_PUBLIC_API_URL="http://localhost:${E2E_API_PORT}"
export NEXTAUTH_URL="$E2E_WEB_URL"
export NEXTAUTH_SECRET="e2e-nextauth-secret-not-for-production"
if [ -f "$E2E_DIR/web.pid" ] && kill -0 "$(cat "$E2E_DIR/web.pid")" 2>/dev/null; then echo "Déjà démarré (pid $(cat "$E2E_DIR/web.pid"))"; exit 0; fi
nohup npm run dev -- --port 3000 > "$E2E_DIR/web.log" 2>&1 &
echo $! > "$E2E_DIR/web.pid"
echo -n "Back-office (journal : $E2E_DIR/web.log) "
for i in $(seq 1 60); do curl -fs -o /dev/null "$E2E_WEB_URL/auth/login" && { echo "prêt : $E2E_WEB_URL"; exit 0; }; sleep 2; done
echo "ne répond pas"; tail -20 "$E2E_DIR/web.log"; exit 1
