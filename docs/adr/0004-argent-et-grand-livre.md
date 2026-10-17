# ADR 0004 — Argent exact et grand livre à double entrée

- **Statut** : Proposé (basé sur les valeurs par défaut de [assumptions.md](../design/assumptions.md), à confirmer)
- **Date** : 2026-10-16

## Contexte
Les soldes sont des `DOUBLE` modifiés par lecture-écriture dans plusieurs contrôleurs ; un transfert annulé chez Wace n'était pas remboursé ; des soldes parallèles par pays coexistent avec le solde principal ; aucun historique ne permet de rapprocher un solde.

## Décision
- Tous les montants sont des `BigDecimal` en Java et `NUMERIC(19,4)` en base, avec une devise ; arrondi explicite (demi supérieur) ; jamais de flottant.
- **Grand livre à double entrée** : écritures immuables, équilibrées par transaction et par devise (vérifié au COMMIT par un déclencheur différé). Le solde d'un portefeuille est **matérialisé** (`wallets.balance`) pour la lecture et ne peut pas devenir négatif ; la vue `wallet_balance_discrepancies` doit toujours être vide et sert de contrôle d'exploitation.
- Débit atomique : `UPDATE wallets SET balance = balance - :x WHERE id = :id AND balance >= :x` dans la même transaction SQL que l'écriture et le transfert.
- Un portefeuille **par client et par devise**.
- Chaque événement d'argent a une `reference` unique (clé d'idempotence métier), si bien qu'un rejeu ne peut pas créditer ou débiter deux fois.
- Les ajustements manuels exigent une **double validation** (initiateur ≠ approbateur, contrainte `CHECK`) ; tout est audité.

## Conséquences
+ Un solde est toujours explicable par ses écritures ; réconciliation et audit simplifiés.
+ Les erreurs de rejeu et de double remboursement deviennent impossibles par construction.
− Plus de tables et de code qu'une simple colonne de solde ; il faut apprendre à l'équipe la notation débit/crédit.

## Alternatives écartées
Garder un solde modifiable sans historique (cause des écarts actuels) ; stocker uniquement des mouvements sans solde matérialisé (lectures plus coûteuses).
