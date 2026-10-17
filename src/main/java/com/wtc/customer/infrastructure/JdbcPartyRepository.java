package com.wtc.customer.infrastructure;

import com.wtc.customer.application.Paged;
import com.wtc.customer.application.PartyData;
import com.wtc.customer.application.port.PartyRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPartyRepository implements PartyRepository {

    private static final String SELECT =
            """
            SELECT p.id, p.public_id, p.customer_id, c.public_id AS customer_public_id,
                   c.kind AS c_kind, c.first_name AS c_first, c.last_name AS c_last, c.business_name AS c_business,
                   p.role, p.kind, p.first_name, p.last_name, p.business_name, p.business_type, p.business_registered_on,
                   p.birth_date, p.nationality, p.occupation, p.gender, p.civility, p.address_line, p.city, p.country_code,
                   p.phone_e164, p.email, p.id_document_type, p.is_pep, p.id_document_expires_on, p.id_document_number_enc,
                   p.id_document_last4, p.created_at
            FROM parties p JOIN customers c ON c.id = p.customer_id
            """;

    private static final Map<String, String> SORTABLE =
            Map.of(
                    "createdAt", "p.created_at",
                    "firstName", "lower(p.first_name)",
                    "lastName", "lower(p.last_name)",
                    "businessName", "lower(p.business_name)");

    private static final RowMapper<PartyRecord> MAPPER =
            (rs, n) ->
                    new PartyRecord(
                            rs.getLong("id"),
                            rs.getObject("public_id", UUID.class),
                            rs.getLong("customer_id"),
                            rs.getObject("customer_public_id", UUID.class),
                            JdbcCustomerRepository.displayName(
                                    rs.getString("c_kind"),
                                    rs.getString("c_first"),
                                    rs.getString("c_last"),
                                    rs.getString("c_business")),
                            rs.getString("role"),
                            new PartyData(
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
                                    rs.getString("id_document_type"),
                                    rs.getObject("id_document_expires_on", LocalDate.class),
                                    rs.getBoolean("is_pep")),
                            rs.getBytes("id_document_number_enc"),
                            rs.getString("id_document_last4"),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    JdbcPartyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(
            UUID publicId, long customerId, String role, PartyData d, byte[] enc, String last4) {
        bind(
                        jdbc.sql(
                                        """
                                        INSERT INTO parties (public_id, customer_id, role, kind, first_name, last_name, business_name, business_type,
                                            business_registered_on, birth_date, nationality, occupation, gender, civility, address_line, city,
                                            country_code, phone_e164, email, id_document_type, id_document_expires_on,
                                            id_document_number_enc, id_document_last4, is_pep)
                                        VALUES (:publicId, :customerId, :role, :kind, :first, :last, :bn, :bt, :bro, :birth, :nat, :occ, :gender, :civ,
                                            :addr, :city, :country, :phone, :email, :idType, :idExp, :enc, :last4, :pep)
                                        """)
                                .param("publicId", publicId)
                                .param("customerId", customerId)
                                .param("role", role),
                        d)
                .param("enc", enc)
                .param("last4", last4)
                .update();
    }

    @Override
    public Optional<PartyRecord> find(UUID publicId) {
        return jdbc.sql(SELECT + " WHERE p.public_id = :id AND p.archived_at IS NULL")
                .param("id", publicId)
                .query(MAPPER)
                .optional();
    }

    @Override
    public void update(long id, PartyData d, boolean replaceIdNumber, byte[] enc, String last4) {
        String idColumns =
                replaceIdNumber
                        ? ", id_document_number_enc = :enc, id_document_last4 = :last4"
                        : "";
        bind(
                        jdbc.sql(
                                        """
                                        UPDATE parties SET kind = :kind, first_name = :first, last_name = :last, business_name = :bn,
                                            business_type = :bt, business_registered_on = :bro, birth_date = :birth, nationality = :nat,
                                            occupation = :occ, gender = :gender, civility = :civ, address_line = :addr, city = :city,
                                            country_code = :country, phone_e164 = :phone, email = :email, id_document_type = :idType,
                                            id_document_expires_on = :idExp, is_pep = :pep
                                        """
                                                + idColumns
                                                + " WHERE id = :id")
                                .param("id", id),
                        d)
                .param(
                        "enc",
                        replaceIdNumber ? enc : null,
                        replaceIdNumber ? java.sql.Types.BINARY : java.sql.Types.NULL)
                .param("last4", last4)
                .update();
    }

    @Override
    public void archive(long id, Instant now) {
        jdbc.sql(
                        "UPDATE payout_destinations SET archived_at = :now, is_default = false"
                                + " WHERE party_id = :id AND archived_at IS NULL")
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
        jdbc.sql("UPDATE parties SET archived_at = :now WHERE id = :id")
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    @Override
    public Paged<PartyRecord> listOfCustomer(
            long customerId,
            String role,
            String search,
            int page,
            int size,
            String sortField,
            boolean ascending) {
        return query(
                " WHERE p.archived_at IS NULL AND p.customer_id = :customerId AND p.role = :role",
                "customerId",
                customerId,
                role,
                null,
                search,
                page,
                size,
                sortField,
                ascending);
    }

    @Override
    public Paged<PartyRecord> adminList(
            String role,
            UUID customerPublicId,
            String search,
            int page,
            int size,
            String sortField,
            boolean ascending) {
        return query(
                " WHERE p.archived_at IS NULL AND (CAST(:role AS text) IS NULL OR p.role = :role)"
                        + " AND (CAST(:customerPublic AS uuid) IS NULL OR c.public_id ="
                        + " :customerPublic)",
                "customerId",
                null,
                role,
                customerPublicId,
                search,
                page,
                size,
                sortField,
                ascending);
    }

    private Paged<PartyRecord> query(
            String where,
            String idParam,
            Long customerId,
            String role,
            UUID customerPublicId,
            String search,
            int page,
            int size,
            String sortField,
            boolean ascending) {
        String searchClause =
                " AND (CAST(:search AS text) IS NULL OR p.first_name ILIKE :p OR p.last_name ILIKE"
                    + " :p OR p.business_name ILIKE :p OR p.email ILIKE :p OR p.phone_e164 ILIKE"
                    + " :p)";
        String pattern =
                search == null
                        ? null
                        : "%"
                                + search.replace("\\", "\\\\")
                                        .replace("%", "\\%")
                                        .replace("_", "\\_")
                                + "%";
        String order =
                SORTABLE.getOrDefault(sortField, "p.created_at")
                        + (ascending ? " ASC" : " DESC")
                        + ", p.id";
        String sql =
                SELECT
                        + where
                        + searchClause
                        + " ORDER BY "
                        + order
                        + " LIMIT :size OFFSET :offset";
        List<PartyRecord> rows =
                params(jdbc.sql(sql), customerId, role, customerPublicId, search, pattern)
                        .param("size", size)
                        .param("offset", (long) page * size)
                        .query(MAPPER)
                        .list();
        String countSql =
                "SELECT count(*) FROM parties p JOIN customers c ON c.id = p.customer_id"
                        + where
                        + searchClause;
        long total =
                params(jdbc.sql(countSql), customerId, role, customerPublicId, search, pattern)
                        .query(Long.class)
                        .single();
        return new Paged<>(rows, total);
    }

    private static StatementSpec params(
            StatementSpec spec,
            Long customerId,
            String role,
            UUID customerPublicId,
            String search,
            String pattern) {
        StatementSpec s = spec.param("role", role).param("search", search).param("p", pattern);
        if (customerId != null) {
            s = s.param("customerId", customerId);
        } else {
            s = s.param("customerPublic", customerPublicId, java.sql.Types.OTHER);
        }
        return s;
    }

    private static StatementSpec bind(StatementSpec spec, PartyData d) {
        return spec.param("kind", d.kind())
                .param("first", d.firstName())
                .param("last", d.lastName())
                .param("bn", d.businessName())
                .param("bt", d.businessType())
                .param(
                        "bro",
                        d.businessRegisteredOn() == null
                                ? null
                                : Date.valueOf(d.businessRegisteredOn()))
                .param("birth", d.birthDate() == null ? null : Date.valueOf(d.birthDate()))
                .param("nat", d.nationality())
                .param("occ", d.occupation())
                .param("gender", d.gender())
                .param("civ", d.civility())
                .param("addr", d.addressLine())
                .param("city", d.city())
                .param("country", d.countryCode())
                .param("phone", d.phone())
                .param("email", d.email())
                .param("idType", d.idDocumentType())
                .param(
                        "idExp",
                        d.idDocumentExpiresOn() == null
                                ? null
                                : Date.valueOf(d.idDocumentExpiresOn()))
                .param("pep", d.pep());
    }
}
