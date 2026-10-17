#!/usr/bin/env bash
# Valide api/openapi.yaml (OpenAPI 3.1 + règles du projet). Dépendance : pip install openapi-spec-validator pyyaml
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tools/check_openapi.py
