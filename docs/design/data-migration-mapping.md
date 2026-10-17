# Migration des données : ancien schéma MySQL → nouveau schéma PostgreSQL

**Spécification de l'ETL, implémenté en phase 10** (`com.wtc.migration`, voir l'[ADR 0017](../adr/0017-etl-de-migration-et-bascule.md) pour les écarts de ce document : pays corrigés par libellé, taux non migrés, rôles, jetons web conservés, pièces de reprise). Établi avec les données de la base locale pour vérifier que le nouveau schéma peut tout accueillir. Principes :

- **Idempotent et rejouable** : chaque ligne migrée garde `legacy_id` (et `legacy_source` si deux tables alimentent une même table cible). Relancer l'ETL met à jour sans dupliquer.
- **Rien ne se perd silencieusement** : toute ligne rejetée ou corrigée est écrite dans un rapport (`etl_report`), avec la valeur d'origine ; les valeurs inexploitables sont conservées dans `legacy_attributes` (jsonb).
- **Contrôle de réconciliation obligatoire** avant chaque bascule : nombre de lignes par table, somme des soldes par devise, nombre et montants des transactions par statut, tous comparés ancien/nouveau.
- Les identifiants publics (UUID) sont générés à la migration ; les anciens identifiants ne sont plus exposés.

## Vue d'ensemble

| Ancienne table (lignes locales) | Nouvelle table | Règle principale |
|---|---|---|
| `user` (96) | `users`, `user_roles` | Hash bcrypt repris tel quel ; rôles normalisés ; PIN abandonné |
| `customer` — titulaires (113) | `customers`, `wallets` | `customeralt IS NULL` ; fiche d'identité + portefeuille |
| `customer` — expéditeurs (26) | `parties` (`SENDER`) | `customeralt` = titulaire propriétaire |
| `customer` — titulaires utilisés comme expéditeur (1 158 transactions) | `parties` (`SENDER`) | Une partie `SENDER` créée à partir de la fiche du titulaire |
| `contactcustomer` (604) | `parties` (`BENEFICIARY`), `payout_destinations` | Coordonnées bancaires → destination chiffrée |
| `transaction` (1 231) | `transfers`, `transfer_status_history` | Voir « Transactions » |
| `customer.balance` (6 non nuls) | `wallets`, `ledger_*` | Écriture d'ouverture au grand livre |
| `balance_customer` (54, dont 48 non nuls) | `wallets`, `ledger_*` | Un portefeuille par devise |
| `prefunding` (2) | `top_ups` (`LEGACY`) | Historique, sans écriture au grand livre |
| `country`, `town`, `zone` | `countries`, `cities`, `pricing_zones` | Devises normalisées ISO 4217 |
| `operateur` (1 278) | `banks`, `mobile_operators` | **Réimport depuis Wace** de préférence ; sinon `payer_service = 'B'` → banque |
| `wace_data` (309) | `reference_values` | **Réimport depuis Wace** ; les 3 catégories dépendent du type de transfert |
| `grilletarifaire` (25), `tauxechange` (38), `configuration` (1) | `fee_schedules`, `exchange_rates`, `platform_settings` | Pourcentages repris, dates d'effet = date de migration |
| `login_audit` (9), `user_action_log` (2) | `login_audit`, `audit_log` | `success` → `SUCCESS` / `BAD_CREDENTIALS` |
| `web_token` (2) | `web_tokens` | Jetons hachés ; jetons non expirés révoqués (l'ancienne valeur en clair n'est pas conservée) |
| `refresh_token` (1) | — | Non migré (les sessions sont rouvertes) |
| `analysemarge`, `crypto_monnaie`, `cash_pickup`, `employe`, `partner`, `transaction_sandbox`, `wace_payer_service` | — | Archivés (export), non migrés (voir hypothèse 1) |

## Utilisateurs

