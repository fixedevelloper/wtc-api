-- Phase 6 (transferts et envoi Wace), d'après la spécification officielle Wace v2.0.0.

-- Numérotation lisible des transferts : WTC-2026-000123 (la séquence ne revient jamais en arrière).
CREATE SEQUENCE transfer_reference_seq START 1;

-- Wace exige l'indicateur « personne politiquement exposée » (pep) pour l'expéditeur.
ALTER TABLE parties ADD COLUMN is_pep boolean NOT NULL DEFAULT false;

-- Suivi de l'ordre chez Wace.
ALTER TABLE transfers ADD COLUMN provider_status text;                 -- dernier statut Wace (PENDING, WAITING CONFIRMATION, PROCESSING, PAID, CANCEL, LOCKED)
ALTER TABLE transfers ADD COLUMN provider_confirmed_at timestamptz;    -- l'ordre a été confirmé (étape obligatoire après la création)
ALTER TABLE transfers ADD COLUMN provider_fee numeric(19,4);           -- frais facturés par Wace (coût de la plateforme)
ALTER TABLE transfers ADD COLUMN provider_amount_received numeric(19,4); -- montant que Wace dit livrer au bénéficiaire
ALTER TABLE transfers ADD COLUMN provider_polled_at timestamptz;       -- dernière interrogation du statut
ALTER TABLE transfers ADD COLUMN request_fingerprint text;             -- empreinte du corps de la demande (Idempotency-Key)
-- Blocage nécessitant une décision humaine : jamais de remboursement ni de renvoi automatique dans ces cas.
ALTER TABLE transfers ADD COLUMN payout_hold text
    CHECK (payout_hold IN ('AMBIGUOUS_CREATE', 'AMOUNT_MISMATCH', 'PROVIDER_LOCKED', 'CONFIRM_FAILED'));
CREATE INDEX transfers_polling_idx ON transfers (provider_polled_at NULLS FIRST) WHERE status = 'PROCESSING' AND provider_reference IS NOT NULL;
CREATE INDEX transfers_held_idx ON transfers (payout_hold) WHERE payout_hold IS NOT NULL;

-- Codes d'un expéditeur ou d'un bénéficiaire chez Wace. Le code d'un bénéficiaire dépend de l'expéditeur
-- (Wace l'exige à la création) : un même bénéficiaire a donc un code par expéditeur.
CREATE TABLE provider_party_refs (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    party_id          bigint      NOT NULL REFERENCES parties (id),
    provider          text        NOT NULL CHECK (provider IN ('WACE')),
    sender_party_id   bigint REFERENCES parties (id),
    external_code     text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (party_id, provider, sender_party_id)
);

-- Code « payeur » Wace pour les envois bancaires d'un pays (à renseigner avec Wace : voir wace-integration.md).
ALTER TABLE countries ADD COLUMN wace_bank_payer_code text;
