package com.example.countries.service;

import com.example.countries.client.CountryProvider;
import com.example.countries.client.WorldBankCountry;

/**
 * Hand-written {@link CountryProvider} that returns whatever a test tells it to.
 *
 * <p>Why a fake and not the real client: the rules worth testing — create versus
 * update, code normalisation, letting provider failures travel up — are decisions
 * of {@code CountryService}, and none of them involve a socket. Standing in for
 * the provider here makes those tests instant, deterministic, and independent of
 * whether the World Bank is up. This is the concrete reason
 * {@link CountryProvider} was extracted as a one-method port in the first place.
 *
 * <p>Two knobs, mirroring the two things the port promises to do: return a country,
 * or throw. Setting {@link #failWith} makes every call fail, which is how the
 * not-found and unreachable paths are driven without any network trickery.
 */
public class FakeCountryProvider implements CountryProvider {

    private WorldBankCountry next;
    private RuntimeException failure;
    private String lastRequestedCode;

    /** Makes the next calls return this country. */
    public void willReturn(WorldBankCountry country) {
        this.next = country;
        this.failure = null;
    }

    /**
     * Makes the next calls throw this exception.
     *
     * <p>Typed as {@link RuntimeException} rather than one of the domain
     * exceptions so a test can also drive the {@code ExternalApiException} branch
     * through the very same knob.
     */
    public void failWith(RuntimeException exception) {
        this.failure = exception;
        this.next = null;
    }

    /** The code the service actually asked for — proof that it forwards what it was given. */
    public String lastRequestedCode() {
        return lastRequestedCode;
    }

    @Override
    public WorldBankCountry fetchByCode(String code) {
        this.lastRequestedCode = code;
        if (failure != null) {
            throw failure;
        }
        if (next == null) {
            // Fail loudly rather than returning null: a null here would surface far
            // away as an NPE inside the service and look like a production bug.
            throw new IllegalStateException("FakeCountryProvider was not told what to return");
        }
        return next;
    }
}
