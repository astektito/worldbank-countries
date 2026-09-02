package com.example.countries.service;

import com.example.countries.model.Country;
import com.example.countries.repository.CountryStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link CountryStore} for tests, written by hand.
 *
 * <p>Why this exists rather than a mock: this project uses no mocking framework,
 * and this class is the payoff of that decision. Because {@code CountryStore} is a
 * four-method port instead of {@code JpaRepository}, faking it takes the few lines
 * below — against the real Spring Data interface it would have meant stubbing some
 * forty inherited methods to exercise two. It also reads better than a stack of
 * {@code when(...).thenReturn(...)}: the behaviour being simulated is stated once,
 * as code, and every test shares the same simulation.
 *
 * <p>WARNING: the id counter is not decoration. {@link #save} assigns an
 * increasing id to rows that do not have one, which is what the identity column
 * does in the real database. Without it, {@code CountryResponse.from} would report
 * {@code id: null} for a freshly imported country and the create path would look
 * broken for a reason that has nothing to do with the code under test — this is
 * the entire reason {@code Country.setId} exists.
 *
 * <p>It deliberately does <em>not</em> simulate the unique constraint: the
 * concurrent-import branch of the service is about what the database does under a
 * race, and asserting it here would only assert that this fake agrees with itself.
 */
public class FakeCountryStore implements CountryStore {

    private final Map<Long, Country> rows = new LinkedHashMap<>();
    private long nextId = 1L;

    @Override
    public Optional<Country> findByIso2Code(String iso2Code) {
        // Case-insensitive, exactly like the derived query it stands in for. A
        // case-sensitive fake here would hide the normalisation bug it is meant
        // to help catch.
        return rows.values().stream()
                .filter(country -> country.getIso2Code() != null
                        && country.getIso2Code().equalsIgnoreCase(iso2Code))
                .findFirst();
    }

    @Override
    public Country save(Country country) {
        if (country.getId() == null) {
            country.setId(nextId++);
        }
        rows.put(country.getId(), country);
        return country;
    }

    @Override
    public List<Country> findAll() {
        return new ArrayList<>(rows.values());
    }

    @Override
    public Optional<Country> findById(Long id) {
        return Optional.ofNullable(rows.get(id));
    }

    /** How many rows are stored — the assertion that proves a re-import updated instead of inserting. */
    public int size() {
        return rows.size();
    }
}
