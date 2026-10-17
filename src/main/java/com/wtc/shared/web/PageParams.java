package com.wtc.shared.web;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import java.util.List;
import java.util.Set;

/**
 * Lecture du paramètre {@code sort=champ,asc|desc} avec liste blanche des champs (jamais de nom de
 * colonne libre).
 */
public record PageParams(String field, boolean ascending) {

    public static PageParams parse(
            String sort, Set<String> allowedFields, String defaultField, boolean defaultAscending) {
        if (sort == null || sort.isBlank()) {
            return new PageParams(defaultField, defaultAscending);
        }
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        boolean ascending = parts.length < 2 || !parts[1].trim().equalsIgnoreCase("desc");
        if (parts.length == 2
                && !parts[1].trim().equalsIgnoreCase("asc")
                && !parts[1].trim().equalsIgnoreCase("desc")) {
            throw invalid("Le sens du tri doit être asc ou desc.");
        }
        if (!allowedFields.contains(field)) {
            throw invalid(
                    "Tri impossible sur ce champ. Champs : "
                            + String.join(", ", allowedFields.stream().sorted().toList()));
        }
        return new PageParams(field, ascending);
    }

    private static DomainException invalid(String message) {
        return new DomainException(
                ErrorCode.VALIDATION_FAILED,
                message,
                List.of(new FieldViolation("sort", "InvalidSort", message)));
    }
}
