package com.wtc.reference.domain;

/** Banque du pays de destination ({@code id} interne, exposé par le contrat). */
public record Bank(long id, String countryCode, String name, String shortCode) {}
