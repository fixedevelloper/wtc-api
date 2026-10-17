package com.wtc.migration;

/**
 * Identité d'une personne ou d'une entreprise telle que la contrainte du schéma l'exige (prénom ou
 * raison sociale).
 */
record Identity(boolean business, String firstName, String lastName, String businessName) {

    String displayName() {
        return business
                ? businessName
                : (firstName + (lastName == null ? "" : " " + lastName)).strip();
    }

    /**
     * Déduit l'identité d'une fiche source ; {@code null} si rien d'exploitable. Les corrections
     * sont consignées au rapport.
     */
    static Identity of(Row row, EtlReport report, String table) {
        long id = row.idOrZero();
        boolean business =
                "B".equalsIgnoreCase(row.str("type"))
                        || row.str("type") == null
                                && row.str("bussinesname") != null
                                && row.str("firstname") == null;
        String first = Parse.clean(row.str("firstname"));
        String last = Parse.clean(row.str("lastname"));
        String businessName = Parse.clean(row.str("bussinesname"));
        if (!business && first == null) {
            if (last != null) {
                report.warn(
                        table,
                        id,
                        "Prénom absent : le nom est repris comme prénom",
                        row.forReport());
                first = last;
                last = null;
            } else if (businessName != null) {
                business = true;
                report.warn(
                        table,
                        id,
                        "Aucun nom de personne mais une raison sociale : repris comme entreprise",
                        row.forReport());
            }
        }
        if (business && businessName == null && first != null) {
            businessName = (first + (last == null ? "" : " " + last)).strip();
            report.warn(
                    table,
                    id,
                    "Entreprise sans raison sociale : nom de personne repris",
                    row.forReport());
        }
        if (!business && first == null || business && businessName == null) {
            return null;
        }
        return business
                ? new Identity(true, null, null, businessName)
                : new Identity(false, first, last, null);
    }
}
