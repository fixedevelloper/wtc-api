package com.wtc.customer.application;

import java.util.List;

/** Une page de résultats et le total. */
public record Paged<T>(List<T> content, long total) {
    public Paged {
        content = List.copyOf(content);
    }
}