| Source | Destination | Règle |
|---|---|---|
| `email` | `users.email` | Minuscules ; aucun doublon en base locale (contrôle d'unicité insensible à la casse) |
| `password` | `password_hash` | Copié ; 96 sur 96 sont bcrypt `$2y$`. Ré-encodage Argon2id à la prochaine connexion |
| `name`, `lastname` | `display_name` | Concaténation |
| `roles` (JSON) | `user_roles` | Aplatir les listes imbriquées (`[["ROLE_USER"]]`) ; `ROLE_ADMIN` → `ADMIN` ; `ROLE_SUPER` → `SUPER_ADMIN` **(à confirmer)** ; `ROLE_CUSTOMER` et `ROLE_USER` → `CUSTOMER` si l'utilisateur a une fiche client, sinon rapport |
| `phone` | `phone_e164` | Normalisation avec l'indicatif du pays (`country_code`/`country`) ; échec → `NULL` + valeur dans le rapport |
| `photo` | `photo_storage_key` | Fichier copié vers le stockage objet |
| `pin_code` (3 en clair) | — | Abandonné ; les 3 clients sont prévenus |
| `authy_id` (nul), `fcm_token`, `chat_id` | — | Abandonnés |
| `login_audit.success` | `outcome` | `1` → `SUCCESS`, `0` → `BAD_CREDENTIALS` (le message libre part dans `audit_log`) |

## Clients, expéditeurs, bénéficiaires

| Source | Destination | Règle |
|---|---|---|
| `customer.type` (`P`/`B`/nul) | `kind` | `B` → `BUSINESS` ; sinon `INDIVIDUAL` ; type nul avec `bussinesname` sans prénom → `BUSINESS` |
| `firstname`, `lastname`, `bussinesname`, `bussinestype` | idem, en anglais | Contrainte `customers_identity` : un `INDIVIDUAL` a un prénom, un `BUSINESS` une raison sociale ; sinon rapport |
| `date_of_birth`, `regiter_business_date` (texte) | `birth_date`, `business_registered_on` (date) | Formats ISO acceptés tels quels ; autres formats analysés (jj/mm/aaaa…) ; échec → `NULL` + rapport (**7 cas locaux**) |
| `country_id` | `country_code` | Via la table pays migrée |
| `isverify` | `kyc_status` | `1` → `VERIFIED`, sinon `NOT_SUBMITTED` ; `expireddatepiece` passée → `EXPIRED` |
| `piecefacefile`, `piecerectofile`, `fileimagename` | `kyc_documents` | Un document `FRONT`, `BACK`, `SELFIE` par fichier présent ; fichier copié vers le stockage privé, empreinte SHA-256 calculée, `status = PENDING` sauf client vérifié (`APPROVED`, relecteur = compte de migration) |
| `typeidentification`, `numeropiece`, `expireddatepiece` | `parties.id_document_*` ; `kyc_documents.expires_on` | Numéro chiffré par l'application ; derniers 4 caractères en clair pour l'affichage |
| `compte_id` | `customers.user_id` | Titulaires avec accès uniquement (80 sur 113) |
| `customeralt`, `partner_id`, `isagent`, `sender_code`, `motif` | `legacy_attributes` | Conservés pour traçabilité |
| `contactcustomer.customer_id` | `parties.customer_id` | Résolu via `customers.legacy_id` |
| `contactcustomer.country_iso_code` | `country_code` | Code ISO (déjà au bon format dans les données saisies par Android) |
| `contactcustomer.bank*` (`bankaccountnumber`, `bankswiftcode`, `bankifsccode`, `bankroutingnumber`, `bankname`, `bankid`) | `payout_destinations` | `BANK_ACCOUNT` si un numéro de compte existe ; `bank_id` résolu par `bankid`/nom, sinon banque factice « Banque inconnue (migrée) » par pays ; numéro chiffré + masqué |

Dédoublonnage : aucun fusionnement automatique de personnes. Deux fiches identiques restent deux fiches (rapport des doublons probables pour revue).

## Transactions → transferts

| Source | Destination | Règle |
|---|---|---|
| `numerotransaction` | `reference` | Conservé tel quel (unique en base locale) ; `numeroidentifiant` → `legacy_number` |
| `customer_id` | `sender_party_id`, `customer_id` | Si la fiche est un expéditeur : partie existante et titulaire = `customeralt`. Si c'est un titulaire (**94 %** des cas) : partie `SENDER` créée à partir de la fiche |
| `beneficiare_id` | `beneficiary_party_id` | Résolu via `legacy_id` |
| Champs bancaires et `operateur` de la transaction | `payout_destination_id` | **Une destination archivée est reconstituée** par transaction (dédoublonnée par bénéficiaire, compte et opérateur), car la colonne est obligatoire |
| `typetransaction` (texte libre, ≈ 30 valeurs) | `kind` | `bancaire`, `Bank transfert`, `bank`, `Virement bancaire`, `Carte bancaire` → `BANK_TRANSFER` ; `mobilemoney`, `mobil`, `MOBIL transfert`, `Mobile Money`, `Wave`, `Point de rechrage` → `MOBILE_MONEY` ; valeurs numériques ou vides → déduites de la destination, sinon rapport |
| `montant`, `fraisenvoi` (double) | `source_amount`, `fee_amount` | Arrondi à 4 décimales avec contrôle d'écart ; `montanttotal` vérifié = montant + frais, écart signalé |
| devise source / destination | `source_currency`, `destination_currency` | Devise du pays d'envoi (hypothèse : XAF) et du pays de destination |
| `business_type` | `party_type_pair` | `P2P`, `P2B`, `B2P`, `B2B` ; valeur absente déduite des deux parties |
| `raisontransaction`, `origin_fonds`, `relaction` | `purpose_code`, `source_of_funds_code`, `relationship_code` | Rapprochés des libellés Wace par nom ; sinon code `LEGACY` + libellé d'origine dans `snapshot` |
| `datetransaction` | `created_at`, `completed_at` | Fuseau `Africa/Brazzaville` converti en UTC ; `completed_at` = date de la transaction pour les terminées |
| `partenairenumero` | `provider_reference` | |
| `isdelete = 1` (**278**) | `hidden_at` | Masquée des listes, jamais supprimée |
| `fileimagename`, `modetransfert`, `branche`, `prestateurservice`, `typeservice`, `partner_id` | `legacy_attributes` | |

### Statuts

| Ancien statut (effectif local) | Nouveau statut | Remarque |
|---|---|---|
| `effectue` (618), `PAID` (75), `SUCCESS` (30) | `COMPLETED` | |
| `validation` (89), `PROCESSING` (5), `LOCKED` (4) | `PROCESSING` | Repris par la réconciliation avec Wace après la bascule |
| `echoue` (45), `FAILED` (5), `CANCELED` (1) | `FAILED` | **Aucun remboursement automatique** : l'ancien système ne remboursait pas toujours ; état de contrôle remis à l'exploitation |
| `en validation` (198), `en attente` (161) | `CANCELLED` + `failure_reason = 'Statut historique non résolu'` | **359 transactions de plus de 90 jours** : client débité, issue inconnue. Export pour traitement manuel, jamais de remboursement automatique |

L'historique (`transfer_status_history`) reçoit une seule ligne par transfert migré (`SYSTEM`, motif « migration »), car l'ancien système n'a pas conservé les transitions. Le statut d'origine est toujours dans `legacy_status`.

Pour les transferts migrés, **aucune écriture au grand livre** n'est créée (`debit_ledger_transaction_id` reste nul) : l'argent est déjà pris en compte dans le solde d'ouverture. Les contraintes du schéma l'autorisent car seuls `REFUNDED` (jamais produit par la migration) exige une écriture.

## Soldes et grand livre

1. Pour chaque client ayant un solde, créer le portefeuille (client, devise) et son compte de grand livre.
2. Créer **une transaction `OPENING_BALANCE` par portefeuille** : crédit du portefeuille, débit de `PLATFORM_OPENING_BALANCE`. Référence : `opening:<legacy_customer_id>:<devise>` (idempotente).
3. Sources : `customer.balance` (devise de la plateforme, **XAF par hypothèse**) et `balance_customer.solde` (devise du pays). Un client qui a les deux obtient deux portefeuilles si les devises diffèrent, un seul (somme **non fusionnée automatiquement**, les deux lignes sont d'abord écrites séparément puis signalées) si elles sont égales.
4. Devises de `country.monaire` : une seule valeur n'est pas un code ISO (`£`). Table de correspondance explicite validée avec l'exploitation (`£` → `GBP`). `XOF` (franc CFA BCEAO) et `XAF` (franc CFA BEAC) sont deux devises distinctes et valides : jamais fusionnées.
5. **Solde négatif** (1 cas) : portefeuille à 0, écart listé pour décision.
6. **Contrôle** : somme des soldes par devise ancien vs nouveau égale à 0,01 près, client par client ; la vue `wallet_balance_discrepancies` doit être vide.

## Recharges (`prefunding`)

Migrées en `top_ups` de méthode `LEGACY` (statut d'origine dans `legacy_status`, preuve copiée vers le stockage privé). Aucune écriture : le solde d'ouverture couvre les recharges déjà créditées. Les recharges `PENDING` locales (2) restent à traiter par un administrateur.

## Référentiels

- `country`, `town`, `zone` : migrés (clés naturelles : code pays ISO) avec table de correspondance des devises.
- `operateur` (1 278) et `wace_data` (309) : **ne pas migrer** ; lancer l'import Wace (`POST /api/admin/reference/sync`) puis relier les destinations migrées par `bank_id` Wace. `payer_service = 'B'` (995 lignes) désigne une banque, les autres valeurs (`MV`, `OM`, `WV`, `ONT`, `MC`, `WM`…) des opérateurs mobiles ; `is_mobil` n'est pas fiable (nul dans 1 256 lignes).
- `grilletarifaire` : une tranche par ligne, `effective_from` = date de bascule ; le chevauchement éventuel des données source est rejeté par la contrainte d'exclusion et rapporté.
- `tauxechange` : 38 lignes réparties sur 15 zones, mappées vers `exchange_rates` avec la devise de la zone.
- `configuration.tauxplatform` : repris dans `platform_settings` (`platform.xaf_usd_rate`).

## Fichiers (`public/uploads`, pièces KYC, preuves)

Copie vers le stockage objet privé avec empreinte SHA-256 ; vérification fichier par fichier (présence, taille). Les noms de fichiers publics ne sont plus utilisés. Les fichiers manquants (références cassées, par exemple les preuves enregistrées sans séparateur `uploads/proofsproof_…`) sont rapportés. Les 15 fichiers de `uploads/` et celui de `uploads/proofs/` sont à reclasser.

## Rapport de contrôle attendu à chaque répétition

| Contrôle | Attendu |
|---|---|
| Lignes migrées par table (avec les rejetées) | égal à la source |
| Somme des soldes par devise (ancien vs nouveau) | égal à 0,01 |
| `wallet_balance_discrepancies` | vide |
| Transactions par statut (mapping appliqué) | totaux identiques |
| Montants des transactions terminées par mois | identiques |
| Utilisateurs pouvant se connecter | tous (hash conservé) |
| Documents KYC migrés | fichiers présents, empreintes valides |
| Anomalies | rapport remis à l'exploitation avant la bascule |
