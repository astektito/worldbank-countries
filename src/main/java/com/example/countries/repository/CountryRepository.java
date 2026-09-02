package com.example.countries.repository;

import com.example.countries.model.Country;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the {@code countries} table.
 *
 * <p>Why this interface is not the one the service layer talks to: it is an
 * infrastructure detail. Extending {@link JpaRepository} drags roughly forty
 * inherited methods into whatever depends on it, and every one of them would
 * have to be stubbed to fake it in a test. The service depends on the narrow
 * {@link CountryStore} port instead, and {@link JpaCountryStore} is the only
 * class in the application allowed to see this type.
 *
 * <p>The derived query lives here because deriving it is precisely what Spring
 * Data is good at: no implementation, no JPQL, no chance of a typo in a
 * hand-written query surviving until runtime — the method name is validated
 * against the entity metamodel at context startup.
 */
public interface CountryRepository extends JpaRepository<Country, Long> {

    /**
     * Finds a country by its ISO2 code, case-insensitively.
     *
     * <p>The {@code IgnoreCase} is deliberate, not decorative: the public API
     * accepts {@code ?code=co} and {@code ?code=CO} as the same country. Without
     * it, importing {@code co} after {@code CO} would look like a new country to
     * the service, and the unique constraint on {@code iso2_code} would then
     * reject the insert with a database error instead of performing the update
     * the caller asked for.
     *
     * <p>KNOWN TRAP: this translates to {@code upper(iso2_code) = upper(?)},
     * which cannot use a plain index on the column. Irrelevant at ~300 rows;
     * worth remembering before copying this pattern into a large table.
     */
    Optional<Country> findByIso2CodeIgnoreCase(String iso2Code);
}
