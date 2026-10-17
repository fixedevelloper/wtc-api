#!/usr/bin/env python3
"""Valide api/openapi.yaml : conformité OpenAPI 3.1 + règles de conception du projet.

Règles : operationId uniques, aucune route /me avec identifiant de client, routes d'argent avec Idempotency-Key,
routes d'administration avec x-required-permission, erreurs en problem+json, pas de numéro de version dans les chemins,
et couverture des routes Symfony « à porter » (docs/phase0/routes.csv).
Usage : PYTHONPATH=<chemin de openapi-spec-validator> python3 tools/check_openapi.py
"""
import csv, pathlib, re, sys, collections
import yaml
from openapi_spec_validator import validate

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = yaml.safe_load((ROOT / 'api' / 'openapi.yaml').read_text())
validate(spec)
print('OpenAPI 3.1 : valide')

errors, ops = [], []
for path, item in spec['paths'].items():
    if re.search(r'/v\d+(/|$)', path): errors.append(f'{path}: numéro de version dans le chemin')
    for method, o in item.items():
        ops.append((method.upper(), path, o))
ids = collections.Counter(o['operationId'] for _, _, o in ops)
errors += [f'operationId en double: {i}' for i, c in ids.items() if c > 1]

MONEY = {'createTransfer', 'createTopUp', 'adminCreateTransfer', 'adminRequestBalanceAdjustment'}
for method, path, o in ops:
    tag = f'{method} {path}'
    if path.startswith('/me') and re.search(r'\{(customerId|userId)\}', path): errors.append(f'{tag}: identifiant client dans une route /me')
    if path.startswith('/admin') and 'x-required-permission' not in o: errors.append(f'{tag}: x-required-permission manquant')
    if path.startswith('/admin') and o.get('security') == []: errors.append(f'{tag}: route admin publique')
    if o['operationId'] in MONEY and not any(p.get('$ref', '').endswith('/IdempotencyKey') for p in o.get('parameters', [])): errors.append(f'{tag}: Idempotency-Key manquant')
    for code, r in o['responses'].items():
        if code.startswith(('4', '5')) and not r.get('$ref', '').startswith('#/components/responses/'): errors.append(f'{tag}: réponse {code} sans problem+json')
    if method in ('POST', 'PUT', 'PATCH') and 'requestBody' not in o and o['responses'].get('204') is None and not path.endswith(('/sync', '/enrollment', '/reference/sync')) and 'mfa-reset' not in path:
        pass
no_sec = [f'{m} {p}' for m, p, o in ops if o.get('security') == []]

# couverture des routes à porter
covered = set()
for _, _, o in ops:
    for r in o.get('x-replaces', []): covered.add(r)
norm = lambda s: re.sub(r'\{[^}]+\}', '{}', s)
covered_n = {norm(c) for c in covered}
missing = []
for row in csv.DictReader(open(ROOT / 'docs/phase0/routes.csv')):
    if row['decision'].startswith('PORTER') and row['callers'] not in ('-',) and 'partenaire' not in row['callers']:
        if norm(f"{row['method']} {row['path']}") not in covered_n:
            missing.append(f"{row['method']} {row['path']} ({row['callers']})")
print(f'{len(ops)} opérations, {len(spec["paths"])} chemins, {len(spec["components"]["schemas"])} schémas')
print('publiques :', len(no_sec), '| existantes (remplacent une route) :', sum(o["x-origin"] == "existing" for _, _, o in ops), '| nouvelles :', sum(o["x-origin"] == "new" for _, _, o in ops))
print('routes appelées par Android/web non couvertes :', missing or 'aucune')
for e in errors: print('ERREUR', e)
sys.exit(1 if errors or missing else 0)
