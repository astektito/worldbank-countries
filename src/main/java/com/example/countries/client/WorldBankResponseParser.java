package com.example.countries.client;

import com.example.countries.exception.CountryNotFoundException;
import com.example.countries.exception.ExternalApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns a raw World Bank response body into a {@link WorldBankCountry}, or into
 * the right exception.
 *
 * <p>Why this is its own class and not three lines inside the HTTP client: it is
 * the only part of the integration with real rules in it, and those rules are
 * where this exercise is actually decided. Splitting it out makes them testable
 * as a pure function — {@code String} in, value or exception out, no network, no
 * Spring context, no HTTP stubbing. That is why the seven parser tests can cover
 * every payload shape the provider is known to produce, including the ones that
 * are hard to reproduce over a real connection.
 *
 * <p>KNOWN TRAP - the World Bank reports an unknown country code with HTTP 200.
 * The body is {@code [{"message":[{"id":"120", ...}]}]}: a perfectly valid
 * response, status 200, no country in it. Checking the status code therefore
 * proves nothing; the shape of the body is the only signal. That check is the
 * first branch below, before anything assumes there is an element at index 1.
 *
 * <p><b>Isolation rule:</b> {@link JsonNode} does not leave this class. Not as a
 * parameter type, not as a return type, not inside an exception. Everything
 * downstream — the client, the service, the controller — speaks only
 * {@link WorldBankCountry}. If Jackson types ever appear outside this file, the
 * boundary has leaked and the provider's format has become the service's format.
 *
 * <p>The envelope is read as {@code List<JsonNode>} rather than into a typed
 * object because it is heterogeneous: index 0 is an object (pagination, or the
 * error) and index 1 is an array of countries. No single POJO describes that
 * shape honestly, and pretending otherwise is how the error branch gets lost.
 */
@Component
public class WorldBankResponseParser {

    private final ObjectMapper objectMapper;

    public WorldBankResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses one country out of a World Bank response body.
     *
     * @param rawBody       the response body, exactly as received
     * @param requestedCode the code the caller asked for. It exists only to build
     *                      the not-found message: when the provider rejects a
     *                      code, its error body does not echo the code back, so
     *                      this is the only place the value still lives.
     * @throws CountryNotFoundException if the body says the code is unknown, or
     *                                  carries no country at all
     * @throws ExternalApiException     if the body is not JSON, is JSON of a shape
     *                                  this contract does not cover, or describes a
     *                                  country with no code or no name
     */
    public WorldBankCountry parse(String rawBody, String requestedCode) {
        // A null or empty body is not a Jackson problem - readValue(null, ..)
        // would throw IllegalArgumentException, which nothing maps - so it is
        // rejected here as what it is: an answer we cannot interpret.
        if (rawBody == null || rawBody.isBlank()) {
            throw new ExternalApiException("World Bank API returned an empty body");
        }

        List<JsonNode> envelope;
        try {
            envelope = objectMapper.readValue(rawBody, new TypeReference<List<JsonNode>>() { });
        } catch (JsonProcessingException e) {
            // The cause carries the parse location and the offending token; a 502
            // without it is undebuggable. This is also the branch that catches an
            // HTML error page served by a proxy in front of the API.
            throw new ExternalApiException("World Bank API returned a body that is not valid JSON", e);
        }

        // Branch order is load-bearing. The error envelope has a single element,
        // so testing for it first is what keeps the "no index 1" branch below
        // from stealing the case and reporting a client typo as a 502.
        if (!envelope.isEmpty() && envelope.get(0).has("message")) {
            throw CountryNotFoundException.forCode(requestedCode);
        }

        if (envelope.size() < 2 || !envelope.get(1).isArray()) {
            throw new ExternalApiException("World Bank API returned an unexpected payload shape");
        }

        JsonNode countries = envelope.get(1);
        if (countries.isEmpty()) {
            // Valid, well-formed, and simply carrying no country: the provider
            // answers this way for some codes instead of the message envelope.
            throw CountryNotFoundException.forCode(requestedCode);
        }

        JsonNode country = countries.get(0);

        // KNOWN TRAP: these two fields are the ones mapped nullable = false on the
        // entity, and text() normalises a blank to null - so a payload with an
        // empty code or name would pass every branch above and only fail later,
        // as an NPE inside the service (a bare 500) or as a constraint violation
        // at flush time. Rejecting them here is what turns "the provider sent us
        // something unusable" into the one exception that means exactly that,
        // while the boundary still has the context to say which field was missing.
        String iso2Code = text(country, "iso2Code");
        if (iso2Code == null) {
            throw new ExternalApiException("World Bank payload is missing the country code");
        }
        String name = text(country, "name");
        if (name == null) {
            throw new ExternalApiException("World Bank payload is missing the country name");
        }

        return new WorldBankCountry(
                iso2Code,
                // KNOWN TRAP: the alpha-3 code lives in the field the API calls
                // "id". There is no "iso3Code" field in the payload at all, so
                // reading one by that name silently yields null.
                text(country, "id"),
                name,
                text(country, "capitalCity"),
                // KNOWN TRAP: region and incomeLevel are nested objects, not
                // strings. country.get("region").asText() compiles, never throws,
                // and returns "" for an ObjectNode - a null region with no error
                // anywhere. The value we want is one level down, in "value".
                nestedText(country, "region", "value"),
                nestedText(country, "incomeLevel", "value"),
                decimal(country, "latitude"),
                decimal(country, "longitude"));
    }

    /**
     * Reads a text field, normalising every flavour of "nothing" to {@code null}.
     *
     * <p>This one helper settles two separate traps, which is why every field goes
     * through it. First, aggregates send {@code ""} rather than {@code null} for
     * {@code capitalCity} and the coordinates, and an empty string stored in the
     * database is not the same fact as an absent value. Second, the provider
     * pads some values with trailing whitespace — {@code "Latin America &
     * Caribbean "} is what it really returns — and an invisible character at the
     * end of a string breaks every later comparison for reasons no one can see.
     */
    private String text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String raw = value.asText();
        return raw.isBlank() ? null : raw.trim();
    }

    /** Reads {@code parent.field.nestedField}, tolerating an absent intermediate node. */
    private String nestedText(JsonNode parent, String field, String nestedField) {
        JsonNode child = parent.get(field);
        return child == null ? null : text(child, nestedField);
    }

    /**
     * Reads a coordinate sent as a string.
     *
     * <p>WARNING: the conversion is {@code Double.valueOf} and must stay that way.
     * {@code NumberFormat} and {@code DecimalFormat} are locale sensitive, and the
     * default locale of this machine is {@code es_EC}, where the comma is the
     * decimal separator and the dot a grouping symbol: those classes read
     * {@code "-74.082"} as {@code -74082}. The failure is silent, the value is
     * plausible, and the country lands thousands of kilometres away.
     *
     * <p>An unparseable coordinate yields {@code null} instead of failing the whole
     * import: a country is still worth storing without its map pin, and the
     * blank case ({@code ""} from aggregates) has already become {@code null}
     * in {@link #text}, which is what keeps {@code Double.valueOf} from ever
     * seeing an empty string.
     */
    private Double decimal(JsonNode parent, String field) {
        String raw = text(parent, field);
        if (raw == null) {
            return null;
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
