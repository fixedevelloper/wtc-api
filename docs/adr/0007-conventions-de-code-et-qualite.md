# ADR 0007 — Conventions de code, tests et chaîne de qualité

- **Statut** : Proposé (basé sur les valeurs par défaut de [assumptions.md](../design/assumptions.md), à confirmer)
- **Date** : 2026-10-16

## Contexte
Le code actuel n'a ni architecture vérifiée, ni tests avant l'audit, ni standards de style ; les identifiants mêlent français, anglais et fautes (`relaction`, `rounting`).

## Décision
- **Anglais** pour tous les identifiants, tables, colonnes et champs d'API ; français pour les messages utilisateur et la documentation.
- **Tests** : domaine pur (rapide), intégration avec **Testcontainers PostgreSQL**, contrat Wace avec WireMock (réponses enregistrées en sandbox), **ArchUnit**, mutation (PIT) sur le cœur financier, tests de propriétés (jqwik) pour les montants et frais. Couverture du domaine ≥ 85 %.
- **Qualité** : Spotless (format), Checkstyle/PMD, SpotBugs, SonarQube, OWASP Dependency-Check, analyse des secrets ; échec de la CI en cas d'alerte.
- **Process** : trunk-based, revue obligatoire, Conventional Commits, un ADR pour toute décision structurante.
- **Exploitation** : logs JSON avec identifiant de corrélation et masquage des données sensibles, Micrometer/Prometheus, OpenTelemetry, sondes de santé, alertes (transfert bloqué en `PROCESSING`, écart de réconciliation, solde incohérent), runbooks.
- Configuration par variables d'environnement (12-factor) ; aucun secret dans le dépôt.

## Conséquences
+ Les régressions sont détectées automatiquement ; l'architecture ne se dégrade pas silencieusement.
− Investissement initial dans l'outillage (phase 1).

## Alternatives écartées
Standards informels « par convention » (non appliqués, donc non tenus).
