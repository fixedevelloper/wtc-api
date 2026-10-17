package com.wtc.customer.application;

import java.util.List;

public record KycOverview(String status, List<KycDocumentView> documents) {
    public KycOverview {
        documents = List.copyOf(documents);
    }
}
