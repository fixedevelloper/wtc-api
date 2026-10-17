#!/usr/bin/env python3
"""Génère docs/design/api-overview.md et docs/design/frontend-migration.md à partir de api/openapi.yaml.

Le guide de migration croise les appels réels d'Android (ApiService.kt) et du web (routes.csv) avec `x-replaces`.
Usage : python3 tools/api_docs.py
"""
import csv, collections, pathlib, re
import yaml

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = yaml.safe_load((ROOT / 'api/openapi.yaml').read_text())
norm = lambda s: re.sub(r'\{[^}]+\}', '{}', s.strip())

ops = []
for path, item in spec['paths'].items():
    for method, o in item.items():
        ops.append({'method': method.upper(), 'path': '/api' + path, 'o': o})

def access(o):
    if o.get('security') == []: return 'publique'
    sec = o.get('security')
    if sec and 'serviceKey' in sec[0]: return 'clé de service'
    if sec and 'waceSignature' in sec[0]: return 'signature Wace'
    if 'x-required-permission' in o: return f"`{o['x-required-permission']}`"
    return 'connecté'

# ------------------------------------------------------------------ api-overview.md
lines = ['# Vue d\'ensemble de l\'API', '',
         f"Générée depuis [`api/openapi.yaml`](../../api/openapi.yaml) — **{len(ops)} opérations**, base `/api`. Ne pas modifier à la main : `python3 tools/api_docs.py`.", '',
         'Légende : **Accès** = qui peut appeler ; **Idem.** = en-tête `Idempotency-Key` obligatoire ; **Origine** = *existante* (remplace une route Symfony) ou *nouvelle*.', '']
by_tag = collections.OrderedDict((t['name'], []) for t in spec['tags'])
for x in ops: by_tag[x['o']['tags'][0]].append(x)
for tag, items in by_tag.items():
    if not items: continue
    lines += [f'## {tag}', '', '| Méthode | Route | Résumé | Accès | Idem. | Origine |', '|---|---|---|---|---|---|']
    for x in items:
        o = x['o']
        idem = 'oui' if any(p.get('$ref', '').endswith('/IdempotencyKey') for p in o.get('parameters', [])) else ''
        pend = ' ⚠ Wace' if 'x-pending' in o else ''
        lines.append(f"| {x['method']} | `{x['path']}` | {o['summary']}{pend} | {access(o)} | {idem} | {'existante' if o['x-origin'] == 'existing' else 'nouvelle'} |")
    lines.append('')
(ROOT / 'docs/design/api-overview.md').write_text('\n'.join(lines))

# ------------------------------------------------------------------ frontend-migration.md
replaced = collections.defaultdict(list)
for x in ops:
    for r in x['o'].get('x-replaces', []):
        m, p = r.split(' ', 1)
        replaced[(m, norm(p))].append(x)

android = []
src = (ROOT.parent / 'transfertWeDoc/app/src/main/java/com/ballack/transfertwedoc/data/api/ApiService.kt').read_text()
for m in re.finditer(r'@(GET|POST|PUT|DELETE|PATCH)\("([^"]+)"\)', src):
    android.append((m.group(1), '/' + m.group(2).lstrip('/')))
paylink = []
web = []
for row in csv.DictReader(open(ROOT / 'docs/phase0/routes.csv')):
    if 'web' in row['callers'].split('+'): web.append((row['method'], row['path']))

def table(calls, who):
    rows, missing = [], []
    for m, p in sorted(set(calls), key=lambda c: (c[1], c[0])):
        hit = replaced.get((m, norm(p)))
        if not hit:
            missing.append((m, p)); continue
        news = '<br>'.join(f"`{h['method']} {h['path']}`" for h in hit)
        rows.append(f"| {m} `{p}` | {news} |")
    return rows, missing

out = ['# Guide de migration des frontends', '',
       'Généré depuis le contrat OpenAPI (`x-replaces`) et les appels réels des clients. Ne pas modifier à la main : `python3 tools/api_docs.py`.', '',
       '## Changements transverses (à traiter une fois, dans la couche réseau)', '',
       '| Sujet | Avant | Après |', '|---|---|---|',
       '| Base d\'URL | `/v2/...`, `/v2/admin/...`, `/auth/...` | `/api/...` (une seule base, sans version) |',
       '| Identité | identifiant du client dans l\'URL ou le corps (`/v2/profile/{id}`, `customer_id`) | **jeton uniquement** : routes `/api/me/...` |',
       '| Enveloppe de réponse | `{status, code, message, data}` | objet direct ; listes paginées `{page, size, totalElements, totalPages, content[]}` |',
       '| Erreurs | `{status:"error", error:{details}}` | `application/problem+json` : `{type, title, status, detail, code, errors[]}` ; se brancher sur `code` |',
       '| Noms de champs | snake_case, fautes historiques (`acount_number`, `relaction`, `rounting_number`) | camelCase, orthographe corrigée |',
       '| Montants | nombres JSON ; frais et total calculés par le client | chaînes décimales + devise ; **le serveur fait foi** (`POST /api/me/transfers/quote`) |',
       '| Dates | formats variés (`"D d,M,y h:i"`, `Y-m-d`) | ISO-8601 UTC |',
       '| Pagination | `page` à partir de 1 / `offset` selon la route | `page` à partir de 0, `size` ≤ 100, `sort=champ,asc` |',
       '| Authentification | JWT 1 h sans renouvellement | JWT court + **refresh token à rotation** ; 2FA pour le personnel |',
       '| Transferts / recharges | aucun contrôle de rejeu | en-tête **`Idempotency-Key`** obligatoire (UUID généré par l\'application) |',
       '| Mises à jour forcées | — | `GET /api/meta/client-requirements` au démarrage |', '']
rows, missing = table(android, 'android')
out += ['## Android', '', f'{len(android)} appels déclarés dans `ApiService.kt`. Les appels de `PayLinkApi.kt` (`api/paylinks`, `bills`, `withdraw`, `PAYLINK_BASE_URL`) relèvent d\'un autre microservice et ne changent pas.', '', '| Appel actuel | Remplacé par |', '|---|---|'] + rows + ['']
pl = [f"`{m} {p}`" for m, p in missing]
if pl:
    out += ['**Hors de ce backend** (microservice PayLink, `PAYLINK_BASE_URL`) ou statiques non remplacées : ' + ', '.join(pl) + '.', '']
rows, missing = table(web, 'web')
out += ['## Web (back-office Next.js)', '', f'{len(set(web))} appels recensés (`Constant.ts` et composants).', '', '| Appel actuel | Remplacé par |', '|---|---|'] + rows + ['']
if missing: out += ['**Sans remplaçant :** ' + ', '.join(f'`{m} {p}`' for m, p in missing) + '.', '']
out += ['## Écrans et fonctions nouveaux côté front', '',
        '- **Web** : validation des ajustements de solde (double validation), revue des documents KYC, réconciliation Wace, gestion des comptes du personnel, activation de la 2FA, saisie d\'un transfert avec justificatif de paiement hors plateforme.',
        '- **Android** : devis avant envoi, gestion des destinations d\'un bénéficiaire (compte bancaire ou portefeuille mobile), relevé du portefeuille, documents KYC, recharge Wace Pay-In, réinitialisation du mot de passe.', '']
(ROOT / 'docs/design/frontend-migration.md').write_text('\n'.join(out))
print(f'{len(ops)} opérations ; Android {len(android)} appels ; web {len(set(web))} appels')
