package com.wtc.reference.application;

import java.util.List;

public record PricingZoneView(long id, String code, String name, List<String> countries) {
    public PricingZoneView {
        countries = List.copyOf(countries);
    }
}
