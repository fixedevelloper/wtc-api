package com.wtc.shared.error;

/** Une erreur de validation rattachée à un champ ({@code errors[]} d'un problème RFC 9457). */
public record FieldViolation(String field, String code, String message) {}
