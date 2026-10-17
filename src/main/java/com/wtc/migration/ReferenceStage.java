package com.wtc.migration;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pays, zones tarifaires, villes et barème de frais. Les taux de change ne sont pas migrés (aucun
 * code ne les lisait).
 */
final class ReferenceStage {

    private final EtlContext ctx;

    ReferenceStage(EtlContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        ctx.report.stage("reference");
        zones();
        countries();
        cities();
        fees();
        settings();
    }

    private void zones() {
        Set<String> used = new HashSet<>();
        for (Row z : ctx.rows("SELECT id, libelle, code FROM zone ORDER BY id")) {
            String label = Parse.clean(z.str("libelle"));
            String code =
                    z.str("code") == null
                            ? null
                            : z.str("code").toUpperCase().replaceAll("[^A-Z0-9_-]", "");
            if (code == null || code.isEmpty() || code.length() > 20 || !used.add(code)) {
                String fallback = "Z" + z.idOrZero();
                ctx.report.warn(
                        "zone",
                        z.idOrZero(),
                        "Code de zone inutilisable ou en double : « "
                                + z.str("code")
                                + " » remplacé par "
                                + fallback,
                        z.forReport());
                code = fallback;
                used.add(code);
            }
            long id =
                    ctx.target
                            .sql(
                                    """
                                    INSERT INTO pricing_zones (code, name, legacy_id) VALUES (:code, :name, :legacy)
                                    ON CONFLICT (legacy_id) DO UPDATE SET name = EXCLUDED.name RETURNING id
                                    """)
                            .param("code", code)
                            .param("name", label == null ? code : label)
                            .param("legacy", z.idOrZero())
                            .query(Long.class)
                            .single();
            ctx.zoneByLegacyId.put(z.idOrZero(), id);
            ctx.report.count("pricing_zones.migrated");
        }
    }

    private void countries() {
        Set<Long> sendingCountries = new HashSet<>();
        ctx.rows("SELECT DISTINCT country_id FROM customer WHERE country_id IS NOT NULL")
                .forEach(r -> sendingCountries.add(r.id("country_id")));
        Set<Long> receivingCountries = new HashSet<>();
        ctx.rows("SELECT DISTINCT country_id FROM transaction WHERE country_id IS NOT NULL")
                .forEach(r -> receivingCountries.add(r.id("country_id")));
        for (Row c :
                ctx.rows(
                        "SELECT id, zone_id, code, code_string, libelle, monaire, payer_code FROM"
                                + " country ORDER BY id")) {
            long legacyId = c.idOrZero();
            String iso2 = CountryTable.iso2(c.str("libelle"), c.str("code_string"));
            if (iso2 == null) {
                ctx.report.error(
                        "country",
                        legacyId,
                        "Pays rejeté : ni le libellé ni le code ne donnent un code ISO",
                        c.forReport());
                ctx.report.count("countries.rejected");
                continue;
            }
            if (!iso2.equalsIgnoreCase(c.str("code_string"))) {
                ctx.report.warn(
                        "country",
                        legacyId,
                        "Code ISO corrigé : « " + c.str("code_string") + " » → " + iso2,
                        c.forReport());
            }
            ctx.countryByLegacyId.put(legacyId, iso2);
            if (ctx.currencyByCountry.containsKey(iso2)) {
                ctx.report.warn(
                        "country",
                        legacyId,
                        "Pays en double : fusionné avec le pays déjà migré " + iso2,
                        c.forReport());
                ctx.report.count("countries.merged");
                continue;
            }
            String currency = Parse.currency(c.str("monaire"));
            if (currency == null) {
                currency = "XAF";
                ctx.report.warn(
                        "country",
                        legacyId,
                        "Devise inexploitable « "
                                + c.str("monaire")
                                + " » : XAF retenu, à confirmer",
                        c.forReport());
            } else if (!currency.equalsIgnoreCase(c.str("monaire"))) {
                ctx.report.info(
                        "country",
                        legacyId,
                        "Devise normalisée : « " + c.str("monaire") + " » → " + currency);
            }
            if ("CD".equals(iso2) && "XAF".equals(currency)) {
                ctx.report.warn(
                        "country",
                        legacyId,
                        "RDC en XAF dans l'ancienne base (la devise réelle est CDF/USD) : à"
                                + " confirmer avant la bascule",
                        c.forReport());
            }
            String calling =
                    c.str("code") == null ? null : "+" + c.str("code").replaceAll("[^0-9]", "");
            boolean canSend =
                    sendingCountries.contains(legacyId)
                            || sendingCountries.stream()
                                    .anyMatch(id -> iso2.equals(ctx.countryByLegacyId.get(id)));
            boolean canReceive =
                    receivingCountries.contains(legacyId)
                            || c.str("payer_code") != null && !"0".equals(c.str("payer_code"));
            Long zone = c.id("zone_id") == null ? null : ctx.zoneByLegacyId.get(c.id("zone_id"));
            ctx.target
                    .sql(
                            """
                            INSERT INTO countries (code, iso3, name, currency_code, calling_code, pricing_zone_id, can_send, can_receive, legacy_id)
                            VALUES (:code, :iso3, :name, :currency, :calling, :zone, :send, :receive, :legacy)
                            ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, currency_code = EXCLUDED.currency_code,
                                calling_code = EXCLUDED.calling_code, pricing_zone_id = EXCLUDED.pricing_zone_id, legacy_id = EXCLUDED.legacy_id
                            """)
                    .param("code", iso2)
                    .param("iso3", CountryTable.iso3(iso2))
                    .param("name", Parse.clean(c.str("libelle")))
                    .param("currency", currency)
                    .param("calling", calling)
                    .param("zone", zone)
                    .param("send", canSend)
                    .param("receive", canReceive)
                    .param("legacy", legacyId)
                    .update();
            ctx.currencyByCountry.put(iso2, currency);
            if (calling != null) {
                ctx.callingCodeByCountry.put(iso2, calling);
            }
            ctx.report.count("countries.migrated");
        }
        // Le pays marqué « peut envoyer » l'est pour tous les identifiants qui lui ont été
        // fusionnés.
        for (Long id : sendingCountries) {
            String iso = ctx.countryByLegacyId.get(id);
            if (iso != null) {
                ctx.target
                        .sql("UPDATE countries SET can_send = true WHERE code = :c")
                        .param("c", iso)
                        .update();
            }
        }
        for (Long id : receivingCountries) {
            String iso = ctx.countryByLegacyId.get(id);
            if (iso != null) {
                ctx.target
                        .sql("UPDATE countries SET can_receive = true WHERE code = :c")
                        .param("c", iso)
                        .update();
            }
        }
    }

