# Phase 0 — inventaire du backend Symfony

Référence de **l'existant** (routes, appelants, intégrations, règles métier, modèle de données, tâches planifiées, statiques).

> **Remplacé par la conception de la refonte** : la carte de routes [`route-map.md`](route-map.md) (73 routes, base partagée, migration progressive) est **supersédée** par le contrat [`../../api/openapi.yaml`](../../api/openapi.yaml) et le plan de bascule unique ([ADR 0008](../adr/0008-bascule-unique-et-anciennes-applications.md)). `routes.csv` reste utile comme table de traçabilité ancien → nouveau.
> Les décisions « MySQL partagée » et « copier tous les partenaires » ne s'appliquent plus (voir [`../design/assumptions.md`](../design/assumptions.md)).
