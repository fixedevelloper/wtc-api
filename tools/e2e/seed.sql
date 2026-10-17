-- Référentiels de l'essai de bout en bout (pays, zones, barème, taux, banques, opérateurs, libellés). Rejouable.
INSERT INTO pricing_zones (code, name) VALUES ('E2E-AC', 'Afrique centrale (essai)'), ('E2E-EU', 'Europe (essai)') ON CONFLICT (code) DO NOTHING;

INSERT INTO countries (code, iso3, name, currency_code, calling_code, can_send, can_receive, enabled, wace_bank_payer_code, pricing_zone_id) VALUES
  ('CM', 'CMR', 'Cameroun',                   'XAF', '+237', true,  true,  true, '1695743219', (SELECT id FROM pricing_zones WHERE code = 'E2E-AC')),
  ('CG', 'COG', 'République du Congo',        'XAF', '+242', false, true,  true, '1684531676', (SELECT id FROM pricing_zones WHERE code = 'E2E-AC')),
  ('FR', 'FRA', 'France',                     'EUR', '+33',  true,  true,  true, '1692214984', (SELECT id FROM pricing_zones WHERE code = 'E2E-EU'))
ON CONFLICT (code) DO UPDATE SET can_send = EXCLUDED.can_send, can_receive = EXCLUDED.can_receive, enabled = true,
  wace_bank_payer_code = EXCLUDED.wace_bank_payer_code, pricing_zone_id = EXCLUDED.pricing_zone_id;

-- Barème (frais en % du montant envoyé, devise d'envoi XAF) et taux XAF -> EUR
INSERT INTO fee_schedules (pricing_zone_id, currency_code, min_amount, max_amount, percent, fixed_fee, effective_from)
SELECT z.id, 'XAF', 0, 100000000, 5, 0, current_date - 1 FROM pricing_zones z WHERE z.code IN ('E2E-AC', 'E2E-EU')
  AND NOT EXISTS (SELECT 1 FROM fee_schedules f WHERE f.pricing_zone_id = z.id);
INSERT INTO exchange_rates (base_currency, quote_currency, rate, effective_from)
SELECT 'XAF', 'EUR', 0.001524, now() - interval '1 day'
WHERE NOT EXISTS (SELECT 1 FROM exchange_rates WHERE base_currency = 'XAF' AND quote_currency = 'EUR');

-- Banques et opérateurs (codes Wace fictifs, acceptés par le faux fournisseur)
INSERT INTO banks (country_code, name, short_code, wace_bank_id) VALUES
  ('CG', 'Banque Congolaise (essai)', 'BCG', '501'), ('CG', 'Crédit du Congo (essai)', 'CDC', '502'), ('FR', 'Banque de France (essai)', 'BDF', '601')
ON CONFLICT (country_code, wace_bank_id) DO NOTHING;
INSERT INTO mobile_operators (country_code, name, short_code, wace_payer_code, supports_payout, supports_payin) VALUES
  ('CG', 'MTN Congo (essai)', 'MTN', '16845316212', true, true), ('CG', 'Airtel Congo (essai)', 'AIR', '16845316213', true, true),
  ('CM', 'Orange Cameroun (essai)', 'OM', '16845316214', true, true)
ON CONFLICT (country_code, wace_payer_code) DO NOTHING;
INSERT INTO cities (country_code, name) VALUES ('CG', 'Brazzaville'), ('CG', 'Pointe-Noire'), ('CM', 'Douala'), ('CM', 'Yaoundé'), ('FR', 'Paris')
ON CONFLICT (country_code, name) DO NOTHING;

-- Libellés Wace (motifs selon le type de transfert, origines des fonds, liens)
INSERT INTO reference_values (category, party_type_pair, code, label, wace_code)
SELECT 'TRANSFER_PURPOSE', p.pair, v.code, v.label, v.wace FROM (VALUES ('P2P'), ('P2B'), ('B2P'), ('B2B')) AS p(pair),
  (VALUES ('FAMILY', 'Soutien familial', 'Family Maintainance'), ('EDUCATION', 'Études', 'Education'), ('BUSINESS', 'Affaires', 'Business')) AS v(code, label, wace)
ON CONFLICT DO NOTHING;
INSERT INTO reference_values (category, party_type_pair, code, label, wace_code) VALUES
  ('SOURCE_OF_FUNDS', 'ALL', 'SALARY', 'Salaire', 'Salary'), ('SOURCE_OF_FUNDS', 'ALL', 'SAVINGS', 'Épargne', 'Savings'),
  ('RELATIONSHIP', 'ALL', 'BROTHER', 'Frère / sœur', 'Brother'), ('RELATIONSHIP', 'ALL', 'SPOUSE', 'Conjoint(e)', 'Spouse'), ('RELATIONSHIP', 'ALL', 'FRIEND', 'Ami(e)', 'Friend')
ON CONFLICT DO NOTHING;
