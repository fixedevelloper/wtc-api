package com.wtc.reference.application;

import java.util.List;

/** Page de résultats. */
public record Page<T>(List<T> content, long total) {
    public Page {
        content = List.copyOf(content);
    }
}
