# ADR 0017 — ETL de migration et bascule

Statut : accepté (phase 10).

## Décisions
1. **ETL en Java dans le même dépôt** (`com.wtc.migration`, profil Spring `etl`), et non un script externe : il réutilise le chiffrement des numéros, le service de grand livre et les règles KYC du produit, donc ce qu'il écrit est relisible et cohérent avec l'API. Il n'est actif qu'avec le profil `etl`. Pilote MariaDB (LGPL) pour lire MySQL 8.
2. **Tout ou rien, à blanc par défaut** : toutes les étapes dans une transaction PostgreSQL ; `wtc.etl.dry-run=true` (défaut) l'annule en gardant le rapport (fichier JSON). La validation exige `--wtc.etl.dry-run=false`. Refus de s'exécuter si la base contient des transferts créés par le nouveau système (`force` pour passer outre).
3. **Idempotent** : `ON CONFLICT (legacy_id)` partout, soldes d'ouverture par référence unique ; une seconde exécution ne change rien (testé).
4. **Rien d'écarté en silence** : chaque rejet, correction ou décision est une ligne du rapport (`etl_report`, avec la valeur d'origine ; numéros de pièce et de compte masqués).
5. **Contrôles de réconciliation bloquants** (code de sortie 2 si écart) : lignes par table (source = migrées + rejetées), somme des soldes par devise (0,01), soldes = grand livre, montants terminés par mois, comptes pouvant se connecter.
6. **Choix de données** : pays corrigés par leur libellé (codes ISO d'origine faux ou en double) ; `ROLE_SUPER` devient `ADMIN` (moindre privilège) ; statut KYC recalculé par la règle du produit, avec pièce de reprise approuvée (sans fichier) pour les clients vérifiés sans pièce exploitable ; transferts migrés financés `OFFLINE_RECEIPT`, sans écriture de grand livre ; « en validation » / « en attente » → `CANCELLED` (jamais de remboursement automatique) ; taux de change et `tauxplatform` non migrés (jamais lus par l'ancien code) ; jetons web conservés valides.

## Conséquences
- Les transferts `PROCESSING` migrés seront interrogés chez Wace après la bascule ; une référence inconnue de Wace les bloque (`AMBIGUOUS_CREATE`) et alerte l'équipe : décision humaine, c'est voulu.
- Les banques et opérateurs « migrés » (placeholders désactivés) sont à relier aux listes Wace après `POST /api/admin/reference/sync` (voir le runbook).
