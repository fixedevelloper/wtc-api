# Modèle de données (base MySQL partagée)

Relevé sur la base de développement `moneytransfert` (MySQL 8.0). DDL complet : [schema-actual.sql](schema-actual.sql). **26 tables**, identiques aux entités Doctrine (aucune dérive hormis la migration `agent_id` en attente). Les volumes sont ceux de la base locale, pas de la production.

| Table | Lignes | Colonnes | Clés étrangères | Unicité (hors PK) |
|---|---:|---:|---|---|
| `operateur` | 1278 | 10 | country_id→country | — |
| `transaction` | 1231 | 32 | beneficiare_id→contactcustomer, country_id→country, customer_id→customer, town_id→town | — |
| `town` | 1038 | 4 | country_id→country | — |
| `contactcustomer` | 604 | 31 | customer_id→customer | — |
| `wace_data` | 309 | 4 | — | — |
| `analysemarge` | 178 | 12 | — | — |
| `customer` | 139 | 32 | compte_id→user, country_id→country | compte_id |
| `user` | 96 | 14 | — | email |
| `doctrine_migration_versions` | 62 | 3 | — | — |
| `balance_customer` | 54 | 4 | country_id→country, customer_id→customer | — |
| `crypto_monnaie` | 50 | 16 | — | — |
| `tauxechange` | 38 | 6 | zone_id→zone | — |
| `grilletarifaire` | 25 | 5 | zone_id→zone | — |
| `country` | 24 | 9 | zone_id→zone | — |
| `zone` | 18 | 3 | — | — |
| `login_audit` | 9 | 7 | user_id→user | — |
| `employe` | 8 | 5 | compte_id→user | compte_id |
| `partner` | 6 | 10 | — | — |
| `prefunding` | 2 | 13 | country_id→country, crypto_monaire_id→crypto_monnaie, customer_id→customer | — |
| `user_action_log` | 2 | 6 | user_id→user | — |
| `web_token` | 2 | 10 | — | token |
| `configuration` | 1 | 5 | — | — |
| `refresh_token` | 1 | 4 | user_id→user | token |
| `transaction_sandbox` | 1 | 30 | beneficiare_id→contactcustomer, country_id→country, customer_id→customer, town_id→town | — |
| `cash_pickup` | 0 | 13 | beneficiare_id→contactcustomer, country_id→country, customer_id→customer, town_id→town | — |
| `wace_payer_service` | 0 | 8 | — | — |

## Colonnes monétaires (écarts de type à traiter)

| Table.colonne | Type actuel | Remarque |
|---|---|---|
| `analysemarge.montanttotal` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `analysemarge.montant` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `analysemarge.fraisenvoi` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `analysemarge.montantfrais` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `analysemarge.tauxjours` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `balance_customer.solde` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `cash_pickup.amount_to_paid` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `configuration.tauxplatform` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `country.operateur` | `json` |  |
| `crypto_monnaie.taux` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `crypto_monnaie.taux_sell` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `customer.balance` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `grilletarifaire.frais` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `partner.balance` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `partner.balance_sandbox` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `prefunding.amount` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `prefunding.total` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `tauxechange.montant` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `tauxechange.montant2` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `transaction.montant` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `transaction.fraisenvoi` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `transaction.montanttotal` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `transaction.operateur` | `varchar(255)` |  |
| `transaction_sandbox.montant` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `transaction_sandbox.fraisenvoi` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `transaction_sandbox.montanttotal` | `double` | flottant : lire en BigDecimal ; conversion en DECIMAL(15,2) à planifier |
| `transaction_sandbox.operateur` | `varchar(255)` |  |
| `web_token.amount` | `decimal(15,2)` |  |

Colonnes de **dates stockées en texte** (`varchar`) :

- `contactcustomer.dob` — `varchar(255)`
- `contactcustomer.expired_document` — `varchar(255)`
- `contactcustomer.regiter_business_date` — `varchar(255)`
- `customer.date_of_birth` — `varchar(255)`
- `customer.regiter_business_date` — `varchar(255)`
