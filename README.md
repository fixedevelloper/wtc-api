# WTC — backend Spring Boot (refonte)

Remplace à terme `wetransfercashapi` (Symfony 5.4). **Ce n'est plus une copie** : le projet reprend les règles métier utiles, mais avec un schéma, un contrat d'API et une architecture conçus selon les bonnes pratiques. **Wace Pay est le seul fournisseur de paiement.**

**État : phase 10 (ETL de migration et procédure de bascule) ; phase 9 (tâches planifiées, jetons web, expiration KYC, synthèse quotidienne) terminée, ≈ 89 des 100 opérations du contrat ; phase 8 (notifications, reçu PDF, alertes) ; phase 6 (transferts et envoi Wace) :** — validée avec un faux fournisseur piloté et un faux serveur HTTP rejouant la spécification Wace ; **pas encore essayée sur la recette de Wace**. Création atomique d'un transfert, envoi, confirmation, suivi par interrogation, remboursement unique, reprise manuelle des cas incertains (≈ 83 des 100 opérations). **Phase 5 :** Grand livre à double entrée idempotent et sûr sous concurrence, portefeuilles multi-devises, relevés, ajustements à double validation, devis de transfert (≈ 75 des 101 opérations). **Phase 4 :** Moteur de frais et de change, administration des barèmes, taux et paramètres, listes de référence (≈ 63 des 98 opérations). **Phase 3 :** Identité (phase 2) et module client sont en place et testés sur PostgreSQL et MinIO : profil, KYC avec pièces en stockage privé, expéditeurs, bénéficiaires et destinations de paiement chiffrés, administration des clients (≈ 54 des 98 opérations du contrat). Le socle (phase 1) et les référentiels publics sont inchangés. Les décisions de conception reposent sur des [hypothèses par défaut](docs/design/assumptions.md) à confirmer.

## Vision