    private void cities() {
        for (Row t : ctx.rows("SELECT id, libelle, country_id FROM town ORDER BY id")) {
            String iso =
                    t.id("country_id") == null
                            ? null
                            : ctx.countryByLegacyId.get(t.id("country_id"));
            String name = Parse.clean(t.str("libelle"));
            if (iso == null || name == null) {
                ctx.report.warn(
                        "town",
                        t.idOrZero(),
                        "Ville ignorée : pays ou nom manquant",
                        t.forReport());
                ctx.report.count("cities.rejected");
                continue;
            }
            int n =
                    ctx.target
                            .sql(
                                    """
                                    INSERT INTO cities (country_code, name, legacy_id) VALUES (:c, :n, :l)
                                    ON CONFLICT DO NOTHING
                                    """)
                            .param("c", iso)
                            .param("n", name)
                            .param("l", t.idOrZero())
                            .update();
            ctx.report.count(n == 1 ? "cities.migrated" : "cities.duplicate");
        }
    }

    /**
     * Une tranche par ligne ; la devise est celle des pays de la zone (une seule requise), jamais
     * devinée.
     */
    private void fees() {
        Map<Long, Set<String>> currencies = new java.util.HashMap<>();
        for (Row c : ctx.rows("SELECT id, zone_id FROM country")) {
            String iso = ctx.countryByLegacyId.get(c.idOrZero());
            if (iso != null && c.id("zone_id") != null) {
                currencies
                        .computeIfAbsent(c.id("zone_id"), k -> new HashSet<>())
                        .add(ctx.currencyByCountry.get(iso));
            }
        }
        java.time.LocalDate from =
                ctx.props.effectiveFrom() == null ? ctx.today : ctx.props.effectiveFrom();
        for (Row g :
                ctx.rows(
                        "SELECT id, tranche_a, tranche_b, frais, zone_id FROM grilletarifaire ORDER"
                                + " BY zone_id, tranche_a")) {
            Long zone = g.id("zone_id") == null ? null : ctx.zoneByLegacyId.get(g.id("zone_id"));
            Set<String> zoneCurrencies =
                    g.id("zone_id") == null
                            ? Set.of()
                            : currencies.getOrDefault(g.id("zone_id"), Set.of());
            BigDecimal min = g.decimal("tranche_a");
            BigDecimal max = g.decimal("tranche_b");
            BigDecimal percent = g.decimal("frais");
            if (zone == null
                    || zoneCurrencies.size() != 1
                    || min == null
                    || max == null
                    || percent == null
                    || max.compareTo(min) <= 0
                    || percent.signum() < 0
                    || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
                ctx.report.error(
                        "grilletarifaire",
                        g.idOrZero(),
                        "Tranche non migrée : zone sans devise unique ("
                                + zoneCurrencies
                                + ") ou valeurs invalides",
                        g.forReport());
                ctx.report.count("fees.rejected");
                continue;
            }
            String currency = zoneCurrencies.iterator().next();
            boolean ok =
                    ctx.attempt(
                            () ->
                                    ctx.target
                                            .sql(
                                                    """
                                                    INSERT INTO fee_schedules (pricing_zone_id, currency_code, min_amount, max_amount, percent, fixed_fee, effective_from)
                                                    VALUES (:z, :cur, :min, :max, :pct, 0, :from)
                                                    """)
                                            .param("z", zone)
                                            .param("cur", currency)
                                            .param("min", min)
                                            .param("max", max)
                                            .param("pct", percent)
                                            .param("from", Date.valueOf(from))
                                            .update());
            if (ok) {
                ctx.report.count("fees.migrated");
            } else {
                // Chevauchement de tranches (contrainte d'exclusion) : la ligne source est
                // rapportée, pas corrigée.
                ctx.report.error(
                        "grilletarifaire",
                        g.idOrZero(),
                        "Tranche refusée par le schéma (chevauchement ?) : " + ctx.lastError,
                        g.forReport());
                ctx.report.count("fees.rejected");
            }
        }
    }

    private void settings() {
        ctx.report.info(
                "tauxechange",
                null,
                "Taux de change non migrés : aucune lecture dans l'ancien code, sémantique des"
                        + " colonnes inconnue ; à saisir dans le back-office");
        ctx.report.info(
                "configuration",
                null,
                "tauxplatform non migré : paramètre inutilisé par le nouveau système");
    }
}
