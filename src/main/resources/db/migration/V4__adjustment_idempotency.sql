-- Phase 5 (portefeuilles). Un ajustement de solde demandé deux fois (réseau instable, double clic) ne doit créer
-- qu'une demande : l'en-tête Idempotency-Key du client est conservé avec l'empreinte du corps pour détecter un rejeu
-- avec un contenu différent.
ALTER TABLE balance_adjustments ADD COLUMN idempotency_key text;
ALTER TABLE balance_adjustments ADD COLUMN request_fingerprint text;
CREATE UNIQUE INDEX balance_adjustments_idempotency_uk ON balance_adjustments (requested_by, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
