package com.wtc.transfer.infrastructure;

import com.wtc.transfer.application.port.ProviderRefRepository;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProviderRefRepository implements ProviderRefRepository {

    private final JdbcClient jdbc;

    JdbcProviderRefRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> find(long partyId, String provider, Long senderPartyId) {
        return jdbc.sql(
                        "SELECT external_code FROM provider_party_refs WHERE party_id = :p AND"
                                + " provider = :prov AND sender_party_id IS NOT DISTINCT FROM :s")
                .param("p", partyId)
                .param("prov", provider)
                .param("s", senderPartyId)
                .query(String.class)
                .optional();
    }

    @Override
    public void save(long partyId, String provider, Long senderPartyId, String externalCode) {
        jdbc.sql(
                        "INSERT INTO provider_party_refs (party_id, provider, sender_party_id,"
                                + " external_code) VALUES (:p, :prov, :s, :code) ON CONFLICT DO"
                                + " NOTHING")
                .param("p", partyId)
                .param("prov", provider)
                .param("s", senderPartyId)
                .param("code", externalCode)
                .update();
    }
}
