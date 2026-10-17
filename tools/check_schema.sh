#!/usr/bin/env bash
# Charge src/main/resources/db/migration/V1__baseline.sql dans un PostgreSQL 16 jetable (Docker) puis exécute src/test/resources/db/schema-invariants.sql.
# Usage : tools/check_schema.sh        (sortie 0 = schéma valide et tous les invariants tenus)
set -euo pipefail
cd "$(dirname "$0")/.."
NAME="wtc-schema-check-$$"
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT
docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=x -e POSTGRES_DB=wtc postgres:16-alpine >/dev/null
for _ in $(seq 1 40); do docker exec "$NAME" pg_isready -U postgres -d wtc >/dev/null 2>&1 && break; sleep 1; done
sleep 2
echo "== Chargement du schéma"
docker exec -i "$NAME" psql -U postgres -d wtc -v ON_ERROR_STOP=1 -q < src/main/resources/db/migration/V1__baseline.sql
docker exec "$NAME" psql -U postgres -d wtc -At -c "select 'tables: '||count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE'"
echo "== Invariants"
docker exec -i "$NAME" psql -U postgres -d wtc -v ON_ERROR_STOP=1 -q -o /dev/null < src/test/resources/db/schema-invariants.sql 2>&1 | sed -e 's/^psql:[^ ]* //' -e 's/^NOTICE:  //' | tee /tmp/wtc-invariants.log
ok=$(grep -c '^\[OK\]' /tmp/wtc-invariants.log || true)
ko=$(grep -c 'ÉCHEC' /tmp/wtc-invariants.log || true)
echo "== Résultat : $ok invariants vérifiés, $ko échec(s)"
[ "$ko" -eq 0 ]
