package com.example.countries.client;

/**
 * A country exactly as the World Bank described it: the model of the boundary.
 *
 * <p>Why this exists instead of parsing straight into the {@code Country} entity.
 * Three reasons, and they are the same reason from three angles.
 *
 * <p>The external format is not ours to control. The World Bank can rename a
 * field, nest one more level, or start sending a number where a string used to
 * be. When that happens the change lands here and in the parser, and the database
 * schema does not move. Parsing directly into the entity would tie our table to
 * someone else's release notes.
 *
 * <p>An entity should never exist half-built. Filling a JPA entity field by field
 * while reading JSON means that, for the duration of the parse, there is an
 * object that looks persistable and is not — and if the payload turns out to be
 * an error body, that half-object has to be thrown away. This record is either
 * fully constructed or never constructed at all.
 *
 * <p>It also marks the direction of the flow. A {@code WorldBankCountry} is
 * something we were <em>told</em>; a {@code Country} is something we <em>decided
 * to keep</em>. The service is where the first becomes the second, and that is
 * precisely where normalisation (upper-casing the code) and the create-or-update
 * decision belong.
 *
 * <p>A {@code record} because this is a value with no identity and no lifecycle:
 * two instances with the same fields are interchangeable, which is exactly what
 * {@code record} equality gives for free.
 *
 * <p>{@code latitude} and {@code longitude} are nullable {@link Double}: regional
 * aggregates come back with empty coordinates, and the parser normalises those to
 * {@code null} rather than to {@code 0.0}, which is a real place in the Gulf of
 * Guinea. Any of the {@code String} fields may likewise be {@code null} — a blank
 * from the provider means "absent", never an empty value worth storing.
 */
public record WorldBankCountry(
        String iso2Code,
        String iso3Code,
        String name,
        String capitalCity,
        String region,
        String incomeLevel,
        Double latitude,
        Double longitude) {
}
