-- =====================================================================================================
-- WTC — schéma cible V1 (PostgreSQL 16)
-- Brouillon de la phase 0b : source de la future migration Flyway V1__baseline.sql.
-- Conventions : snake_case, tables au pluriel, identifiants anglais.
--   * id            bigint identity (clé interne, jamais exposée)
--   * public_id     uuid (identifiant exposé par l'API : pas d'énumération)
--   * argent        numeric(19,4) + devise ISO 4217 ; jamais de flottant
--   * dates         timestamptz (UTC) ; date pour les dates calendaires
--   * énumérations  text + CHECK (évolution simple par migration)
--   * legacy_id     id de l'ancienne base Symfony, pour l'ETL idempotent et le rapprochement
-- =====================================================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;   -- contraintes d'exclusion (barèmes sans chevauchement)

-- ---------------------------------------------------------------------------------------------------
-- Fonctions communes
-- ---------------------------------------------------------------------------------------------------
CREATE FUNCTION set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    NEW.version := OLD.version + 1;
    RETURN NEW;
END $$;

CREATE FUNCTION forbid_modification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% est immuable (% interdit)', TG_TABLE_NAME, TG_OP USING ERRCODE = 'integrity_constraint_violation';
END $$;

-- =====================================================================================================
-- MODULE identity
-- =====================================================================================================
CREATE TABLE roles (
    code        text PRIMARY KEY,
    description text NOT NULL
);

CREATE TABLE permissions (
    code        text PRIMARY KEY,
    description text NOT NULL
);

CREATE TABLE role_permissions (
    role_code       text NOT NULL REFERENCES roles (code),
    permission_code text NOT NULL REFERENCES permissions (code),
    PRIMARY KEY (role_code, permission_code)
);

CREATE TABLE users (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id             uuid        NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    email                 text        NOT NULL,
    phone_e164            text,
    password_hash         text        NOT NULL,                       -- bcrypt hérité, puis Argon2id
    display_name          text        NOT NULL,
    photo_storage_key     text,
    status                text        NOT NULL DEFAULT 'ACTIVE'
                          CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED')),
    email_verified_at     timestamptz,
    phone_verified_at     timestamptz,
    mfa_enabled           boolean     NOT NULL DEFAULT false,
    mfa_secret_encrypted  bytea,
    failed_login_count    integer     NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until          timestamptz,
    last_login_at         timestamptz,
    password_changed_at   timestamptz,
    legacy_id             bigint UNIQUE,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT users_phone_e164 CHECK (phone_e164 IS NULL OR phone_e164 ~ '^\+[1-9][0-9]{6,14}$'),
    CONSTRAINT users_mfa_secret CHECK (NOT mfa_enabled OR mfa_secret_encrypted IS NOT NULL)
);
CREATE UNIQUE INDEX users_email_lower_uk ON users (lower(email));
CREATE UNIQUE INDEX users_phone_uk ON users (phone_e164) WHERE phone_e164 IS NOT NULL;
CREATE TRIGGER users_touch BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE user_roles (
    user_id   bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_code text   NOT NULL REFERENCES roles (code),
    PRIMARY KEY (user_id, role_code)
);

-- Refresh tokens : seul le hachage est stocké ; la rotation lie chaque jeton à sa famille (détection de rejeu).
CREATE TABLE refresh_tokens (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id     uuid        NOT NULL,
    token_hash    text        NOT NULL UNIQUE,
    issued_at     timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,
    revoked_at    timestamptz,
    replaced_by   bigint REFERENCES refresh_tokens (id),
    device_id     text,
    user_agent    text,
    ip_address    inet,
    CONSTRAINT refresh_tokens_expiry CHECK (expires_at > issued_at)
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);

