# Contribuer

## Avant de commencer

1. `./mvnw verify` doit être vert sur votre machine (Docker requis : les tests d'intégration lancent PostgreSQL).
2. Lire les [ADR](docs/adr/README.md), en particulier 0001 (architecture), 0003 (contrat d'API) et 0004 (argent).

## Règles non négociables

- **Le contrat d'abord.** Toute route ajoutée ou modifiée est d'abord décrite dans [`api/openapi.yaml`](api/openapi.yaml). Le build échoue si une route existe sans y figurer, si la sécurité HTTP diffère du contrat, ou si une réponse ne respecte pas son schéma.
- **Le domaine reste pur.** Pas de Spring, JPA ou Jackson dans `domain` (vérifié par ArchUnit). Les modules se parlent uniquement par leur couche `application`.
- **Jamais de flottant pour l'argent.** `BigDecimal` en Java, `NUMERIC` en base.
- **Le schéma appartient à Flyway.** Nouvelle migration `V<n>__description.sql` ; ne jamais modifier une migration déjà appliquée. Tout nouvel invariant financier s'accompagne d'un test dans `src/test/resources/db/schema-invariants.sql`.
- **Pas de secret dans le dépôt**, pas de donnée personnelle dans les logs ou les fixtures.
- **Erreurs** : lever une `DomainException` avec un `ErrorCode` stable (et l'ajouter au contrat) ; ne jamais renvoyer de détail technique au client.

## Ajouter une fonctionnalité (exemple d'un endpoint)

1. Décrire l'opération dans `api/openapi.yaml` (permission, `Idempotency-Key` si elle déplace de l'argent), puis `python tools/check_openapi.py && python tools/api_docs.py`.
2. Écrire d'abord le test d'intégration (`*IT`) : appel HTTP, vérification de la réponse avec `OpenApiContract.validateResponse(...)`.
3. Implémenter dans le module concerné : `domain` → `application` (+ ports) → `infrastructure` → `api`. La tranche `reference` est le modèle à suivre.
4. `./mvnw spotless:apply verify`.

## Tests

| Suffixe | Exécuté par | Contenu |
|---|---|---|
| `*Test` | `surefire` (rapide) | Unitaires, règles d'architecture |
| `*IT` | `failsafe` (phase `verify`) | Application complète + PostgreSQL réel (Testcontainers) |

## Commits et revues

Conventional Commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`). Une revue est obligatoire ; la CI doit être verte (build, contrat, image).
