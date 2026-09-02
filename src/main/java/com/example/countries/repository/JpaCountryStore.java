package com.example.countries.repository;

import com.example.countries.model.Country;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The JPA side of the {@link CountryStore} port.
 *
 * <p>Why a class that mostly forwards calls: it is the seam. This adapter is the
 * single place where the narrow port meets Spring Data, so the framework's types
 * stop here and never reach the service layer. Deleting it and injecting
 * {@link CountryRepository} directly would work today and cost the testability
 * that {@link CountryStore} was introduced for.
 *
 * <p>It holds no business logic by design. Anything that looks like a decision —
 * normalising a code, choosing between insert and update, turning an absent row
 * into a 404 — belongs in the service, where it can be tested without a
 * database. The one thing this class does decide is <em>when</em> the write hits
 * the database, and that is a persistence concern; see {@link #save}.
 *
 * <p>{@code @Repository} rather than {@code @Component} for what it adds:
 * Spring's exception translation, which turns Hibernate-specific failures (a
 * violation of {@code uk_countries_iso2_code}, for instance) into
 * {@code DataAccessException} subtypes, so the error handling can be written
 * against Spring's hierarchy instead of Hibernate's.
 */
@Repository
public class JpaCountryStore implements CountryStore {

    private final CountryRepository countryRepository;

    public JpaCountryStore(CountryRepository countryRepository) {
        this.countryRepository = countryRepository;
    }

    @Override
    public Optional<Country> findByIso2Code(String iso2Code) {
        return countryRepository.findByIso2CodeIgnoreCase(iso2Code);
    }

    /**
     * KNOWN TRAP - saveAndFlush, NOT save. With plain save() Hibernate defers the
     * INSERT until commit, so a unique index violation on iso2_code is raised AFTER
     * the service's try/catch has already returned, and it surfaces as an
     * UnexpectedRollbackException from the transaction interceptor instead of the
     * DataIntegrityViolationException the service is waiting for.
     */
    @Override
    public Country save(Country country) {
        return countryRepository.saveAndFlush(country);
    }

    @Override
    public List<Country> findAll() {
        return countryRepository.findAll();
    }

    @Override
    public Optional<Country> findById(Long id) {
        return countryRepository.findById(id);
    }
}