-- Jetons à usage unique (réinitialisation de mot de passe, vérification d'e-mail, étape MFA).
CREATE TABLE one_time_tokens (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose     text        NOT NULL CHECK (purpose IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION', 'MFA_CHALLENGE')),
    token_hash  text        NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE login_audit (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          bigint REFERENCES users (id) ON DELETE SET NULL,
    email_attempted  text        NOT NULL,
    outcome          text        NOT NULL CHECK (outcome IN ('SUCCESS', 'BAD_CREDENTIALS', 'LOCKED', 'MFA_REQUIRED', 'MFA_FAILED', 'SUSPENDED')),
    ip_address       inet,
    user_agent       text,
    occurred_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX login_audit_user_idx ON login_audit (user_id, occurred_at DESC);
CREATE TRIGGER login_audit_immutable BEFORE UPDATE OR DELETE ON login_audit FOR EACH ROW EXECUTE FUNCTION forbid_modification();

-- =====================================================================================================
-- MODULE reference
-- =====================================================================================================
CREATE TABLE pricing_zones (
    id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code  text NOT NULL UNIQUE,
    name  text NOT NULL,
    legacy_id bigint UNIQUE
);

CREATE TABLE countries (
    code              char(2) PRIMARY KEY CHECK (code ~ '^[A-Z]{2}$'),     -- ISO 3166-1 alpha-2
    iso3              char(3) NOT NULL UNIQUE CHECK (iso3 ~ '^[A-Z]{3}$'),
    name              text    NOT NULL,
    currency_code     char(3) NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
    calling_code      text,
    flag_url          text,
    pricing_zone_id   bigint REFERENCES pricing_zones (id),
    can_send          boolean NOT NULL DEFAULT false,
    can_receive       boolean NOT NULL DEFAULT false,
    enabled           boolean NOT NULL DEFAULT true,
    legacy_id         bigint UNIQUE
);

CREATE TABLE cities (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    country_code  char(2) NOT NULL REFERENCES countries (code),
    name          text    NOT NULL,
    legacy_id     bigint UNIQUE,
    UNIQUE (country_code, name)
);

CREATE TABLE banks (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    country_code   char(2) NOT NULL REFERENCES countries (code),
    name           text    NOT NULL,
    short_code     text,
    wace_bank_id   text,
    enabled        boolean NOT NULL DEFAULT true,
    legacy_id      bigint UNIQUE,
    UNIQUE (country_code, wace_bank_id)
);

CREATE TABLE mobile_operators (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    country_code     char(2) NOT NULL REFERENCES countries (code),
    name             text    NOT NULL,
    short_code       text,
    wace_payer_code  text,
    supports_payout  boolean NOT NULL DEFAULT true,
    supports_payin   boolean NOT NULL DEFAULT false,
    enabled          boolean NOT NULL DEFAULT true,
    legacy_id        bigint UNIQUE,
    UNIQUE (country_code, wace_payer_code)
);

-- Libellés pilotés par Wace (motifs, origines des fonds, liens avec le bénéficiaire). Les listes dépendent du type
-- de transfert (P2P, P2B, B2P, B2B) ; 'ALL' vaut pour tous. Le code métier est stable, wace_code est la valeur envoyée à Wace.
CREATE TABLE reference_values (
    category         text    NOT NULL CHECK (category IN ('TRANSFER_PURPOSE', 'SOURCE_OF_FUNDS', 'RELATIONSHIP')),
    party_type_pair  text    NOT NULL DEFAULT 'ALL' CHECK (party_type_pair IN ('ALL', 'P2P', 'P2B', 'B2P', 'B2B')),
    code             text    NOT NULL,
    label            text    NOT NULL,
    wace_code        text    NOT NULL,
    enabled          boolean NOT NULL DEFAULT true,
    legacy_id        bigint UNIQUE,
    PRIMARY KEY (category, party_type_pair, code)
);

-- Barème de frais : pourcentage par tranche, par zone, daté ; deux tranches ne peuvent pas se chevaucher.
CREATE TABLE fee_schedules (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pricing_zone_id bigint        NOT NULL REFERENCES pricing_zones (id),
    currency_code   char(3)       NOT NULL,
    min_amount      numeric(19,4) NOT NULL CHECK (min_amount >= 0),
    max_amount      numeric(19,4) NOT NULL,
    percent         numeric(7,4)  NOT NULL CHECK (percent >= 0 AND percent <= 100),
    fixed_fee       numeric(19,4) NOT NULL DEFAULT 0 CHECK (fixed_fee >= 0),
    effective_from  date          NOT NULL,
    effective_to    date,
    created_by      bigint REFERENCES users (id),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT fee_schedules_range CHECK (max_amount > min_amount),
    CONSTRAINT fee_schedules_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT fee_schedules_no_overlap EXCLUDE USING gist (
        pricing_zone_id WITH =,
        currency_code   WITH =,
        numrange(min_amount, max_amount, '(]') WITH &&,
        daterange(effective_from, effective_to, '[)') WITH &&
    )
);

CREATE TABLE exchange_rates (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    base_currency   char(3)       NOT NULL,
    quote_currency  char(3)       NOT NULL,
    rate            numeric(24,10) NOT NULL CHECK (rate > 0),
    effective_from  timestamptz   NOT NULL,
    source          text          NOT NULL DEFAULT 'MANUAL',
    created_by      bigint REFERENCES users (id),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT exchange_rates_pair CHECK (base_currency <> quote_currency),
    UNIQUE (base_currency, quote_currency, effective_from)
);

CREATE TABLE platform_settings (
    key         text PRIMARY KEY,
    value       jsonb       NOT NULL,
    updated_by  bigint REFERENCES users (id),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- =====================================================================================================
-- MODULE customer
-- =====================================================================================================
CREATE TABLE customers (
    id                       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id                uuid        NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    user_id                  bigint UNIQUE REFERENCES users (id),            -- nul : client créé par le back-office, sans accès
    kind                     text        NOT NULL CHECK (kind IN ('INDIVIDUAL', 'BUSINESS')),
    first_name               text,
    last_name                text,
    business_name            text,
    business_type            text,
    business_registered_on   date,
    birth_date               date,
    nationality              text,
    occupation               text,
    gender                   text CHECK (gender IN ('M', 'F', 'OTHER')),
    civility                 text,
    address_line             text,
    city                     text,
    country_code             char(2) REFERENCES countries (code),
    phone_e164               text,
    email                    text,
    kyc_status               text        NOT NULL DEFAULT 'NOT_SUBMITTED'
                             CHECK (kyc_status IN ('NOT_SUBMITTED', 'PENDING', 'VERIFIED', 'REJECTED', 'EXPIRED')),
    kyc_verified_at          timestamptz,
    status                   text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    legacy_id                bigint UNIQUE,
    legacy_attributes        jsonb,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    version                  bigint      NOT NULL DEFAULT 0,
    CONSTRAINT customers_identity CHECK (
        (kind = 'INDIVIDUAL' AND first_name IS NOT NULL) OR (kind = 'BUSINESS' AND business_name IS NOT NULL)
    ),
    CONSTRAINT customers_phone_e164 CHECK (phone_e164 IS NULL OR phone_e164 ~ '^\+[1-9][0-9]{6,14}$')
);
CREATE INDEX customers_country_idx ON customers (country_code);
CREATE INDEX customers_email_idx ON customers (lower(email));
CREATE TRIGGER customers_touch BEFORE UPDATE ON customers FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Personnes pour lesquelles un client envoie (SENDER) ou à qui il envoie (BENEFICIARY).
CREATE TABLE parties (
    id                       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id                uuid        NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    customer_id              bigint      NOT NULL REFERENCES customers (id),
    role                     text        NOT NULL CHECK (role IN ('SENDER', 'BENEFICIARY')),
    kind                     text        NOT NULL CHECK (kind IN ('INDIVIDUAL', 'BUSINESS')),
    first_name               text,
    last_name                text,
    business_name            text,
    business_type            text,
    business_registered_on   date,
    birth_date               date,
    nationality              text,
    occupation               text,
    gender                   text CHECK (gender IN ('M', 'F', 'OTHER')),
    civility                 text,
    address_line             text,
    city                     text,
    country_code             char(2) REFERENCES countries (code),
    phone_e164               text,
    email                    text,
    id_document_type         text,
    id_document_number_enc   bytea,                        -- chiffré par l'application
    id_document_last4        text,
    id_document_expires_on   date,
    archived_at              timestamptz,
    legacy_id                bigint,
    legacy_source            text CHECK (legacy_source IN ('customer', 'contactcustomer')),
    legacy_attributes        jsonb,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    version                  bigint      NOT NULL DEFAULT 0,
    CONSTRAINT parties_identity CHECK (
        (kind = 'INDIVIDUAL' AND first_name IS NOT NULL) OR (kind = 'BUSINESS' AND business_name IS NOT NULL)
    ),
    CONSTRAINT parties_phone_e164 CHECK (phone_e164 IS NULL OR phone_e164 ~ '^\+[1-9][0-9]{6,14}$'),
    CONSTRAINT parties_legacy_uk UNIQUE (legacy_source, legacy_id)
);
CREATE INDEX parties_customer_idx ON parties (customer_id, role) WHERE archived_at IS NULL;
CREATE TRIGGER parties_touch BEFORE UPDATE ON parties FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Où l'argent est versé : compte bancaire ou portefeuille mobile d'un bénéficiaire.
CREATE TABLE payout_destinations (
    id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id              uuid        NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    party_id               bigint      NOT NULL REFERENCES parties (id),
    kind                   text        NOT NULL CHECK (kind IN ('BANK_ACCOUNT', 'MOBILE_WALLET')),
    country_code           char(2)     NOT NULL REFERENCES countries (code),
    bank_id                bigint REFERENCES banks (id),
    mobile_operator_id     bigint REFERENCES mobile_operators (id),
    account_holder_name    text,
    account_number_enc     bytea       NOT NULL,                   -- numéro de compte ou MSISDN, chiffré par l'application
    account_number_masked  text        NOT NULL,
    swift_code             text,
    ifsc_code              text,
    routing_number         text,
    is_default             boolean     NOT NULL DEFAULT false,
    archived_at            timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                bigint      NOT NULL DEFAULT 0,
    CONSTRAINT payout_destinations_kind CHECK (
        (kind = 'BANK_ACCOUNT' AND bank_id IS NOT NULL AND mobile_operator_id IS NULL) OR
        (kind = 'MOBILE_WALLET' AND mobile_operator_id IS NOT NULL AND bank_id IS NULL)
    )
);
CREATE INDEX payout_destinations_party_idx ON payout_destinations (party_id) WHERE archived_at IS NULL;
CREATE UNIQUE INDEX payout_destinations_one_default_uk ON payout_destinations (party_id) WHERE is_default AND archived_at IS NULL;
CREATE TRIGGER payout_destinations_touch BEFORE UPDATE ON payout_destinations FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE kyc_documents (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id         uuid        NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    customer_id       bigint      NOT NULL REFERENCES customers (id),
    document_type     text        NOT NULL CHECK (document_type IN
                      ('PASSPORT', 'NATIONAL_ID', 'RESIDENCE_PERMIT', 'DRIVING_LICENSE', 'PROOF_OF_ADDRESS', 'SELFIE', 'BUSINESS_REGISTRATION')),
    side              text        NOT NULL DEFAULT 'SINGLE' CHECK (side IN ('FRONT', 'BACK', 'SINGLE')),
    storage_key       text        NOT NULL,                        -- objet privé (MinIO/S3), accès par lien signé
    content_type      text        NOT NULL,
    size_bytes        bigint      NOT NULL CHECK (size_bytes > 0),
    sha256            char(64)    NOT NULL,
    status            text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    expires_on        date,
    reviewed_by       bigint REFERENCES users (id),
    reviewed_at       timestamptz,
    rejection_reason  text,
    uploaded_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT kyc_documents_review CHECK (
        (status IN ('APPROVED', 'REJECTED') AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL) OR status IN ('PENDING', 'EXPIRED')
    ),
    CONSTRAINT kyc_documents_rejection CHECK (status <> 'REJECTED' OR rejection_reason IS NOT NULL)
);
CREATE INDEX kyc_documents_customer_idx ON kyc_documents (customer_id);

-- =====================================================================================================
-- MODULE wallet — grand livre à double entrée
-- =====================================================================================================
-- Un portefeuille par client et par devise (l'ancien système tenait aussi des soldes par pays). Un compte de grand livre
-- par portefeuille, plus des comptes de la plateforme (un jeu par devise, créés à l'activation de la devise).
-- Convention : un compte client est un PASSIF. Un crédit augmente le solde, un débit le diminue.
CREATE TABLE wallets (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id      uuid          NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    customer_id    bigint        NOT NULL REFERENCES customers (id),
    currency_code  char(3)       NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
    status         text          NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    balance        numeric(19,4) NOT NULL DEFAULT 0 CONSTRAINT wallets_non_negative CHECK (balance >= 0),
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    version        bigint        NOT NULL DEFAULT 0,
    UNIQUE (customer_id, currency_code)
);
CREATE TRIGGER wallets_touch BEFORE UPDATE ON wallets FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE ledger_accounts (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code           text     NOT NULL UNIQUE,
    kind           text     NOT NULL CHECK (kind IN ('CUSTOMER_WALLET', 'PLATFORM')),
    wallet_id      bigint UNIQUE REFERENCES wallets (id),
    currency_code  char(3)  NOT NULL,
    name           text     NOT NULL,
    CONSTRAINT ledger_accounts_wallet CHECK ((kind = 'CUSTOMER_WALLET') = (wallet_id IS NOT NULL))
);

CREATE TABLE ledger_transactions (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id       uuid        NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    kind            text        NOT NULL CHECK (kind IN
                    ('OPENING_BALANCE', 'TOP_UP', 'TRANSFER_DEBIT', 'TRANSFER_REFUND', 'ADJUSTMENT', 'CORRECTION')),
    reference       text        NOT NULL UNIQUE,      -- clé d'idempotence métier, ex. « transfer:42:debit »
    description     text,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    created_by      bigint REFERENCES users (id),
    related_type    text,
    related_id      bigint
);

CREATE TABLE ledger_entries (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_transaction_id bigint        NOT NULL REFERENCES ledger_transactions (id),
    ledger_account_id     bigint        NOT NULL REFERENCES ledger_accounts (id),
    direction             text          NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount                numeric(19,4) NOT NULL CHECK (amount > 0),
    currency_code         char(3)       NOT NULL,
    created_at            timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX ledger_entries_account_idx ON ledger_entries (ledger_account_id, created_at);
CREATE INDEX ledger_entries_tx_idx ON ledger_entries (ledger_transaction_id);

CREATE TRIGGER ledger_entries_immutable BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER ledger_transactions_immutable BEFORE UPDATE OR DELETE ON ledger_transactions FOR EACH ROW EXECUTE FUNCTION forbid_modification();

-- Équilibre : par transaction et par devise, total des débits = total des crédits (vérifié au COMMIT).
CREATE FUNCTION check_ledger_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE unbalanced record;
BEGIN
    SELECT currency_code,
           sum(CASE direction WHEN 'DEBIT' THEN amount ELSE 0 END)  AS debits,
           sum(CASE direction WHEN 'CREDIT' THEN amount ELSE 0 END) AS credits
      INTO unbalanced
      FROM ledger_entries
     WHERE ledger_transaction_id = NEW.ledger_transaction_id
     GROUP BY currency_code
    HAVING sum(CASE direction WHEN 'DEBIT' THEN amount ELSE 0 END) <> sum(CASE direction WHEN 'CREDIT' THEN amount ELSE 0 END)
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Écritures déséquilibrées pour la transaction % (devise %: débits %, crédits %)',
            NEW.ledger_transaction_id, unbalanced.currency_code, unbalanced.debits, unbalanced.credits
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER ledger_entries_balanced AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_ledger_balanced();

-- Contrôle d'intégrité : le solde matérialisé doit égaler la somme des écritures (doit toujours être vide).
CREATE VIEW wallet_balance_discrepancies AS
SELECT w.id AS wallet_id, w.balance AS materialized_balance,
       COALESCE(sum(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END), 0) AS ledger_balance
  FROM wallets w
  JOIN ledger_accounts a ON a.wallet_id = w.id
  LEFT JOIN ledger_entries e ON e.ledger_account_id = a.id
 GROUP BY w.id, w.balance
HAVING w.balance <> COALESCE(sum(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END), 0);

CREATE TABLE top_ups (
    id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id              uuid          NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    reference              text          NOT NULL UNIQUE,
    customer_id            bigint        NOT NULL REFERENCES customers (id),
    wallet_id              bigint        NOT NULL REFERENCES wallets (id),
    method                 text          NOT NULL CHECK (method IN ('WACE_MOBILE_MONEY', 'MANUAL_PROOF', 'LEGACY')),   -- LEGACY : historique migré (Stripe, PayDunya…)
    amount                 numeric(19,4) NOT NULL CHECK (amount > 0),
    currency_code          char(3)       NOT NULL,
    status                 text          NOT NULL DEFAULT 'PENDING'
                           CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'REJECTED')),
    mobile_operator_id     bigint REFERENCES mobile_operators (id),
    payer_msisdn_masked    text,
    provider               text CHECK (provider IN ('WACE')),
    provider_reference     text,
    proof_storage_key      text,
    reviewed_by            bigint REFERENCES users (id),
    reviewed_at            timestamptz,
    failure_reason         text,
    ledger_transaction_id  bigint UNIQUE REFERENCES ledger_transactions (id),     -- unique : crédité une seule fois
    idempotency_key        text,
    legacy_id              bigint UNIQUE,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    version                bigint        NOT NULL DEFAULT 0,
    CONSTRAINT top_ups_method CHECK (
        (method = 'WACE_MOBILE_MONEY' AND mobile_operator_id IS NOT NULL AND provider = 'WACE') OR
        (method = 'MANUAL_PROOF' AND proof_storage_key IS NOT NULL) OR
        (method = 'LEGACY' AND legacy_id IS NOT NULL)
    ),
    -- Une recharge terminée a toujours son écriture au grand livre, sauf l'historique migré (couvert par le solde d'ouverture).
    CONSTRAINT top_ups_completed CHECK (method = 'LEGACY' OR (status = 'COMPLETED') = (ledger_transaction_id IS NOT NULL)),
    CONSTRAINT top_ups_reviewed CHECK (method <> 'MANUAL_PROOF' OR status NOT IN ('COMPLETED', 'REJECTED') OR reviewed_by IS NOT NULL),
    UNIQUE (customer_id, idempotency_key),
    UNIQUE (provider, provider_reference)
);
CREATE INDEX top_ups_customer_idx ON top_ups (customer_id, created_at DESC);
CREATE INDEX top_ups_open_idx ON top_ups (status) WHERE status IN ('PENDING', 'PROCESSING');
CREATE TRIGGER top_ups_touch BEFORE UPDATE ON top_ups FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Ajustement manuel : double validation obligatoire (initiateur différent de l'approbateur).
CREATE TABLE balance_adjustments (
    id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id              uuid          NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    wallet_id              bigint        NOT NULL REFERENCES wallets (id),
    direction              text          NOT NULL CHECK (direction IN ('CREDIT', 'DEBIT')),
    amount                 numeric(19,4) NOT NULL CHECK (amount > 0),
    currency_code          char(3)       NOT NULL,
    reason                 text          NOT NULL CHECK (length(btrim(reason)) >= 5),
    status                 text          NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by           bigint        NOT NULL REFERENCES users (id),
    decided_by             bigint REFERENCES users (id),
    decided_at             timestamptz,
    decision_note          text,
    ledger_transaction_id  bigint UNIQUE REFERENCES ledger_transactions (id),
    created_at             timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT balance_adjustments_four_eyes CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CONSTRAINT balance_adjustments_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL) OR (status <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    ),
    CONSTRAINT balance_adjustments_ledger CHECK ((status = 'APPROVED') = (ledger_transaction_id IS NOT NULL))
);

-- =====================================================================================================
-- MODULE transfer
-- =====================================================================================================
CREATE TABLE transfers (
    id                          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id                   uuid          NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    reference                   text          NOT NULL UNIQUE,                  -- lisible : WTC-2026-000123
    customer_id                 bigint        NOT NULL REFERENCES customers (id),
    wallet_id                   bigint REFERENCES wallets (id),
    sender_party_id             bigint        NOT NULL REFERENCES parties (id),
    beneficiary_party_id        bigint        NOT NULL REFERENCES parties (id),
    payout_destination_id       bigint        NOT NULL REFERENCES payout_destinations (id),
    kind                        text          NOT NULL CHECK (kind IN ('BANK_TRANSFER', 'MOBILE_MONEY')),
    funding_source              text          NOT NULL DEFAULT 'WALLET' CHECK (funding_source IN ('WALLET', 'OFFLINE_RECEIPT')),
    party_type_pair             text          NOT NULL CHECK (party_type_pair IN ('P2P', 'P2B', 'B2P', 'B2B')),
    source_amount               numeric(19,4) NOT NULL CHECK (source_amount > 0),
    fee_amount                  numeric(19,4) NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
    total_debit                 numeric(19,4) GENERATED ALWAYS AS (source_amount + fee_amount) STORED,
    source_currency             char(3)       NOT NULL,
    destination_amount          numeric(19,4) CHECK (destination_amount IS NULL OR destination_amount > 0),
    destination_currency        char(3)       NOT NULL,
    fx_rate                     numeric(24,10) CHECK (fx_rate IS NULL OR fx_rate > 0),
    fee_schedule_id             bigint REFERENCES fee_schedules (id),
    purpose_code                text          NOT NULL,
    source_of_funds_code        text          NOT NULL,
    relationship_code           text          NOT NULL,
    comment                     text,
    status                      text          NOT NULL DEFAULT 'CREATED'
                                CHECK (status IN ('CREATED', 'PAYOUT_PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'REFUNDED', 'CANCELLED')),
    failure_reason              text,
    provider                    text          NOT NULL DEFAULT 'WACE' CHECK (provider IN ('WACE')),
    provider_reference          text,
    idempotency_key             text,
    snapshot                    jsonb         NOT NULL,                        -- identités, barème, taux figés au moment du transfert
    debit_ledger_transaction_id bigint UNIQUE REFERENCES ledger_transactions (id),
    refund_ledger_transaction_id bigint UNIQUE REFERENCES ledger_transactions (id),
    created_by                  bigint        NOT NULL REFERENCES users (id),
    completed_at                timestamptz,
    hidden_at                   timestamptz,                                   -- ancien isdelete : masqué des listes, jamais supprimé
    legacy_id                   bigint UNIQUE,
    legacy_number               text,
    legacy_status               text,
    legacy_attributes           jsonb,
    created_at                  timestamptz   NOT NULL DEFAULT now(),
    updated_at                  timestamptz   NOT NULL DEFAULT now(),
    version                     bigint        NOT NULL DEFAULT 0,
    CONSTRAINT transfers_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT transfers_refunded CHECK ((status = 'REFUNDED') = (refund_ledger_transaction_id IS NOT NULL)),
    CONSTRAINT transfers_failure CHECK (status NOT IN ('FAILED', 'REFUNDED') OR failure_reason IS NOT NULL),
    CONSTRAINT transfers_wallet_funding CHECK (funding_source <> 'WALLET' OR wallet_id IS NOT NULL),
    UNIQUE (customer_id, idempotency_key),
    UNIQUE (provider, provider_reference)
);
CREATE INDEX transfers_customer_idx ON transfers (customer_id, created_at DESC);
CREATE INDEX transfers_open_idx ON transfers (status, updated_at) WHERE status IN ('CREATED', 'PAYOUT_PENDING', 'PROCESSING');
CREATE INDEX transfers_sender_idx ON transfers (sender_party_id);
CREATE INDEX transfers_beneficiary_idx ON transfers (beneficiary_party_id);
CREATE TRIGGER transfers_touch BEFORE UPDATE ON transfers FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Historique des changements d'état : append-only.
CREATE TABLE transfer_status_history (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id  bigint      NOT NULL REFERENCES transfers (id),
    from_status  text,
    to_status    text        NOT NULL,
    reason       text,
    actor_type   text        NOT NULL CHECK (actor_type IN ('CUSTOMER', 'ADMIN', 'SYSTEM', 'PROVIDER')),
    actor_id     bigint REFERENCES users (id),
    metadata     jsonb,
    occurred_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX transfer_status_history_idx ON transfer_status_history (transfer_id, occurred_at);
CREATE TRIGGER transfer_status_history_immutable BEFORE UPDATE OR DELETE ON transfer_status_history FOR EACH ROW EXECUTE FUNCTION forbid_modification();

-- Chaque appel d'envoi à Wace. Un résultat AMBIGUOUS interdit toute nouvelle tentative : la réconciliation tranche.
CREATE TABLE payout_attempts (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id        bigint      NOT NULL REFERENCES transfers (id),
    attempt_no         integer     NOT NULL CHECK (attempt_no > 0),
    request_payload    jsonb       NOT NULL,                -- données sensibles masquées
    response_payload   jsonb,
    http_status        integer,
    provider_status    text,
    provider_reference text,
    outcome            text        NOT NULL CHECK (outcome IN ('ACCEPTED', 'REJECTED', 'AMBIGUOUS', 'ERROR')),
    started_at         timestamptz NOT NULL DEFAULT now(),
    finished_at        timestamptz,
    UNIQUE (transfer_id, attempt_no)
);

-- =====================================================================================================
-- MODULE payment (infrastructure Wace) et fiabilité
-- =====================================================================================================
CREATE TABLE outbox_events (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type  text        NOT NULL,
    aggregate_id    bigint      NOT NULL,
    event_type      text        NOT NULL,
    payload         jsonb       NOT NULL,
    status          text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED')),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    processed_at    timestamptz
);
CREATE INDEX outbox_events_due_idx ON outbox_events (next_attempt_at) WHERE status IN ('PENDING', 'PROCESSING');

CREATE TABLE webhook_events (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider         text        NOT NULL CHECK (provider IN ('WACE')),
    event_id         text        NOT NULL,
    event_type       text,
    signature_valid  boolean     NOT NULL,
    payload          jsonb       NOT NULL,
    status           text        NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED')),
    received_at      timestamptz NOT NULL DEFAULT now(),
    processed_at     timestamptz,
    UNIQUE (provider, event_id)                          -- dédoublonnage des rejeux
);

CREATE TABLE reconciliation_runs (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider        text        NOT NULL CHECK (provider IN ('WACE')),
    business_date   date        NOT NULL,
    status          text        NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    started_at      timestamptz NOT NULL DEFAULT now(),
    finished_at     timestamptz,
    matched_count   integer     NOT NULL DEFAULT 0,
    mismatch_count  integer     NOT NULL DEFAULT 0,
    triggered_by    bigint REFERENCES users (id),
    UNIQUE (provider, business_date, started_at)
);

CREATE TABLE reconciliation_items (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id             bigint      NOT NULL REFERENCES reconciliation_runs (id),
    kind               text        NOT NULL CHECK (kind IN ('MISSING_LOCAL', 'MISSING_PROVIDER', 'STATUS_MISMATCH', 'AMOUNT_MISMATCH')),
    transfer_id        bigint REFERENCES transfers (id),
    top_up_id          bigint REFERENCES top_ups (id),
    provider_reference text,
    details            jsonb,
    resolved_at        timestamptz,
    resolved_by        bigint REFERENCES users (id),
    CONSTRAINT reconciliation_items_target CHECK (transfer_id IS NOT NULL OR top_up_id IS NOT NULL OR provider_reference IS NOT NULL)
);
CREATE INDEX reconciliation_items_open_idx ON reconciliation_items (run_id) WHERE resolved_at IS NULL;

-- Verrous des tâches planifiées (ShedLock).
CREATE TABLE shedlock (
    name        varchar(64)  PRIMARY KEY,
    lock_until  timestamptz  NOT NULL,
    locked_at   timestamptz  NOT NULL,
    locked_by   varchar(255) NOT NULL
);

-- =====================================================================================================
-- MODULE notification / backoffice
-- =====================================================================================================
CREATE TABLE notification_outbox (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel          text        NOT NULL CHECK (channel IN ('EMAIL', 'WHATSAPP', 'TELEGRAM')),
    recipient        text        NOT NULL,
    template         text        NOT NULL,
    payload          jsonb       NOT NULL,
    status           text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    attempts         integer     NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    sent_at          timestamptz,
    last_error       text,
    dedupe_key       text UNIQUE,                         -- un même événement ne notifie qu'une fois
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notification_outbox_due_idx ON notification_outbox (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE audit_log (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    actor_user_id  bigint REFERENCES users (id) ON DELETE SET NULL,
    actor_type     text        NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM', 'PROVIDER')),
    action         text        NOT NULL,
    entity_type    text        NOT NULL,
    entity_id      text        NOT NULL,
    before_state   jsonb,
    after_state    jsonb,
    ip_address     inet,
    request_id     text
);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX audit_log_actor_idx ON audit_log (actor_user_id, occurred_at DESC);
CREATE TRIGGER audit_log_immutable BEFORE UPDATE OR DELETE ON audit_log FOR EACH ROW EXECUTE FUNCTION forbid_modification();

-- Jeton à usage unique pré-remplissant un transfert (canal WhatsApp) ; seul le hachage est stocké.
CREATE TABLE web_tokens (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_hash    text          NOT NULL UNIQUE,
    amount        numeric(19,4) NOT NULL CHECK (amount > 0),
    country_code  char(2)       NOT NULL REFERENCES countries (code),
    whatsapp_id   text,
    status        text          NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'USED', 'EXPIRED')),
    expires_at    timestamptz   NOT NULL,
    used_at       timestamptz,
    created_by    bigint REFERENCES users (id),
    created_at    timestamptz   NOT NULL DEFAULT now(),
    legacy_id     bigint UNIQUE,
    CONSTRAINT web_tokens_used CHECK ((status = 'USED') = (used_at IS NOT NULL))
);

-- =====================================================================================================
-- Données de référence initiales
-- =====================================================================================================
INSERT INTO roles (code, description) VALUES
    ('CUSTOMER',    'Client final'),
    ('SUPPORT',     'Support : consultation et KYC'),
    ('ADMIN',       'Administration des opérations'),
    ('SUPER_ADMIN', 'Administration complète, gestion des utilisateurs');

INSERT INTO permissions (code, description) VALUES
    ('CUSTOMER_READ',          'Consulter les clients et leurs données'),
    ('CUSTOMER_WRITE',         'Modifier ou suspendre un client'),
    ('KYC_REVIEW',             'Valider ou rejeter un document KYC'),
    ('TRANSFER_READ',          'Consulter tous les transferts'),
    ('TRANSFER_CREATE',        'Saisir un transfert pour un client'),
    ('TRANSFER_REFUND',        'Rembourser ou resynchroniser un transfert'),
    ('TOPUP_REVIEW',           'Valider ou rejeter une recharge par preuve'),
    ('BALANCE_ADJUST_REQUEST', 'Demander un ajustement de solde'),
    ('BALANCE_ADJUST_APPROVE', 'Approuver un ajustement de solde'),
    ('REFERENCE_WRITE',        'Modifier barèmes, taux et référentiels'),
    ('USER_MANAGE',            'Gérer les comptes du personnel et leurs rôles'),
    ('AUDIT_READ',             'Consulter les journaux d''audit'),
    ('RECONCILIATION_RUN',     'Lancer et résoudre les réconciliations');

INSERT INTO role_permissions (role_code, permission_code)
SELECT 'SUPER_ADMIN', code FROM permissions;
INSERT INTO role_permissions (role_code, permission_code)
SELECT 'ADMIN', code FROM permissions WHERE code <> 'USER_MANAGE';
INSERT INTO role_permissions (role_code, permission_code) VALUES
    ('SUPPORT', 'CUSTOMER_READ'), ('SUPPORT', 'KYC_REVIEW'), ('SUPPORT', 'TRANSFER_READ');

-- Comptes de la plateforme (devise XAF par défaut ; d'autres devises s'ajoutent par migration).
INSERT INTO ledger_accounts (code, kind, currency_code, name) VALUES
    ('PLATFORM_OPENING_BALANCE', 'PLATFORM', 'XAF', 'Contrepartie des soldes d''ouverture (migration)'),
    ('PLATFORM_TOPUP_CLEARING',  'PLATFORM', 'XAF', 'Fonds reçus par recharge, en attente de règlement'),
    ('PLATFORM_OFFLINE_RECEIPTS','PLATFORM', 'XAF', 'Fonds reçus hors plateforme (espèces, virement) lors d''une saisie admin'),
    ('PLATFORM_PAYOUT_CLEARING', 'PLATFORM', 'XAF', 'Fonds envoyés à Wace, en attente de règlement'),
    ('PLATFORM_FEE_REVENUE',     'PLATFORM', 'XAF', 'Produit des frais de transfert'),
    ('PLATFORM_ADJUSTMENTS',     'PLATFORM', 'XAF', 'Contrepartie des ajustements manuels');

INSERT INTO platform_settings (key, value) VALUES
    ('transfer.enforce_fee_schedule', 'true'::jsonb),
    ('transfer.max_amount_per_transfer', '5000000'::jsonb),
    ('wallet.default_currency', '"XAF"'::jsonb);
