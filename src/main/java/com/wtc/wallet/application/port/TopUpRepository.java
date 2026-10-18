package com.wtc.wallet.application.port;

import com.wtc.wallet.application.Page;
import com.wtc.wallet.application.TopUpView;
import java.time.Instant;
import java.util.UUID;

public interface TopUpRepository {

    /**
     * Filtres facultatifs ({@code to} exclu) ; {@code search} porte sur la référence et le client.
     */
    record Filter(String status, UUID customerId, Instant from, Instant to, String search) {}

    Page<TopUpView> search(Filter filter, int page, int size, boolean newestFirst);
}
