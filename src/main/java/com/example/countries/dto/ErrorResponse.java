package com.example.countries.dto;

/**
 * The single error body shape of this API.
 *
 * <p>Why one shape for every failure: a client that has to branch on the
 * structure of an error body cannot handle errors generically. Whether the
 * request was malformed, the country unknown, or the provider down, the response
 * carries one human-readable sentence in {@code detail} — and the machine-readable
 * part of the answer is the HTTP status, where it belongs.
 *
 * <p>KNOWN TRAP: this field is a {@code String} and not a {@code List<String>} on
 * purpose. With Bean Validation the natural thing is to hand back the collection
 * of violations, and the body then silently changes shape depending on how many
 * fields happened to be invalid — one error yields a one-element array, and a
 * client written against that breaks the first time two rules fail at once.
 * Declaring the field as a {@code String} makes that impossible by construction:
 * the handler is forced to join the violations into one deterministic sentence.
 *
 * <p>WARNING: no status code field. Duplicating the status in the body invites it
 * to disagree with the real one, and there is no rule for which of the two a
 * client should then believe.
 */
public record ErrorResponse(String detail) {
}
