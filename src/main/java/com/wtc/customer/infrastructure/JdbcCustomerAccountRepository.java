package com.wtc.customer.infrastructure;

import com.wtc.customer.application.CustomerProfile;
import com.wtc.customer.application.port.CustomerAccountRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCustomerAccountRepository implements CustomerAccountRepository {

    private final JdbcClient jdbc;

    JdbcCustomerAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insertIndividual(
            long userId,
            String firstName,
            String lastName,
            String phone,
            String email,
            String countryCode) {
        return jdbc.sql(
                        """
                        INSERT INTO customers (user_id, kind, first_name, last_name, phone_e164, email, country_code)
                        VALUES (:userId, 'INDIVIDUAL', :first, :last, :phone, :email, :country)
                        RETURNING public_id
                        """)
                .param("userId", userId)
                .param("first", firstName)
                .param("last", lastName)
                .param("phone", phone)
                .param("email", email)
                .param("country", countryCode)
                .query(UUID.class)
                .single();
    }

    @Override
    public Optional<UUID> findPublicIdByUserId(long userId) {
        return jdbc.sql("SELECT public_id FROM customers WHERE user_id = :userId")
                .param("userId", userId)
                .query(UUID.class)
                .optional();
    }

    @Override
    public Optional<CustomerProfile> findProfileByUserId(long userId) {
        return jdbc.sql(
                        """
                        SELECT public_id, kind, first_name, last_name, business_name, business_type,
                               business_registered_on, birth_date, nationality, occupation, gender, civility,
                               address_line, city, country_code, phone_e164, email, kyc_status, status, created_at
                        FROM customers WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .query(JdbcCustomerAccountRepository::map)
                .optional();
    }

    private static CustomerProfile map(ResultSet rs, int row) throws SQLException {
        return new CustomerProfile(
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
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());
    }
}
