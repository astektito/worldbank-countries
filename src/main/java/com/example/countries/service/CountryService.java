package com.example.countries.service;

import com.example.countries.client.CountryProvider;
import com.example.countries.client.WorldBankCountry;
import com.example.countries.dto.CountryResponse;
import com.example.countries.exception.CountryConflictException;
import com.example.countries.exception.CountryNotFoundException;
import com.example.countries.model.Country;
import com.example.countries.repository.CountryStore;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where the import is actually decided: fetch, normalise, create or update.
 *
 * <p>Why this layer exists between the controller and the two ports. It is the
 * only place that holds a rule rather than a mechanism — the controller merely
 * translates HTTP, the client merely speaks to a socket, the store merely writes
 * rows. Whether a country is new, which code it is filed under, and what happens
 * when two imports race, are all decided here.
 *
 * <p>It depends on {@link CountryProvider} and {@link CountryStore}, both
 * interfaces, both injected through the constructor and held {@code final}. That
 * is what lets these rules be tested with two hand-written fakes and no mocking
 * framework, no database and no network.
 *
 * <p>WARNING: no HTTP, no Jackson, no {@code ResponseEntity} and no status code
 * appears in this file. The exceptions thrown from the ports are allowed straight
 * through — translating them is the job of the single
 * {@code GlobalExceptionHandler}, and catching them here to "handle" them would
 * scatter that decision across the codebase.
 *
 * <p>KNOWN LIMITATION: {@code @Transactional} on {@link #importCountry} opens the
 * database transaction <em>before</em> the outbound HTTP call, so a slow provider
 * holds a connection from the pool for the duration of the request. It is bounded
 * by the client's read timeout and harmless at this scale, but it does not belong
 * in a service under load: the fix is to fetch outside the transaction and keep
 * only the read-and-write in it, which means splitting this method in two so that
 * the proxy boundary falls between them. Left as is deliberately — the change is
 * worth doing with tests around the transaction boundary, not in passing.
 */
@Service
public class CountryService {

    private final CountryProvider provider;
    private final CountryStore store;

    public CountryService(CountryProvider provider, CountryStore store) {
        this.provider = provider;
        this.store = store;
    }

    /**
     * Imports the country under the given code, creating it or refreshing the row
     * that is already there.
     *
     * <p>The operation is idempotent by design: calling it twice with the same code
     * leaves one row, updated. That is why the lookup happens on the normalised
     * code and not on whatever the caller typed.
     *
     * @return the stored country plus whether this call created it
     */
    @Transactional
    public ImportResult importCountry(String code) {
        // Deliberately not wrapped: CountryNotFoundException and
        // ExternalApiException from the provider are already the right answers.
        WorldBankCountry fetched = provider.fetchByCode(code);

        // KNOWN TRAP: normalise the code the API returned, not the one the caller
        // sent. Both "CO" and "COL" are accepted by the provider and both describe
        // the same country, whose iso2Code is "CO" - filing the row under the
        // requested string would store Colombia twice, once as CO and once as COL.
        // Locale.ROOT because the default locale is not guaranteed to upper-case
        // ASCII the way ISO codes require (a Turkish locale turns "i" into "İ").
        String normalised = fetched.iso2Code().toUpperCase(Locale.ROOT);

        Optional<Country> existing = store.findByIso2Code(normalised);
        boolean created = existing.isEmpty();
        Country country = existing.orElseGet(Country::new);
        apply(country, fetched, normalised);

        try {
            Country saved = store.save(country);
            return new ImportResult(CountryResponse.from(saved), created);
        } catch (DataIntegrityViolationException e) {
            // The likeliest cause is a concurrent request that inserted the same
            // code between our lookup and our write, which the unique constraint
            // catches - but it is not the only one, so the exception thrown here
            // does not claim it. A value too long for its column, or a mapped
            // not-null field arriving empty, land in this very same catch.
            // KNOWN TRAP: do NOT retry or re-read here. Once a constraint violation
            // has been raised the transaction is already marked rollback-only, so
            // any further read or save inside it dies at commit time with an
            // UnexpectedRollbackException that hides this cause entirely. The only
            // safe move is to translate and let the transaction end.
            throw CountryConflictException.forCode(code, e);
        }
    }

    /** Every country stored so far. Empty list, never null, when nothing was imported yet. */
    @Transactional(readOnly = true)
    public List<CountryResponse> findAll() {
        return store.findAll().stream().map(CountryResponse::from).toList();
    }

    /**
     * One country by our own surrogate id.
     *
     * @throws CountryNotFoundException if no row has that id
     */
    @Transactional(readOnly = true)
    public CountryResponse findById(Long id) {
        return store.findById(id)
                .map(CountryResponse::from)
                .orElseThrow(() -> CountryNotFoundException.forId(id));
    }

    /**
     * Copies the boundary model onto the entity.
     *
     * <p>Kept private and separate so that the create and the update path cannot
     * drift: a field added to {@link WorldBankCountry} and forgotten in one of two
     * inlined copies would produce a country that is complete when first imported
     * and half-empty when refreshed.
     */
    private void apply(Country target, WorldBankCountry source, String normalisedIso2Code) {
        target.setIso2Code(normalisedIso2Code);
        target.setIso3Code(source.iso3Code());
        target.setName(source.name());
        target.setCapitalCity(source.capitalCity());
        target.setRegion(source.region());
        target.setIncomeLevel(source.incomeLevel());
        target.setLatitude(source.latitude());
        target.setLongitude(source.longitude());
    }
}
