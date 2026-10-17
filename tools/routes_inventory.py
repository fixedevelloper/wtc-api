#!/usr/bin/env python3
"""Inventaire des routes du backend Symfony recoupé avec les appelants connus (Android, web).

Sources : routeur Symfony (debug:router + table d'accès de sécurité), ApiService.kt (Android), Constant.ts et
tests/Api/ClientContractTest.php (web). Sortie : docs/phase0/routes.csv et routes-summary.md.
Usage : python3 tools/routes_inventory.py
"""
import csv, json, re, subprocess, collections, pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
BACKEND = ROOT / 'wetransfercashapi'
OUT = ROOT / 'wtc-spring' / 'docs' / 'phase0'


def norm(path: str) -> str:
    """Chemin normalisé : paramètres {x} -> {}, sans slash final, sans slash initial."""
    return re.sub(r'\{[^}]+\}', '{}', path.strip('/'))


def symfony_routes():
    raw = subprocess.run(['php', 'bin/console', 'debug:router', '--format=json'], cwd=BACKEND,
                         env={'APP_ENV': 'test', 'PATH': '/usr/bin:/bin:/usr/local/bin'}, capture_output=True, text=True).stdout
    data = json.loads(raw[raw.index('{'):])
    rows = []
    for name, r in data.items():
        methods = r['method'].split('|') if r['method'] != 'ANY' else ['GET']
        ctl = (r.get('defaults') or {}).get('_controller', '') if isinstance(r.get('defaults'), dict) else ''
        rows.append({'name': name, 'path': r['path'], 'methods': methods, 'controller': ctl.replace('App\\Controller\\', '')})
    return rows


def access_map_bulk(calls):
    script = f"""<?php
require '{BACKEND}/vendor/autoload.php';
(new Symfony\\Component\\Dotenv\\Dotenv())->bootEnv('{BACKEND}/.env');
$k = new App\\Kernel('test', false); $k->boot();
$map = $k->getContainer()->get('test.service_container')->get('security.access_map');
$out = [];
foreach (json_decode(file_get_contents('php://stdin'), true) as [$m, $p]) {{
    [$attr] = $map->getPatterns(Symfony\\Component\\HttpFoundation\\Request::create($p, $m));
    $out[$m . ' ' . $p] = $attr ?? [];
}}
echo json_encode($out);"""
    r = subprocess.run(['php', '-r', script.replace('<?php', '', 1)], input=json.dumps(calls), capture_output=True, text=True)
    return json.loads(r.stdout[r.stdout.index('{'):])


def android_calls():
    src = (ROOT / 'transfertWeDoc/app/src/main/java/com/ballack/transfertwedoc/data/api/ApiService.kt').read_text()
    calls = set()
    for m in re.finditer(r'@(GET|POST|PUT|DELETE|PATCH)\("([^"]+)"\)', src):
        calls.add((m.group(1), norm(m.group(2))))
    return calls


def web_calls():
    """Appels du back-office : constantes de Constant.ts + liste curée du test de contrat."""
    calls = set()
    test = (BACKEND / 'tests/Api/ClientContractTest.php').read_text()
    block = re.search(r"\$webTarget = \[(.*?)\];", test, re.S).group(1)
    for m in re.finditer(r"\['(GET|POST|PUT|DELETE)', '([^']+)'\]", block):
        calls.add((m.group(1), norm(re.sub(r'/\d+', '/{}', m.group(2)))))
    for ref in ('countries', 'cities/{}', 'wace_data', 'banklists/{}', 'operatorslists/{}', 'tauxechanges/{}'):
        calls.add(('GET', norm('v2/' + ref)))
    return calls


def main():
    routes = symfony_routes()
    pairs = [(m, r['path']) for r in routes for m in r['methods']]
    concrete = [(m, re.sub(r'\{[^}]+\}', '1', p)) for m, p in pairs]
    acc = access_map_bulk(concrete)
    android, web = android_calls(), web_calls()

    seen = collections.defaultdict(list)
    rows = []
    for r in routes:
        for m in r['methods']:
            key = (m, norm(r['path']))
            seen[key].append(r['name'])
            attr = acc.get(f"{m} {re.sub(r'{[^}]+}', '1', r['path'])}", [])
            access = ('ADMIN' if 'ROLE_ADMIN' in attr else 'AUTH' if 'IS_AUTHENTICATED_FULLY' in attr
                      else 'PUBLIC' if (not attr or attr in (['PUBLIC_ACCESS'], ['IS_AUTHENTICATED_ANONYMOUSLY'])) else ','.join(attr))
            p = r['path']
            area = ('v2/admin' if p.startswith('/v2/admin') else 'v1' if p.startswith('/v1') else 'v2' if p.startswith('/v2')
                    else 'v3' if p.startswith('/v3') else 'auth' if p.startswith('/auth') else 'api' if p.startswith('/api') else 'autre')
            callers = [c for c, s in (('android', android), ('web', web)) if key in s]
            if p.startswith('/auth/notifyurl') or p.startswith('/webhook'):
                callers.append('partenaire(callback)')
            rows.append({'method': m, 'path': p, 'route_name': r['name'], 'controller': r['controller'], 'area': area,
                         'access': access, 'callers': '+'.join(callers) or '-', 'key': key})

    dups = {k: v for k, v in seen.items() if len(v) > 1}
    for row in rows:
        row['duplicate_of'] = ';'.join(n for n in dups.get(row['key'], []) if n != row['route_name'])
        del row['key']

    rows.sort(key=lambda x: (x['area'], x['path'], x['method']))
    OUT.mkdir(parents=True, exist_ok=True)
    with open(OUT / 'routes.csv', 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader(); w.writerows(rows)

    by = collections.Counter((r['area'], r['callers'] != '-') for r in rows)
    lines = ['| Zone | Routes | Appelées (Android / web / partenaire) | Sans appelant connu |', '|---|---|---|---|']
    for area in ['v2', 'v2/admin', 'auth', 'api', 'v3', 'v1', 'autre']:
        used, unused = by.get((area, True), 0), by.get((area, False), 0)
        if used + unused:
            lines.append(f'| `/{area}` | {used + unused} | {used} | {unused} |')
    (OUT / 'routes-summary.md').write_text('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    print('\nroutes (méthode+chemin):', len(rows), '| appelants connus:', sum(r['callers'] != '-' for r in rows))
    print('chemin+méthode déclarés plusieurs fois:', {f'{k[0]} /{k[1]}': v for k, v in dups.items()})
    miss = [('android', k) for k in android if k not in {(r['method'], norm(r['path'])) for r in rows}]
    miss += [('web', k) for k in web if k not in {(r['method'], norm(r['path'])) for r in rows}]
    print('appels clients sans route correspondante:', miss)


main()
