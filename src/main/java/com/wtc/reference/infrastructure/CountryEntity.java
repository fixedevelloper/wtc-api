package com.wtc.reference.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Projection JPA de la table {@code countries} (lecture seule côté application). */
@Entity
@Table(name = "countries")
class CountryEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "code", length = 2, columnDefinition = "char(2)")
    private String code;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "iso3", length = 3, columnDefinition = "char(3)")
    private String iso3;

    @Column(name = "name")
    private String name;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency_code", length = 3, columnDefinition = "char(3)")
    private String currencyCode;

    @Column(name = "calling_code")
    private String callingCode;

    @Column(name = "flag_url")
    private String flagUrl;

    @Column(name = "can_send")
    private boolean canSend;

    @Column(name = "can_receive")
    private boolean canReceive;

    @Column(name = "enabled")
    private boolean enabled;

    protected CountryEntity() {}

    String code() {
        return code;
    }

    String iso3() {
        return iso3;
    }

    String name() {
        return name;
    }

    String currencyCode() {
        return currencyCode;
    }

    String callingCode() {
        return callingCode;
    }

    String flagUrl() {
        return flagUrl;
    }

    boolean canSend() {
        return canSend;
    }

    boolean canReceive() {
        return canReceive;
    }

    boolean enabled() {
        return enabled;
    }
}
