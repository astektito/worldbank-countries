package com.example.countries.exception;

/**
 * Signals that the World Bank API could not be reached, or answered something
 * this service cannot interpret as a country.
 *
 * <p>Why it is separate from {@link CountryNotFoundException}: the two are the
 * same HTTP verb away from each other but mean opposite things. A missing country
 * is the caller's problem and a correct answer from us; an unreachable provider is
 * <em>our</em> failure to fulfil a legitimate request. Collapsing them would make
 * a World Bank outage look like a client typo, which is exactly the confusion the
 * error-handling part of this exercise is about.
 *
 * <p>WARNING: like its sibling, this class contains no HTTP knowledge — no
 * {@code @ResponseStatus}, no mention of 502. The mapping to a status code lives
 * only in {@code GlobalExceptionHandler}, so the transport can change without
 * touching the domain.
 *
 * <p>WARNING: always use the two-argument constructor when wrapping something
 * thrown by {@code RestClient} or by Jackson. The cause is the only place where
 * the real failure survives — a connect timeout, a DNS error, the offset of the
 * malformed JSON. Dropping it produces a 502 whose stack trace stops at our own
 * wrapper and says nothing about why the call failed.
 */
public class ExternalApiException extends RuntimeException {

    public ExternalApiException(String message) {
        super(message);
    }

    public ExternalApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
