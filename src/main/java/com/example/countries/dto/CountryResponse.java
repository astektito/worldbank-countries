package com.example.countries.dto;

import com.example.countries.model.Country;

/**
 * What a client of this service sees when it asks for a country.
 *
 * <p>Why the JPA entity is not serialised directly, even though the fields are
 * nearly identical.
 *
 * <p>The published contract and the storage schema must be free to move
 * independently. Serialising the entity welds them together: renaming a column
 * for the sake of the database breaks every consumer, and adding a field for the
 * sake of the API forces a migration. With this record in between, either side can
 * change while the other stays still — and a future v2 of the API is a second
 * record, not a second table.
 *
 * <p>The entity is also not safe to expose by default. Everything mapped is
 * published, so any column added later — an internal note, an audit timestamp,
 * whatever the next requirement brings — leaks the moment it is declared, with
 * nothing in the code to signal it. Here, publishing a field is a deliberate line
 * of code. And because the entity is what Hibernate manages, serialising it is
 * also what makes a lazy proxy get touched by the JSON writer outside the
 * transaction: a {@code LazyInitializationException} raised in the middle of
 * writing the response, when the status line has already been sent.
 *
 * <p>The static factory exists so the mapping has one home and reads as a method
 * reference at the call sites: {@code .map(CountryResponse::from)}.
 */
public record CountryResponse(
        Long id,
        String iso2Code,
        String iso3Code,
        String name,
        String capitalCity,
        String region,
        String incomeLevel,
        Double latitude,
        Double longitude) {

    /** Projects a stored country onto the public contract. */
    public static CountryResponse from(Country country) {
        return new CountryResponse(
                country.getId(),
                country.getIso2Code(),
                country.getIso3Code(),
                country.getName(),
                country.getCapitalCity(),
                country.getRegion(),
                country.getIncomeLevel(),
                country.getLatitude(),
                country.getLongitude());
    }
}
