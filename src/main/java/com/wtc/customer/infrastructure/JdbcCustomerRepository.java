package com.wtc.customer.infrastructure;

import com.wtc.customer.application.AdminCustomerSummary;
import com.wtc.customer.application.CustomerProfile;
import com.wtc.customer.application.Paged;
import com.wtc.customer.application.port.CustomerRepository;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCustomerRepository implements CustomerRepository {

    private static final Map<String, String> SORTABLE =
            Map.of(
                    "createdAt", "c.created_at",
                    "displayName",
                            "lower(COALESCE(c.business_name, c.last_name || ' ' || c.first_name))",
                    "kycStatus", "c.kyc_status",
                    "status", "c.status");

    private final JdbcClient jdbc;

    JdbcCustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CustomerRecord> findByPublicId(UUID publicId) {
        return jdbc.sql(
                        """
                        SELECT id, public_id, user_id, kind, first_name, last_name, business_name, business_type,
                               business_registered_on, birth_date, nationality, occupation, gender, civility,
                               address_line, city, country_code, phone_e164, email, kyc_status, status, created_at
                        FROM customers WHERE public_id = :id
                        """)
                .param("id", publicId)
                .query(
                        (rs, n) -> {
                            long userId = rs.getLong("user_id");
                            Long user = rs.wasNull() ? null : userId;
                            return new CustomerRecord(
                                    rs.getLong("id"),
                                    rs.getObject("public_id", UUID.class),
                                    user,
                                    new CustomerProfile(
                                            rs.getObject("public_id", UUID.class),
                                            rs.getString("kind"),
                                            rs.getString("first_name"),
                                            rs.getString("last_name"),
                                            rs.getString("business_name"),
                                            rs.getString("business_type"),
                                            rs.getObject("business_registered_on", LocalDate.class),
                                            rs.getObject("birth_date", LocalDate.class),
                                            rs.getString("nationality"),
                                            rs.getString("occupation"),
                                            rs.getString("gender"),
                                            rs.getString("civility"),
                                            rs.getString("address_line"),
                                            rs.getString("city"),
                                            rs.getString("country_code"),
                                            rs.getString("phone_e164"),
                                            rs.getString("email"),
                                            rs.getString("kyc_status"),
                                            rs.getString("status"),
                                            rs.getObject("created_at", OffsetDateTime.class)
                                                    .toInstant()));
                        })
                .optional();
    }

    @Override
    public void saveProfile(long id, CustomerProfile p) {
        jdbc.sql(
                        """
                        UPDATE customers SET kind = :kind, first_name = :first, last_name = :last, business_name = :bn,
                            business_type = :bt, business_registered_on = :bro, birth_date = :birth, nationality = :nat,
                            occupation = :occ, gender = :gender, civility = :civ, address_line = :addr, city = :city,
                            country_code = :country, phone_e164 = :phone
                        WHERE id = :id
                        """)
                .param("kind", p.kind())
                .param("first", p.firstName())
                .param("last", p.lastName())
                .param("bn", p.businessName())
                .param("bt", p.businessType())
                .param(
                        "bro",
                        p.businessRegisteredOn() == null
                                ? null
                                : Date.valueOf(p.businessRegisteredOn()))
                .param("birth", p.birthDate() == null ? null : Date.valueOf(p.birthDate()))
                .param("nat", p.nationality())
                .param("occ", p.occupation())
                .param("gender", p.gender())
                .param("civ", p.civility())
                .param("addr", p.addressLine())
                .param("city", p.city())
                .param("country", p.countryCode())
                .param("phone", p.phone())
                .param("id", id)
                .update();
    }

    @Override
    public void setStatus(long id, String status) {
        jdbc.sql("UPDATE customers SET status = :s WHERE id = :id")
                .param("s", status)
                .param("id", id)
                .update();
    }

    @Override
    public void setKycStatus(long id, String status) {
        jdbc.sql(
                        """
                        UPDATE customers SET kyc_status = :s,
                            kyc_verified_at = CASE WHEN :s = 'VERIFIED' THEN COALESCE(kyc_verified_at, now()) ELSE NULL END
                        WHERE id = :id
                        """)
                .param("s", status)
                .param("id", id)
                .update();
    }

    @Override
    public Paged<AdminCustomerSummary> search(
            String search,
            String status,
            String kycStatus,
            int page,
            int size,
            String sortField,
            boolean ascending) {
        String where =
                " WHERE (CAST(:search AS text) IS NULL OR c.first_name ILIKE :p OR c.last_name"
                    + " ILIKE :p OR c.business_name ILIKE :p OR c.email ILIKE :p OR c.phone_e164"
                    + " ILIKE :p) AND (CAST(:status AS text) IS NULL OR c.status = :status) AND"
                    + " (CAST(:kyc AS text) IS NULL OR c.kyc_status = :kyc)";
        String pattern =
                search == null
                        ? null
                        : "%"
                                + search.replace("\\", "\\\\")
                                        .replace("%", "\\%")
                                        .replace("_", "\\_")
                                + "%";
        String order =
                SORTABLE.getOrDefault(sortField, "c.created_at")
                        + (ascending ? " ASC" : " DESC")
                        + ", c.id";
        List<AdminCustomerSummary> rows =
                jdbc.sql(
                                "SELECT c.public_id, c.kind, c.first_name, c.last_name,"
                                        + " c.business_name, c.email, c.phone_e164, c.country_code,"
                                        + " c.kyc_status, c.status, c.created_at, w.balance,"
                                        + " w.currency_code FROM customers c LEFT JOIN LATERAL"
                                        + " (SELECT balance, currency_code FROM wallets WHERE"
                                        + " customer_id = c.id ORDER BY (currency_code = 'XAF')"
                                        + " DESC, id LIMIT 1) w ON true"
                                        + where
                                        + " ORDER BY "
                                        + order
                                        + " LIMIT :size OFFSET :offset")
                        .param("search", search)
                        .param("status", status)
                        .param("kyc", kycStatus)
                        .param("p", pattern)
                        .param("size", size)
                        .param("offset", (long) page * size)
                        .query(
                                (rs, n) ->
                                        new AdminCustomerSummary(
                                                rs.getObject("public_id", UUID.class),
                                                displayName(
                                                        rs.getString("kind"),
                                                        rs.getString("first_name"),
                                                        rs.getString("last_name"),
                                                        rs.getString("business_name")),
                                                rs.getString("kind"),
                                                rs.getString("email"),
                                                rs.getString("phone_e164"),
                                                rs.getString("country_code"),
                                                rs.getString("kyc_status"),
                                                rs.getString("status"),
                                                rs.getBigDecimal("balance") == null
                                                        ? null
                                                        : new AdminCustomerSummary.Balance(
                                                                rs.getBigDecimal("balance")
                                                                        .toPlainString(),
                                                                rs.getString("currency_code")
                                                                        .strip()),
                                                rs.getObject("created_at", OffsetDateTime.class)
                                                        .toInstant()))
                        .list();
        long total =
                jdbc.sql("SELECT count(*) FROM customers c" + where)
                        .param("search", search)
                        .param("status", status)
                        .param("kyc", kycStatus)
                        .param("p", pattern)
                        .query(Long.class)
                        .single();
        return new Paged<>(rows, total);
    }

    static String displayName(String kind, String first, String last, String business) {
        if ("BUSINESS".equals(kind) && business != null) {
            return business;
        }
        return ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
    }
}
