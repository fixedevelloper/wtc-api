-- Tests des invariants du schéma V1 : chaque garde-fou financier doit effectivement bloquer l'écriture interdite.
-- Usage : voir tools/check_schema.sh (charge V1__baseline.sql dans un PostgreSQL jetable, puis exécute ce fichier).
-- Le script s'arrête à la première défaillance (ON_ERROR_STOP) et ne laisse aucune donnée (tout est annulé).

BEGIN;

CREATE FUNCTION pg_temp.expect_error(label text, stmt text, expected_state text DEFAULT NULL) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;               -- force le contrôle des déclencheurs différés
    EXCEPTION WHEN OTHERS THEN
        IF expected_state IS NOT NULL AND SQLSTATE <> expected_state THEN
            RAISE EXCEPTION '[ÉCHEC] % : erreur % au lieu de %', label, SQLSTATE, expected_state;
        END IF;
        RAISE NOTICE '[OK] % -> refusé (%)', label, SQLSTATE;
        RETURN;
    END;
    RAISE EXCEPTION '[ÉCHEC] % : l''écriture interdite a été acceptée', label;
END $$;

CREATE FUNCTION pg_temp.expect_ok(label text, stmt text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE stmt;
    RAISE NOTICE '[OK] % -> accepté', label;
END $$;

-- Jeux de données minimaux ---------------------------------------------------------------------------
INSERT INTO pricing_zones (code, name) VALUES ('Z1', 'Zone 1');
INSERT INTO countries (code, iso3, name, currency_code, pricing_zone_id, can_send, can_receive)
VALUES ('CG', 'COG', 'Congo', 'XAF', 1, true, true), ('FR', 'FRA', 'France', 'EUR', 1, true, true);
INSERT INTO users (email, password_hash, display_name) VALUES ('alice@example.com', 'x', 'Alice'), ('admin1@example.com', 'x', 'Admin 1'), ('admin2@example.com', 'x', 'Admin 2');
INSERT INTO customers (user_id, kind, first_name, last_name, country_code) VALUES (1, 'INDIVIDUAL', 'Alice', 'Dupont', 'CG');
INSERT INTO wallets (customer_id, currency_code) VALUES (1, 'XAF');
INSERT INTO ledger_accounts (code, kind, wallet_id, currency_code, name) VALUES ('WALLET_1', 'CUSTOMER_WALLET', 1, 'XAF', 'Portefeuille Alice');

-- 1. Grand livre ---------------------------------------------------------------------------------------
SELECT pg_temp.expect_ok('écriture équilibrée', $q$
    WITH t AS (INSERT INTO ledger_transactions (kind, reference) VALUES ('TOP_UP', 'test:balanced') RETURNING id)
    INSERT INTO ledger_entries (ledger_transaction_id, ledger_account_id, direction, amount, currency_code)
    SELECT t.id, a.id, d.direction, 100, 'XAF' FROM t
      JOIN LATERAL (VALUES ('PLATFORM_TOPUP_CLEARING', 'DEBIT'), ('WALLET_1', 'CREDIT')) d(code, direction) ON true
      JOIN ledger_accounts a ON a.code = d.code
$q$);
SELECT pg_temp.expect_error('écriture déséquilibrée', $q$
    WITH t AS (INSERT INTO ledger_transactions (kind, reference) VALUES ('TOP_UP', 'test:unbalanced') RETURNING id)
    INSERT INTO ledger_entries (ledger_transaction_id, ledger_account_id, direction, amount, currency_code)
    SELECT t.id, a.id, 'CREDIT', 100, 'XAF' FROM t JOIN ledger_accounts a ON a.code = 'WALLET_1'
$q$, '23000');
SELECT pg_temp.expect_error('montant d''écriture nul', $q$
    INSERT INTO ledger_entries (ledger_transaction_id, ledger_account_id, direction, amount, currency_code) VALUES (1, 1, 'CREDIT', 0, 'XAF')
$q$, '23514');
SELECT pg_temp.expect_error('modification d''une écriture', $q$ UPDATE ledger_entries SET amount = 1 $q$, '23000');
SELECT pg_temp.expect_error('suppression d''une écriture', $q$ DELETE FROM ledger_entries $q$, '23000');
SELECT pg_temp.expect_error('modification d''une transaction du grand livre', $q$ UPDATE ledger_transactions SET description = 'x' $q$, '23000');
SELECT pg_temp.expect_error('référence de transaction en double (idempotence)', $q$ INSERT INTO ledger_transactions (kind, reference) VALUES ('TOP_UP', 'test:balanced') $q$, '23505');

-- 2. Portefeuilles -------------------------------------------------------------------------------------
SELECT pg_temp.expect_error('solde négatif', $q$ UPDATE wallets SET balance = -1 WHERE id = 1 $q$, '23514');
SELECT pg_temp.expect_error('deux portefeuilles dans la même devise', $q$ INSERT INTO wallets (customer_id, currency_code) VALUES (1, 'XAF') $q$, '23505');
SELECT pg_temp.expect_error('compte client sans portefeuille', $q$
    INSERT INTO ledger_accounts (code, kind, currency_code, name) VALUES ('BAD', 'CUSTOMER_WALLET', 'XAF', 'x')
$q$, '23514');
UPDATE wallets SET balance = 100 WHERE id = 1;
SELECT pg_temp.expect_ok('solde matérialisé = somme des écritures', $q$
    DO $d$ BEGIN
        IF EXISTS (SELECT 1 FROM wallet_balance_discrepancies) THEN RAISE EXCEPTION 'écart inattendu'; END IF;
    END $d$
$q$);
UPDATE wallets SET balance = 150 WHERE id = 1;
SELECT pg_temp.expect_ok('la vue détecte un solde qui diverge du grand livre', $q$
    DO $d$ BEGIN
        IF NOT EXISTS (SELECT 1 FROM wallet_balance_discrepancies WHERE wallet_id = 1 AND ledger_balance = 100) THEN RAISE EXCEPTION 'écart non détecté'; END IF;
    END $d$
$q$);
UPDATE wallets SET balance = 100 WHERE id = 1;

-- 3. Ajustements : double validation -------------------------------------------------------------------
INSERT INTO balance_adjustments (wallet_id, direction, amount, currency_code, reason, requested_by) VALUES (1, 'CREDIT', 10, 'XAF', 'Geste commercial', 2);
SELECT pg_temp.expect_error('même personne initie et approuve', $q$
    UPDATE balance_adjustments SET status = 'REJECTED', decided_by = 2, decided_at = now() WHERE id = 1
$q$, '23514');
SELECT pg_temp.expect_ok('approbation par une autre personne (rejet)', $q$
    UPDATE balance_adjustments SET status = 'REJECTED', decided_by = 3, decided_at = now() WHERE id = 1
$q$);
SELECT pg_temp.expect_error('ajustement approuvé sans écriture au grand livre', $q$
    INSERT INTO balance_adjustments (wallet_id, direction, amount, currency_code, reason, requested_by, status, decided_by, decided_at)
    VALUES (1, 'CREDIT', 10, 'XAF', 'Sans écriture', 2, 'APPROVED', 3, now())
$q$, '23514');
SELECT pg_temp.expect_error('motif trop court', $q$
    INSERT INTO balance_adjustments (wallet_id, direction, amount, currency_code, reason, requested_by) VALUES (1, 'DEBIT', 5, 'XAF', 'ok', 2)
$q$, '23514');

-- 4. Barème de frais : pas de chevauchement ------------------------------------------------------------
INSERT INTO fee_schedules (pricing_zone_id, currency_code, min_amount, max_amount, percent, effective_from) VALUES (1, 'XAF', 0, 100000, 5, '2026-01-01');
SELECT pg_temp.expect_error('tranches qui se chevauchent', $q$
    INSERT INTO fee_schedules (pricing_zone_id, currency_code, min_amount, max_amount, percent, effective_from) VALUES (1, 'XAF', 50000, 200000, 4, '2026-01-01')
$q$, '23P01');
SELECT pg_temp.expect_ok('tranche contiguë (borne haute exclue du suivant)', $q$
    INSERT INTO fee_schedules (pricing_zone_id, currency_code, min_amount, max_amount, percent, effective_from) VALUES (1, 'XAF', 100000, 200000, 4, '2026-01-01')
$q$);
SELECT pg_temp.expect_ok('même tranche sur une autre période', $q$
    UPDATE fee_schedules SET effective_to = '2026-06-30' WHERE id = 1;
    INSERT INTO fee_schedules (pricing_zone_id, currency_code, min_amount, max_amount, percent, effective_from) VALUES (1, 'XAF', 0, 100000, 6, '2026-06-30')
$q$);
SELECT pg_temp.expect_error('pourcentage supérieur à 100', $q$
    INSERT INTO fee_schedules (pricing_zone_id, currency_code, min_amount, max_amount, percent, effective_from) VALUES (1, 'EUR', 0, 10, 101, '2026-01-01')
$q$, '23514');

-- 5. Identité ------------------------------------------------------------------------------------------
SELECT pg_temp.expect_error('e-mail en double à la casse près', $q$ INSERT INTO users (email, password_hash, display_name) VALUES ('ALICE@example.com', 'x', 'Bis') $q$, '23505');
SELECT pg_temp.expect_error('téléphone non E.164', $q$ UPDATE users SET phone_e164 = '0612345678' WHERE id = 1 $q$, '23514');
SELECT pg_temp.expect_error('2FA activée sans secret', $q$ UPDATE users SET mfa_enabled = true WHERE id = 1 $q$, '23514');
SELECT pg_temp.expect_error('client entreprise sans raison sociale', $q$ INSERT INTO customers (kind, first_name, country_code) VALUES ('BUSINESS', 'x', 'CG') $q$, '23514');

-- 6. Transferts ----------------------------------------------------------------------------------------
INSERT INTO customers (kind, first_name, last_name, country_code) VALUES ('INDIVIDUAL', 'Ben', 'Eficiary', 'FR');
INSERT INTO parties (customer_id, role, kind, first_name, last_name, country_code) VALUES (1, 'SENDER', 'INDIVIDUAL', 'Alice', 'Dupont', 'CG'), (1, 'BENEFICIARY', 'INDIVIDUAL', 'Ben', 'Eficiary', 'FR');
INSERT INTO banks (country_code, name, wace_bank_id) VALUES ('FR', 'BNP', 'B1');
INSERT INTO mobile_operators (country_code, name, wace_payer_code) VALUES ('CG', 'MTN', 'M1');
SELECT pg_temp.expect_error('destination bancaire sans banque', $q$
    INSERT INTO payout_destinations (party_id, kind, country_code, account_number_enc, account_number_masked) VALUES (2, 'BANK_ACCOUNT', 'FR', '\x00', '****')
$q$, '23514');
SELECT pg_temp.expect_error('destination mobile avec banque', $q$
    INSERT INTO payout_destinations (party_id, kind, country_code, bank_id, mobile_operator_id, account_number_enc, account_number_masked) VALUES (2, 'MOBILE_WALLET', 'FR', 1, 1, '\x00', '****')
$q$, '23514');
INSERT INTO payout_destinations (party_id, kind, country_code, bank_id, account_number_enc, account_number_masked, is_default) VALUES (2, 'BANK_ACCOUNT', 'FR', 1, '\x01', '****1', true);
SELECT pg_temp.expect_error('deux destinations par défaut', $q$
    INSERT INTO payout_destinations (party_id, kind, country_code, bank_id, account_number_enc, account_number_masked, is_default) VALUES (2, 'BANK_ACCOUNT', 'FR', 1, '\x02', '****2', true)
$q$, '23505');

CREATE FUNCTION pg_temp.new_transfer(p_reference text, p_key text, p_status text DEFAULT 'CREATED') RETURNS text LANGUAGE sql AS $$
    SELECT format($f$INSERT INTO transfers (reference, customer_id, wallet_id, sender_party_id, beneficiary_party_id, payout_destination_id, kind, party_type_pair,
        source_amount, fee_amount, source_currency, destination_currency, purpose_code, source_of_funds_code, relationship_code, status, idempotency_key, snapshot, created_by)
        VALUES (%L, 1, 1, 1, 2, (SELECT id FROM payout_destinations WHERE party_id = 2 AND archived_at IS NULL AND is_default), 'BANK_TRANSFER', 'P2P', 1000, 50, 'XAF', 'EUR', 'FAMILY', 'SALARY', 'SIBLING', %L, %L, '{}', 1)$f$, p_reference, p_status, p_key)
$$;
SELECT pg_temp.expect_ok('transfert créé', pg_temp.new_transfer('WTC-2026-000001', 'key-1'));
SELECT pg_temp.expect_ok('total débité calculé (montant + frais)', $q$
    DO $d$ BEGIN
        IF (SELECT total_debit FROM transfers WHERE reference = 'WTC-2026-000001') <> 1050 THEN RAISE EXCEPTION 'total_debit incorrect'; END IF;
    END $d$
$q$);
SELECT pg_temp.expect_error('même clé d''idempotence pour le même client', pg_temp.new_transfer('WTC-2026-000002', 'key-1'), '23505');
SELECT pg_temp.expect_error('référence en double', pg_temp.new_transfer('WTC-2026-000001', 'key-2'), '23505');
SELECT pg_temp.expect_error('statut inconnu', pg_temp.new_transfer('WTC-2026-000003', 'key-3', 'DONE'), '23514');
SELECT pg_temp.expect_error('transfert échoué sans motif', pg_temp.new_transfer('WTC-2026-000004', 'key-4', 'FAILED'), '23514');
SELECT pg_temp.expect_error('transfert terminé sans date de fin', pg_temp.new_transfer('WTC-2026-000005', 'key-5', 'COMPLETED'), '23514');
SELECT pg_temp.expect_error('transfert remboursé sans écriture de remboursement', $q$
    UPDATE transfers SET status = 'REFUNDED', failure_reason = 'x' WHERE reference = 'WTC-2026-000001'
$q$, '23514');
SELECT pg_temp.expect_error('montant source nul', $q$ UPDATE transfers SET source_amount = 0 WHERE id = 1 $q$, '23514');

INSERT INTO transfer_status_history (transfer_id, from_status, to_status, actor_type) VALUES (1, NULL, 'CREATED', 'SYSTEM');
SELECT pg_temp.expect_error('modification de l''historique d''état', $q$ UPDATE transfer_status_history SET to_status = 'COMPLETED' $q$, '23000');
SELECT pg_temp.expect_error('numéro de tentative d''envoi en double', $q$
    INSERT INTO payout_attempts (transfer_id, attempt_no, request_payload, outcome) VALUES (1, 1, '{}', 'AMBIGUOUS'), (1, 1, '{}', 'ERROR')
$q$, '23505');

-- 7. Recharges -----------------------------------------------------------------------------------------
SELECT pg_temp.expect_error('recharge terminée sans écriture au grand livre', $q$
    INSERT INTO top_ups (reference, customer_id, wallet_id, method, amount, currency_code, status, mobile_operator_id, provider)
    VALUES ('TU-1', 1, 1, 'WACE_MOBILE_MONEY', 100, 'XAF', 'COMPLETED', 1, 'WACE')
$q$, '23514');
SELECT pg_temp.expect_error('recharge manuelle sans preuve', $q$
    INSERT INTO top_ups (reference, customer_id, wallet_id, method, amount, currency_code) VALUES ('TU-2', 1, 1, 'MANUAL_PROOF', 100, 'XAF')
$q$, '23514');
SELECT pg_temp.expect_ok('recharge Wace créée', $q$
    INSERT INTO top_ups (reference, customer_id, wallet_id, method, amount, currency_code, mobile_operator_id, provider, provider_reference)
    VALUES ('TU-3', 1, 1, 'WACE_MOBILE_MONEY', 100, 'XAF', 1, 'WACE', 'W-123')
$q$);
SELECT pg_temp.expect_error('référence partenaire déjà utilisée (une recharge par paiement Wace)', $q$
    INSERT INTO top_ups (reference, customer_id, wallet_id, method, amount, currency_code, mobile_operator_id, provider, provider_reference)
    VALUES ('TU-4', 1, 1, 'WACE_MOBILE_MONEY', 100, 'XAF', 1, 'WACE', 'W-123')
$q$, '23505');

SELECT pg_temp.expect_ok('recharge historique migrée terminée sans écriture au grand livre', $q$
    INSERT INTO top_ups (reference, customer_id, wallet_id, method, amount, currency_code, status, legacy_id)
    VALUES ('TU-LEG', 1, 1, 'LEGACY', 100, 'XAF', 'COMPLETED', 99)
$q$);
SELECT pg_temp.expect_error('recharge LEGACY sans identifiant historique', $q$
    INSERT INTO top_ups (reference, customer_id, wallet_id, method, amount, currency_code) VALUES ('TU-LEG2', 1, 1, 'LEGACY', 100, 'XAF')
$q$, '23514');
SELECT pg_temp.expect_ok('transfert masqué (ancien isdelete)', $q$ UPDATE transfers SET hidden_at = now() WHERE reference = 'WTC-2026-000001' $q$);

-- 8. Fiabilité et audit --------------------------------------------------------------------------------
INSERT INTO webhook_events (provider, event_id, signature_valid, payload) VALUES ('WACE', 'evt-1', true, '{}');
SELECT pg_temp.expect_error('rejeu d''un webhook (même événement)', $q$ INSERT INTO webhook_events (provider, event_id, signature_valid, payload) VALUES ('WACE', 'evt-1', true, '{}') $q$, '23505');
SELECT pg_temp.expect_error('fournisseur autre que Wace', $q$ INSERT INTO webhook_events (provider, event_id, signature_valid, payload) VALUES ('STRIPE', 'evt-2', true, '{}') $q$, '23514');
INSERT INTO audit_log (actor_type, action, entity_type, entity_id) VALUES ('SYSTEM', 'TEST', 'wallet', '1');
SELECT pg_temp.expect_error('suppression du journal d''audit', $q$ DELETE FROM audit_log $q$, '23000');
SELECT pg_temp.expect_error('notification en double (même clé)', $q$
    INSERT INTO notification_outbox (channel, recipient, template, payload, dedupe_key) VALUES ('EMAIL', 'a@b.c', 't', '{}', 'k'), ('EMAIL', 'a@b.c', 't', '{}', 'k')
$q$, '23505');
SELECT pg_temp.expect_error('KYC approuvé sans relecteur', $q$
    INSERT INTO kyc_documents (customer_id, document_type, storage_key, content_type, size_bytes, sha256, status)
    VALUES (1, 'PASSPORT', 'k', 'image/png', 1, repeat('a', 64), 'APPROVED')
$q$, '23514');
SELECT pg_temp.expect_error('KYC rejeté sans motif', $q$
    INSERT INTO kyc_documents (customer_id, document_type, storage_key, content_type, size_bytes, sha256, status, reviewed_by, reviewed_at)
    VALUES (1, 'PASSPORT', 'k', 'image/png', 1, repeat('a', 64), 'REJECTED', 2, now())
$q$, '23514');

-- 8 bis. Référentiels dépendants du type de transfert -----------------------------------------------------
INSERT INTO reference_values (category, party_type_pair, code, label, wace_code) VALUES ('TRANSFER_PURPOSE', 'P2P', 'GIFT', 'Cadeau', 'Gift');
SELECT pg_temp.expect_ok('même code pour un autre type de transfert', $q$
    INSERT INTO reference_values (category, party_type_pair, code, label, wace_code) VALUES ('TRANSFER_PURPOSE', 'B2B', 'GIFT', 'Cadeau', 'Gift')
$q$);
SELECT pg_temp.expect_error('même code deux fois pour le même type de transfert', $q$
    INSERT INTO reference_values (category, party_type_pair, code, label, wace_code) VALUES ('TRANSFER_PURPOSE', 'P2P', 'GIFT', 'Cadeau bis', 'Gift')
$q$, '23505');
SELECT pg_temp.expect_error('type de transfert inconnu', $q$
    INSERT INTO reference_values (category, party_type_pair, code, label, wace_code) VALUES ('TRANSFER_PURPOSE', 'X2X', 'GIFT2', 'x', 'x')
$q$, '23514');

-- 9. Données de référence initiales --------------------------------------------------------------------
SELECT pg_temp.expect_ok('SUPER_ADMIN possède toutes les permissions, ADMIN toutes sauf USER_MANAGE', $q$
    DO $d$ BEGIN
        IF (SELECT count(*) FROM role_permissions WHERE role_code = 'SUPER_ADMIN') <> (SELECT count(*) FROM permissions) THEN RAISE EXCEPTION 'SUPER_ADMIN incomplet'; END IF;
        IF EXISTS (SELECT 1 FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'USER_MANAGE') THEN RAISE EXCEPTION 'ADMIN ne doit pas gérer les utilisateurs'; END IF;
    END $d$
$q$);

ROLLBACK;
