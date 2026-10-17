package com.wtc.reference.api;

import com.wtc.reference.domain.City;

/** Représentation JSON d'une ville (schéma {@code City}). */
public record CityResponse(long id, String name) {

    static CityResponse from(City c) {
        return new CityResponse(c.id(), c.name());
    }
}
