package com.wtc.customer.domain;

/** Statut KYC d'un client (valeur calculée par {@link KycPolicy} à partir de ses documents). */
public enum KycStatus {
    NOT_SUBMITTED,
    PENDING,
    VERIFIED,
    REJECTED,
    EXPIRED
}
