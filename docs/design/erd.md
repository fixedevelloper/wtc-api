# Modèle de données cible — diagrammes

Source de vérité : [`V1__baseline.sql`](../../src/main/resources/db/migration/V1__baseline.sql) (38 tables, chargé et testé sur PostgreSQL 16 par `tools/check_schema.sh`, 55 invariants). Les diagrammes sont découpés par module ; les colonnes d'audit (`created_at`, `updated_at`, `version`) et les colonnes `legacy_*` ne sont pas répétées.

## 1. Identité et clients

```mermaid
erDiagram
    users ||--o{ user_roles : "a"
    roles ||--o{ user_roles : ""
    roles ||--o{ role_permissions : ""
    permissions ||--o{ role_permissions : ""
    users ||--o{ refresh_tokens : "famille de rotation"
    users ||--o{ one_time_tokens : ""
    users ||--o{ login_audit : ""
    users |o--o| customers : "compte d'accès (optionnel)"
    customers ||--o{ parties : "possède"
    parties ||--o{ payout_destinations : "reçoit sur"
    customers ||--o{ kyc_documents : "justifie son identité"
    customers ||--o{ wallets : "un par devise"

    users {
        uuid public_id
        text email "unique, insensible à la casse"
        text password_hash
        text status
        bool mfa_enabled
        bytea mfa_secret_encrypted
    }
    customers {
        uuid public_id
        text kind "INDIVIDUAL | BUSINESS"
        text kyc_status
        char country_code FK
    }
    parties {
        text role "SENDER | BENEFICIARY"
        text kind "INDIVIDUAL | BUSINESS"
        bytea id_document_number_enc "chiffré"
        timestamptz archived_at
    }
    payout_destinations {
        text kind "BANK_ACCOUNT | MOBILE_WALLET"
        bigint bank_id FK
        bigint mobile_operator_id FK
        bytea account_number_enc "chiffré"
        text account_number_masked
    }
    kyc_documents {
        text document_type
        text storage_key "objet privé"
        char sha256
        text status
        bigint reviewed_by FK
    }
```

## 2. Référentiels et tarification

```mermaid
erDiagram
    pricing_zones ||--o{ countries : "regroupe"
    pricing_zones ||--o{ fee_schedules : "tranches datées"
    countries ||--o{ cities : ""
    countries ||--o{ banks : ""
    countries ||--o{ mobile_operators : ""

    countries {
        char code PK "ISO 3166-1 alpha-2"
        char currency_code "ISO 4217"
        bool can_send
        bool can_receive
    }
    fee_schedules {
        numeric min_amount
        numeric max_amount
        numeric percent
        date effective_from
        date effective_to "EXCLUDE : pas de chevauchement"
    }
    reference_values {
        text category "motif | origine des fonds | relation"
        text party_type_pair "ALL | P2P | P2B | B2P | B2B"
        text code
        text wace_code
    }
    exchange_rates {
        char base_currency
        char quote_currency
        numeric rate
        timestamptz effective_from
    }
    platform_settings {
        text key PK
        jsonb value
    }
```

## 3. Portefeuilles et grand livre

```mermaid
erDiagram
    customers ||--o{ wallets : ""
    wallets ||--|| ledger_accounts : "compte de passif"
    ledger_transactions ||--|{ ledger_entries : "équilibrée au COMMIT"
    ledger_accounts ||--o{ ledger_entries : ""
    wallets ||--o{ top_ups : "crédité par"
    wallets ||--o{ balance_adjustments : ""
    top_ups |o--o| ledger_transactions : "crédit unique"
    balance_adjustments |o--o| ledger_transactions : "après approbation"

    wallets {
        char currency_code
        numeric balance "CHECK >= 0, rapproché du grand livre"
        text status
    }
    ledger_accounts {
        text code
        text kind "CUSTOMER_WALLET | PLATFORM"
    }
    ledger_transactions {
        text kind
        text reference UK "clé d'idempotence métier"
    }
    ledger_entries {
        text direction "DEBIT | CREDIT"
        numeric amount "> 0, immuable"
    }
    top_ups {
        text method "WACE_MOBILE_MONEY | MANUAL_PROOF | LEGACY"
        text status
        text provider_reference "unique avec provider"
    }
    balance_adjustments {
        bigint requested_by FK
        bigint decided_by FK "différent de requested_by"
        text status
    }
```

