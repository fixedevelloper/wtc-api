package com.wtc.shared.web;

import java.util.List;

/** Calcule l'enveloppe de pagination du contrat à partir d'un total. */
public final class Paged {

    private Paged() {}

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long total) {
        int totalPages = (int) Math.ceil(total / (double) size);
        return new PageResponse<>(page, size, total, totalPages, content);
    }
}
