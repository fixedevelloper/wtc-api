package com.wtc.identity.domain;

/**
 * Rôles de la plateforme. Les permissions fines de chaque rôle sont en base ({@code
 * role_permissions}).
 */
public enum Role {
    CUSTOMER,
    SUPPORT,
    ADMIN,
    SUPER_ADMIN;

    /**
     * Un membre du personnel est tout utilisateur qui n'est pas un simple client : le second
     * facteur lui est obligatoire.
     */
    public boolean isStaff() {
        return this != CUSTOMER;
    }
}
