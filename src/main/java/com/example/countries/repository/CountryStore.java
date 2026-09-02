package com.example.countries.repository;

import com.example.countries.model.Country;
import java.util.List;
import java.util.Optional;

/**
 * The persistence port: the only four operations this application actually needs
 * from a country storage.
 *
 * <p><b>Why this interface exists at all,</b> given that
 * {@link CountryRepository} would already work. Two reasons, and both are about
 * who depends on whom.
 *
 * <p>Dependency inversion: the service layer owns the definition of what it
 * needs from storage, and Spring Data adapts to it via {@link JpaCountryStore}.
 * The domain never imports {@code org.springframework.data.*}; replacing JPA
 * with anything else is one new adapter and no edit to the service.
 *
 * <p>Interface segregation, which is the concrete payoff here: this project does
 * not use Mockito, by decision — tests fake collaborators by hand. Hand-writing
 * a fake of {@link org.springframework.data.jpa.repository.JpaRepository} means
 * implementing some forty inherited methods ({@code saveAllAndFlush},
 * {@code findAll(Pageable)}, {@code getReferenceById}, the deprecated
 * {@code getOne}…) in order to exercise two of them. Against this port, an
 * in-memory fake backed by a {@code HashMap} is a dozen lines, so the service's
 * tests stay readable without a mocking framework.
 *
 * <p>Note that {@code findByIso2Code} drops the {@code IgnoreCase} suffix of the
 * Spring Data method name: case-insensitive lookup is the behaviour this port
 * promises, and how an adapter achieves it is not the caller's business.
 * WARNING: any implementation must honour that promise, and must honour the
 * flush contract of {@link #save}, or the idempotency of the import silently
 * breaks.
 */
public interface CountryStore {

    /**
     * Looks up a country by its ISO2 business code, ignoring case.
     * Empty when the country has never been imported.
     */
    Optional<Country> findByIso2Code(String iso2Code);

    /**
     * Inserts or updates the country and returns the managed instance, which is
     * the only one guaranteed to carry the generated id.
     *
     * <p>Contract: the write must reach the database <em>before this method
     * returns</em>, not at transaction commit. That is what allows the caller to
     * wrap this call in a {@code catch (DataIntegrityViolationException)} and
     * handle two concurrent imports of the same code as an update rather than a
     * 500. An implementation that defers the write breaks that contract without
     * failing any compilation.
     */
    Country save(Country country);

    /** All stored countries. Unpaged on purpose: the World Bank has ~300 of them. */
    List<Country> findAll();

    /** Looks up a country by our own surrogate id, as exposed by the REST API. */
    Optional<Country> findById(Long id);
}
