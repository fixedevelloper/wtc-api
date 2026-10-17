#!/usr/bin/env bash
# Arrête l'API et supprime les conteneurs d'essai (données comprises).
source "$(dirname "$0")/env.sh"
[ -f "$E2E_DIR/api.pid" ] && kill "$(cat "$E2E_DIR/api.pid")" 2>/dev/null || true
pkill -f "wtc-spring.*spring-boot" 2>/dev/null || true
[ -f "$E2E_DIR/web.pid" ] && kill "$(cat "$E2E_DIR/web.pid")" 2>/dev/null || true
docker rm -f "$E2E_PG_CONTAINER" "$E2E_MINIO_CONTAINER" >/dev/null 2>&1 || true
rm -f "$E2E_DIR"/*.pid "$E2E_DIR"/*.secret "$E2E_DIR"/state.json
echo "Environnement d'essai arrêté et supprimé."
