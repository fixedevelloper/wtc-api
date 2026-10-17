# Décisions d'architecture (ADR)

Une décision structurante = un fichier. Statut **Proposé** tant que les hypothèses de [assumptions.md](../design/assumptions.md) ne sont pas confirmées.

| N° | Décision |
|---|---|
| [0001](0001-architecture-monolithe-modulaire.md) | Monolithe modulaire en architecture hexagonale |
| [0002](0002-postgresql-nouveau-schema.md) | PostgreSQL 16 et nouveau schéma, migration par ETL |
| [0003](0003-api-contract-first.md) | API contract-first sous la base `/api`, sans numéro de version |
| [0004](0004-argent-et-grand-livre.md) | Argent exact et grand livre à double entrée |
| [0005](0005-wace-seul-fournisseur.md) | Wace Pay comme seul fournisseur de paiement |
| [0006](0006-authentification-et-autorisation.md) | Authentification et autorisation |
| [0007](0007-conventions-de-code-et-qualite.md) | Conventions de code, tests et chaîne de qualité |
| [0008](0008-bascule-unique-et-anciennes-applications.md) | Bascule unique et traitement des anciennes applications |
| [0009](0009-plateforme-technique.md) | Plateforme technique : Java 21 et Spring Boot 4.1 |
| [0010](0010-identite-choix-d-implementation.md) | Identité : choix d'implémentation (Argon2id, rotation, TOTP, garde-fous) |
| [0011](0011-clients-kyc-et-stockage.md) | Clients, KYC, contacts de paiement et stockage des fichiers |
| [0012](0012-tarification-et-change.md) | Tarification et taux de change |
| [0013](0013-portefeuilles-et-grand-livre-en-pratique.md) | Portefeuilles et grand livre : mise en œuvre |
| [0014](0014-transferts-et-envoi-wace.md) | Transferts et envoi Wace |
| [0015](0015-notifications-recu-et-alertes.md) | Notifications, reçu PDF et alertes |
| [0016](0016-taches-planifiees-jetons-web-et-expiration-kyc.md) | Tâches planifiées, jetons web et expiration KYC |
| [0017](0017-etl-de-migration-et-bascule.md) | ETL de migration et bascule |
