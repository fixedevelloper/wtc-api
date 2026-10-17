package com.wtc.migration;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Correction des pays de l'ancienne base, dont les codes ISO sont faux ou en double (le Canada
 * porte le code {@code CD} de la RDC, l'Ouganda {@code OG}, la Belgique {@code BG}, le Ghana est
 * saisi deux fois). La clé est le <b>libellé</b> d'origine (normalisé) et non l'identifiant, qui
 * peut différer entre la base de développement et la production. Un libellé inconnu retombe sur le
 * code d'origine s'il est un code ISO valide ; sinon le pays est rejeté et signalé.
 */
final class CountryTable {

    private static final Set<String> ISO2 = Set.of(Locale.getISOCountries());

    private static final Map<String, String> BY_NAME =
            Map.ofEntries(
                    Map.entry("CAMEROUN", "CM"),
                    Map.entry("REPUBLIQUE DU CONGO (RDC)", "CD"),
                    Map.entry("REPUBLIQUE DEMOCRATIQUE DU CONGO", "CD"),
                    Map.entry("RDC", "CD"),
                    Map.entry("REPUBLIQUE DU CONGO", "CG"),
                    Map.entry("CONGO", "CG"),
                    Map.entry("FRANCE", "FR"),
                    Map.entry("BELGIQUE", "BE"),
                    Map.entry("CHINE", "CN"),
                    Map.entry("RWANDA", "RW"),
                    Map.entry("INDE", "IN"),
                    Map.entry("TURKEY", "TR"),
                    Map.entry("TURQUIE", "TR"),
                    Map.entry("CANADA", "CA"),
                    Map.entry("OUGANDA", "UG"),
                    Map.entry("GHANA", "GH"),
                    Map.entry("GRANDE BRETAGNE", "GB"),
                    Map.entry("ROYAUME-UNI", "GB"),
                    Map.entry("COTE D'IVOIRE", "CI"),
                    Map.entry("SENEGAL", "SN"),
                    Map.entry("MALI", "ML"),
                    Map.entry("BENIN", "BJ"),
                    Map.entry("TOGO", "TG"),
                    Map.entry("NIGERIA", "NG"),
                    Map.entry("NIGER", "NE"),
                    Map.entry("BURKINA FASO", "BF"),
                    Map.entry("RUSSIE", "RU"),
                    Map.entry("ETATS UNIES D'AMERIQUE", "US"),
                    Map.entry("ETATS-UNIS", "US"),
                    Map.entry("USA", "US"));

    private CountryTable() {}

    /**
     * Code ISO 3166-1 alpha-2 corrigé, ou null si ni le libellé ni le code d'origine ne permettent
     * de conclure.
     */
    static String iso2(String label, String legacyCode) {
        String byName = label == null ? null : BY_NAME.get(normalize(label));
        if (byName != null) {
            return byName;
        }
        String code = legacyCode == null ? null : legacyCode.strip().toUpperCase(Locale.ROOT);
        return code != null && ISO2.contains(code) ? code : null;
    }

    static String iso3(String iso2) {
        return new Locale("", iso2).getISO3Country();
    }

    static String normalize(String label) {
        String s = Normalizer.normalize(label, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replace("  ", " ")
                .strip()
                .replace("’", "'");
    }
}
