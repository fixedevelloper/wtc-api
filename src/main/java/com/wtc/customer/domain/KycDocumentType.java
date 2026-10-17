package com.wtc.customer.domain;

public enum KycDocumentType {
    PASSPORT(true),
    NATIONAL_ID(true),
    RESIDENCE_PERMIT(true),
    DRIVING_LICENSE(true),
    PROOF_OF_ADDRESS(false),
    SELFIE(false),
    BUSINESS_REGISTRATION(false);

    private final boolean identity;

    KycDocumentType(boolean identity) {
        this.identity = identity;
    }

    /** Pièce d'identité proprement dite (c'est elle qui fait passer un client à « vérifié »). */
    public boolean isIdentity() {
        return identity;
    }

    /** Un selfie est forcément une image, jamais un PDF. */
    public boolean imageOnly() {
        return this == SELFIE;
    }
}
