package com.wtc.reference.application;

/** Banque telle que l'exposent le référentiel et les modules qui s'en servent. */
public record BankView(long id, String countryCode, String name, String shortCode) {}