## 4. Transferts, envoi Wace et fiabilité

```mermaid
erDiagram
    customers ||--o{ transfers : "initie"
    parties ||--o{ transfers : "expéditeur"
    parties ||--o{ transfers : "bénéficiaire"
    payout_destinations ||--o{ transfers : "verse sur"
    fee_schedules |o--o{ transfers : "barème appliqué"
    transfers ||--o{ transfer_status_history : "append-only"
    transfers ||--o{ payout_attempts : "appels à Wace"
    transfers |o--o| ledger_transactions : "débit"
    transfers |o--o| ledger_transactions : "remboursement"
    reconciliation_runs ||--o{ reconciliation_items : ""
    reconciliation_items }o--o| transfers : ""
    reconciliation_items }o--o| top_ups : ""

    transfers {
        text reference UK "WTC-2026-000123"
        text kind "BANK_TRANSFER | MOBILE_MONEY"
        text funding_source "WALLET | OFFLINE_RECEIPT"
        numeric source_amount
        numeric fee_amount
        numeric total_debit "colonne calculée"
        text status "machine d'états"
        text idempotency_key "unique par client"
        jsonb snapshot "identités, barème, taux figés"
    }
    payout_attempts {
        int attempt_no
        text outcome "ACCEPTED | REJECTED | AMBIGUOUS | ERROR"
        jsonb request_payload "masqué"
    }
    outbox_events {
        text event_type
        text status
        timestamptz next_attempt_at
    }
    webhook_events {
        text event_id "unique avec provider : anti-rejeu"
        bool signature_valid
    }
```

## Machine d'états d'un transfert

```mermaid
stateDiagram-v2
    [*] --> CREATED : demande validée, portefeuille débité
    CREATED --> PAYOUT_PENDING : ordre Wace placé dans l'outbox
    PAYOUT_PENDING --> PROCESSING : ordre accepté par Wace, ou réponse ambiguë (la réconciliation tranche)
    PAYOUT_PENDING --> FAILED : refus explicite
    PROCESSING --> COMPLETED : Wace confirme le paiement
    PROCESSING --> FAILED : Wace annule
    FAILED --> REFUNDED : remboursement unique et idempotent
    CREATED --> CANCELLED : annulé avant envoi
    COMPLETED --> [*]
    REFUNDED --> [*]
    CANCELLED --> [*]
```

Règles : un transfert `REFUNDED` a toujours son écriture de remboursement ; `COMPLETED` a toujours `completed_at` ; `FAILED` et `REFUNDED` ont toujours un motif (contraintes `CHECK`, voir les invariants testés). Une réponse ambiguë de Wace ne déclenche **jamais** de remboursement : seul un statut confirmé (webhook, interrogation ou réconciliation) le permet.

## Comptes du grand livre : exemples d'écritures

| Événement | Débit | Crédit |
|---|---|---|
| Solde d'ouverture (migration) | `PLATFORM_OPENING_BALANCE` | portefeuille du client |
| Recharge Wace confirmée | `PLATFORM_TOPUP_CLEARING` | portefeuille du client |
| Transfert (montant) | portefeuille du client | `PLATFORM_PAYOUT_CLEARING` |
| Transfert (frais) | portefeuille du client | `PLATFORM_FEE_REVENUE` |
| Remboursement d'un transfert | `PLATFORM_PAYOUT_CLEARING` et `PLATFORM_FEE_REVENUE` | portefeuille du client |
| Saisie admin avec paiement hors plateforme | `PLATFORM_OFFLINE_RECEIPTS` | portefeuille du client, puis débit comme un transfert |
| Ajustement approuvé (crédit) | `PLATFORM_ADJUSTMENTS` | portefeuille du client |
