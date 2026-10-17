-- Phase 2 (identité).
-- 1. Anti-rejeu TOTP : dernier pas de temps (RFC 6238) accepté pour un utilisateur ; un code ne sert qu'une fois.
ALTER TABLE users ADD COLUMN mfa_last_used_step bigint;

-- 2. Libellé de l'auteur conservé dans le journal d'audit (l'e-mail peut changer, le compte disparaître).
ALTER TABLE audit_log ADD COLUMN actor_label text;

-- 3. Nombre d'essais sur un défi de second facteur : limite persistante, valable avec plusieurs instances.
ALTER TABLE one_time_tokens ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0);
CREATE INDEX one_time_tokens_user_purpose_idx ON one_time_tokens (user_id, purpose) WHERE used_at IS NULL;
