package com.wtc.reference.infrastructure;

import com.wtc.reference.application.ReferenceValueView;
import com.wtc.reference.application.port.ReferenceValueRepository;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReferenceValueRepository implements ReferenceValueRepository {

    private final JdbcClient jdbc;

    JdbcReferenceValueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReferenceValueView> list(String category, String partyTypePair) {
        return jdbc.sql(
                        """
                        SELECT DISTINCT code, label FROM reference_values
                        WHERE category = :category AND enabled AND (CAST(:pair AS text) IS NULL OR party_type_pair IN ('ALL', :pair))
                        ORDER BY label
                        """)
                .param("category", category)
                .param("pair", partyTypePair)
                .query(
                        (rs, n) ->
                                new ReferenceValueView(rs.getString("code"), rs.getString("label")))
                .list();
    }
}
