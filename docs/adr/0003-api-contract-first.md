# ADR 0003 — API contract-first sous la base `/api`, sans numéro de version

- **Statut** : Proposé (basé sur les valeurs par défaut de [assumptions.md](../design/assumptions.md), à confirmer)
- **Date** : 2026-10-16

## Contexte
Les frontends sont modifiés de toute façon. Les routes actuelles sont réparties sur `/v1`, `/v2`, `/v3` et `/api`, avec des formats incohérents (noms de champs fautifs, enveloppes différentes, identifiant du client dans l'URL).

## Décision
- Le contrat [`api/openapi.yaml`](../../api/openapi.yaml) (OpenAPI 3.1) est **écrit et validé avec les équipes front avant le code** ; les clients TypeScript et Kotlin en sont générés.
- **Base unique `/api`**, sans numéro de version. Évolutions **additives**. Une rupture inévitable passe par un en-tête `Api-Version` et une période de dépréciation documentée.
- Routes du client connecté sous `/api/me/...` : **l'identité vient du jeton**, jamais de l'URL ou du corps. Back-office sous `/api/admin/...`, une permission par opération (`x-required-permission`).
- Erreurs **RFC 9457** (`application/problem+json`) avec un `code` applicatif stable ; pagination `page` (de 0), `size`, `sort` ; JSON en camelCase ; dates ISO-8601 UTC ; montants en chaînes décimales + devise.
- Opérations d'argent : `Idempotency-Key` obligatoire (même clé et même corps = même résultat ; corps différent = 422).
- `tools/check_openapi.py` valide le contrat et ses règles (identifiants uniques, pas de version dans les chemins, idempotence, permissions) et vérifie que chaque route utilisée par Android ou le web est couverte.

## Conséquences
+ Les équipes front et back travaillent en parallèle sur un contrat commun ; moins d'écarts de type.
+ Suppression à la source de la faille de propriété (plus d'identifiant client dans l'URL).
− Le travail de conception précède le code (c'est voulu) ; toute évolution passe par la spécification.

## Alternatives écartées
Code-first (le contrat dériverait du code) ; versionnement dans le chemin (`/v2`) refusé par la demande.
