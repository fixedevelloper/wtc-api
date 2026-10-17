package com.wtc.shared.error;

import java.util.List;

/**
 * Erreur métier prévisible. Elle porte un {@link ErrorCode} stable ; le message est destiné aux
 * humains. À lever depuis le domaine et les cas d'usage : la couche web la convertit en {@code
 * application/problem+json}.
 */
public class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient List<FieldViolation> violations;

    public DomainException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public DomainException(ErrorCode code, String message, List<FieldViolation> violations) {
        super(message);
        this.code = code;
        this.violations = List.copyOf(violations);
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
