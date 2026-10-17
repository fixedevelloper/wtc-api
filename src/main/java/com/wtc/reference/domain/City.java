package com.wtc.reference.domain;

import java.util.Objects;

/** Ville d'un pays. */
public record City(long id, String name) {

    public City {
        Objects.requireNonNull(name, "name");
    }
}