| Sujet | Choix |
|---|---|
| Langage / build | Java 21, Maven (`./mvnw`), Spring Boot 4.1 ([ADR 0009](docs/adr/0009-plateforme-technique.md)) |
| Architecture | Monolithe modulaire, hexagonal, frontières vérifiées par ArchUnit ([ADR 0001](docs/adr/0001-architecture-monolithe-modulaire.md)) |
| Base de données | PostgreSQL 16, **nouveau schéma**, migration des données par ETL ([ADR 0002](docs/adr/0002-postgresql-nouveau-schema.md)) |
| API | **Contract-first** OpenAPI 3.1, base unique `/api` sans version, `/api/me/*` et `/api/admin/*` ([ADR 0003](docs/adr/0003-api-contract-first.md)) |
| Argent | `NUMERIC(19,4)` + devise, **grand livre à double entrée**, portefeuilles multi-devises ([ADR 0004](docs/adr/0004-argent-et-grand-livre.md)) |
| Paiement | Wace Pay uniquement (envoi et encaissement), ports/adaptateurs, outbox, réconciliation ([ADR 0005](docs/adr/0005-wace-seul-fournisseur.md)) |
| Sécurité | JWT court + refresh à rotation, RBAC par permissions, 2FA du personnel ([ADR 0006](docs/adr/0006-authentification-et-autorisation.md), choix détaillés dans l'[ADR 0010](docs/adr/0010-identite-choix-d-implementation.md)) |
| Qualité | Tests Testcontainers, ArchUnit, mutation, CI avec portes qualité ([ADR 0007](docs/adr/0007-conventions-de-code-et-qualite.md)) |
| Bascule | Unique et planifiée, Symfony en lecture seule ensuite ([ADR 0008](docs/adr/0008-bascule-unique-et-anciennes-applications.md)) |

## Livrables de conception (phase 0b)

| Document | Contenu |
|---|---|
| [api/openapi.yaml](api/openapi.yaml) | **Contrat d'API** : 97 opérations, 76 chemins, 104 schémas. Source de vérité |
| [docs/design/api-overview.md](docs/design/api-overview.md) | Vue d'ensemble des routes (générée) |
| [docs/design/frontend-migration.md](docs/design/frontend-migration.md) | **Guide pour les équipes front** : ancien appel Android/web → nouvelle route (généré) |
| [docs/design/frontend-status.md](docs/design/frontend-status.md) | **État de l'adaptation** du back-office web et de l'application Android à cette API |
| [src/main/resources/db/migration/V1__baseline.sql](src/main/resources/db/migration/V1__baseline.sql) | Schéma PostgreSQL (migration Flyway V1) : 38 tables, contraintes, déclencheurs |
| [docs/design/erd.md](docs/design/erd.md) | Diagrammes entité-relation et machine d'états d'un transfert |
| [docs/design/data-migration-mapping.md](docs/design/data-migration-mapping.md) | Spécification de l'ETL ancien → nouveau schéma |
| [docs/design/glossary.md](docs/design/glossary.md) | Vocabulaire métier ↔ technique ↔ ancien code |
| [docs/design/assumptions.md](docs/design/assumptions.md) | Hypothèses retenues et découvertes de données qui imposent une décision |
| [docs/adr/](docs/adr/README.md) | 8 décisions d'architecture |

La [phase 0](docs/phase0/) (inventaire du backend Symfony : routes, intégrations, règles métier, modèle de données) reste la référence de l'existant. Ses recommandations de migration progressive derrière un proxy et de base partagée sont **remplacées** par ce plan.

## Vérifier la conception

```bash
pip install -r tools/requirements.txt     # openapi-spec-validator, pyyaml
tools/check_all.sh                        # nécessite Docker
```

| Contrôle | Ce qui est vérifié |
|---|---|
| `tools/check_schema.sh` | Le schéma se charge sur PostgreSQL 16 ; **55 invariants financiers** sont testés : écritures déséquilibrées refusées, grand livre immuable, solde jamais négatif, ajustement à double validation, barèmes sans chevauchement, idempotence, anti-rejeu des webhooks, cohérence des statuts |
| `tools/check_openapi.py` | Contrat OpenAPI 3.1 valide ; identifiants d'opération uniques ; aucune version dans les chemins ; aucune identité client dans `/me` ; permission déclarée sur chaque route d'administration ; `Idempotency-Key` sur les opérations d'argent ; erreurs en `problem+json` ; **toutes les routes appelées par Android et le web ont un remplaçant** |
| `tools/api_docs.py` | Régénère la vue d'ensemble et le guide de migration des frontends depuis le contrat |

## Ce qui reste à décider ou à obtenir

Voir [assumptions.md](docs/design/assumptions.md). Les points bloquants :

1. **Wace** : la spécification officielle est reçue et analysée ([wace-integration.md](docs/design/wace-integration.md)) ; elle ne décrit ni webhook ni clé d'idempotence, et le Pay-In n'y figure pas. **Il manque encore l'accès à la recette, la liste blanche d'IP et quelques listes de codes.** Les opérations concernées portent `x-pending` dans le contrat.
2. **Nature des soldes par pays** (48 soldes non nuls dans 9 devises) : argent réel ou données de test ?
3. **Traitement des 359 transactions bloquées** depuis plus de 90 jours (client débité, issue inconnue) et du solde négatif.
4. **Anciennes versions d'Android** : mise à jour forcée ou couche de compatibilité temporaire.
5. **Validation du contrat par les équipes web et Android** avant le premier code.

## Démarrer en local

```bash
tools/dev-keys.sh                                  # clés de développement dans .local/
cp .env.example .env                               # renseigner DATABASE_PASSWORD, WTC_SECURITY_ENCRYPTION_KEY (= .local/encryption.key), WTC_STORAGE_*
docker compose --profile app up --build            # API sur http://localhost:8080 (+ PostgreSQL et MinIO)
./mvnw verify                                      # tests, couverture, SpotBugs (Docker requis)
```

Premier compte, exploitation et dépannage : [docs/runbook.md](docs/runbook.md). Règles de contribution : [CONTRIBUTING.md](CONTRIBUTING.md).

## Prérequis techniques

- Java 21 est installé ; **Maven ne l'est pas** (`sudo apt install maven` ou Maven Wrapper avec accès réseau).
- Docker est disponible (PostgreSQL jetable, futur Testcontainers).
- Secrets (clé privée JWT, identifiants Wace) uniquement par variables d'environnement.
