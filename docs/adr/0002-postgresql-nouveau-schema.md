# ADR 0002 — PostgreSQL 16 et nouveau schéma, migration par ETL

- **Statut** : Proposé (basé sur les valeurs par défaut de [assumptions.md](../design/assumptions.md), à confirmer)
- **Date** : 2026-10-16

## Contexte
Le schéma Symfony pose des problèmes structurels : argent en `DOUBLE`, dates en texte, statuts en texte libre, titulaires et expéditeurs mélangés dans `customer`, soldes par pays parallèles, aucune contrainte d'unicité sur les numéros de transaction. La refonte rend la base partagée impossible.

## Décision
- Nouvelle base **PostgreSQL 16**, schéma décrit par [`V1__baseline.sql`](../../src/main/resources/db/migration/V1__baseline.sql) et versionné avec **Flyway**.
- L'ancienne base MySQL n'est **plus partagée** : les données sont reprises par un **ETL idempotent** répété en recette, puis une bascule unique (ADR 0008).
- Principes : `NUMERIC(19,4)` + devise, `timestamptz`, énumérations en `CHECK`, clés étrangères, contraintes d'exclusion (barèmes sans chevauchement), journaux immuables par déclencheurs, identifiant public UUID distinct de la clé interne.
- Les invariants financiers sont **testés sur un vrai PostgreSQL** (`tools/check_schema.sh`).

## Conséquences
+ La base garantit elle-même l'intégrité financière, indépendamment du code applicatif.
+ Types et contraintes adaptés (exclusion, `jsonb`, index partiels, DDL transactionnel).
− Il faut migrer les données et exploiter un second moteur le temps de la transition.
− Les tests d'intégration utilisent Testcontainers PostgreSQL (pas H2).

## Alternatives écartées
Rester sur MySQL (possible, mais pas de contrainte d'exclusion ni de DDL transactionnel, et la base n'est plus contrainte d'être partagée).
