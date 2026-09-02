package com.example.countries.service;

import com.example.countries.dto.CountryResponse;

/**
 * The outcome of an import: the stored country, and whether it was created or
 * updated.
 *
 * <p>Why this type exists at all. The import endpoint has to answer 201 for a new
 * country and 200 for one that already existed, so the status depends on a fact
 * only the service knows — it is the one that looked the row up. There are three
 * ways to get that fact to the controller and two of them are worse.
 *
 * <p>The service could return a {@code ResponseEntity}, which would put HTTP
 * inside the business layer and make the import logic untestable without a web
 * context. Or the controller could query the database again to see whether the
 * row is new, which is both a second round trip and a lie — by then the row
 * exists either way, so the answer is unknowable after the fact.
 *
 * <p>So the service returns a plain domain value that states what happened, and
 * the controller alone decides which status code says it. That is the whole
 * purpose of this record: it carries the one bit of information that crosses the
 * boundary, in a form that mentions no protocol.
 */
public record ImportResult(CountryResponse country, boolean created) {
}
