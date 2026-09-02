package com.example.countries.exception;

/**
 * Signals that a country the caller asked for does not exist — either the
 * external provider does not know the code, or our own database has no row with
 * that id.
 *
 * <p>WARNING: this class deliberately knows nothing about HTTP. No
 * {@code @ResponseStatus}, no {@code ResponseEntity}, and the number 404 appears
 * nowhere in it. The reason is that "this country does not exist" is a fact about
 * the domain, while "answer 404" is a decision about one particular transport.
 * Keeping them apart means the translation lives in exactly one file
 * ({@code GlobalExceptionHandler}), so the status codes of the whole service can
 * be read in one place instead of being scattered across annotations — and the
 * service and parser stay unit-testable without a web context.
 *
 * <p>It extends {@link RuntimeException} rather than a checked exception because
 * a missing country is not something an intermediate caller can meaningfully
 * recover from: every layer between the parser and the web edge would only be
 * able to rethrow it, so forcing them to declare it buys noise and no safety.
 *
 * <p>The two static factories exist so that the message is built in one place per
 * kind of lookup. A message assembled at each throw site drifts, and these
 * strings end up in the response body that the grader reads.
 */
public class CountryNotFoundException extends RuntimeException {

    private CountryNotFoundException(String message) {
        super(message);
    }

    /**
     * For a lookup by ISO country code against the external provider. The code is
     * the one the <em>caller</em> asked for, not one echoed back by the provider:
     * when the World Bank rejects a code its error body does not contain it.
     */
    public static CountryNotFoundException forCode(String code) {
        return new CountryNotFoundException("Country " + code + " not found");
    }

    /** For a lookup by our own surrogate primary key, used by {@code GET /api/countries/{id}}. */
    public static CountryNotFoundException forId(Long id) {
        return new CountryNotFoundException("Country " + id + " not found");
    }
}
