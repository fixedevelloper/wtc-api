#!/usr/bin/env bash
# Démarre l'environnement d'essai : PostgreSQL + MinIO jetables (conteneurs dédiés), clés de développement, puis l'API
# (./mvnw spring-boot:run, sans image d'application) en arrière-plan. Les référentiels sont ensuite chargés par seed.sql.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$E2E_ROOT"
tools/dev-keys.sh >/dev/null

docker rm -f "$E2E_PG_CONTAINER" "$E2E_MINIO_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$E2E_PG_CONTAINER" -e POSTGRES_DB=wtc -e POSTGRES_USER=wtc -e POSTGRES_PASSWORD=e2e \
  -p 127.0.0.1:${E2E_PG_PORT}:5432 postgres:16-alpine >/dev/null
docker run -d --name "$E2E_MINIO_CONTAINER" -e MINIO_ROOT_USER=e2eminio -e MINIO_ROOT_PASSWORD=e2eminio-secret-0123456789 \
  -p 127.0.0.1:${E2E_MINIO_PORT}:9000 minio/minio:latest server /data >/dev/null
echo -n "PostgreSQL "; until docker exec "$E2E_PG_CONTAINER" pg_isready -U wtc -d wtc >/dev/null 2>&1; do sleep 1; done; echo "prêt"

if [ -f "$E2E_DIR/api.pid" ] && kill -0 "$(cat "$E2E_DIR/api.pid")" 2>/dev/null; then kill "$(cat "$E2E_DIR/api.pid")"; sleep 2; fi
e2e_api_env
nohup ./mvnw -q spring-boot:run > "$E2E_DIR/api.log" 2>&1 &
echo $! > "$E2E_DIR/api.pid"
echo -n "API (journal : $E2E_DIR/api.log) "
for i in $(seq 1 120); do
  if curl -fs "http://localhost:8081/actuator/health" >/dev/null 2>&1; then echo "prête"; break; fi
  if ! kill -0 "$(cat "$E2E_DIR/api.pid")" 2>/dev/null; then echo "ARRÊTÉE :"; tail -30 "$E2E_DIR/api.log"; exit 1; fi
  sleep 2
done
curl -fs "http://localhost:8081/actuator/health" >/dev/null || { echo "L'API ne démarre pas"; tail -30 "$E2E_DIR/api.log"; exit 1; }

docker exec -i "$E2E_PG_CONTAINER" psql -q -v ON_ERROR_STOP=1 -U wtc -d wtc < tools/e2e/seed.sql
echo "Référentiels chargés. Lancez : python3 tools/e2e/smoke.py"
