package com.wtc.shared.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Enveloppe de pagination du contrat : {@code {page, size, totalElements, totalPages, content[]}}.
 */
public record PageResponse<T>(
        int page, int size, long totalElements, int totalPages, List<T> content) {

    public PageResponse {
        content =
                List.copyOf(
                        content); // copie immuable : le record n'expose jamais une liste modifiable
    }

    public static <S, T> PageResponse<T> of(Page<S> source, Function<S, T> mapper) {
        return new PageResponse<>(
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.getContent().stream().map(mapper).toList());
    }
}
