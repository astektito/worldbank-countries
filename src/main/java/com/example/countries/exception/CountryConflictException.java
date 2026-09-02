package com.example.countries.exception;

/**
 * Signals that a country could not be stored because it clashes with what is
 * already in the database.
 *
 * <p>Why it is not an {@link ExternalApiException}. A constraint violation on our
 * own table is our side of the system refusing the write — the provider answered
 * fine. Reporting it as an upstream failure sends an operator to check the World
 * Bank's status page for a problem that lives in our schema, and it tells the
 * client 502 ("the gateway behind me is broken") about a request that no retry
 * against a healthy provider would fix.
 *
 * <p>WARNING on the message: do not claim a cause. A concurrent import of the
 * same code is the most likely reason, but it is not the only one — a value longer
 * than its column, or a field the entity maps {@code nullable = false} arriving
 * empty, produce the very same exception. The message therefore states the effect
 * ("it conflicts with an existing record") and leaves the diagnosis to the cause
 * chain, which is logged. A message that asserts "concurrent import" is simply
 * false half the time, and a false error message costs more than a vague one.
 *
 * <p>Like its two siblings, this class knows nothing about HTTP — no
 * {@code @ResponseStatus}, no mention of 409. The translation to a status code
 * lives only in {@code GlobalExceptionHandler}.
 */
public class CountryConflictException extends RuntimeException {

    private CountryConflictException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * For a country that could not be written under the given code.
     *
     * <p>WARNING: always pass the cause. The underlying
     * {@code DataIntegrityViolationException} names the constraint that was
     * violated, and that name is the only thing that distinguishes a genuine race
     * from a mapping bug once the request is over.
     */
    public static CountryConflictException forCode(String code, Throwable cause) {
        return new CountryConflictException(
                "Country " + code + " could not be stored: it conflicts with an existing record",
                cause);
    }
}
