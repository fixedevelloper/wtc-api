#!/usr/bin/env bash
# Tous les contrôles de la phase 0b : schéma + invariants (PostgreSQL jetable via Docker), contrat OpenAPI, docs générées.
# Prérequis : Docker, python3 avec `pip install -r tools/requirements.txt`.
set -euo pipefail
cd "$(dirname "$0")/.."
echo "== 1/3 Schéma PostgreSQL et invariants financiers"
tools/check_schema.sh | tail -3
echo "== 2/3 Contrat OpenAPI 3.1 et règles de conception"
python3 tools/check_openapi.py
echo "== 3/3 Documents générés depuis le contrat"
python3 tools/api_docs.py
echo "Tout est vert."
