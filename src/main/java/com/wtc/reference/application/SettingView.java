package com.wtc.reference.application;

import java.time.Instant;

/** Paramètre de la plateforme ; {@code valueJson} est la valeur JSON brute. */
public record SettingView(String key, String valueJson, Instant updatedAt) {}
