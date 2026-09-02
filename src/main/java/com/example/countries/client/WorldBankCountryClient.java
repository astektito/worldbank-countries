package com.example.countries.client;

import com.example.countries.exception.ExternalApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The World Bank implementation of {@link CountryProvider}: the only class in the
 * project that performs an outbound HTTP call.
 *
 * <p>Why the transport and the parsing are two classes. This one owns everything
 * that can only be exercised against a real socket — the request, the timeouts,
 * the failure modes of a network — and hands the body straight to
 * {@link WorldBankResponseParser}, which owns everything that can be exercised
 * with a string literal. That split is what allows the interesting rules (an
 * error arriving as HTTP 200, blanks, nested fields) to be covered by fast unit
 * tests, while this class stays thin enough that reading it is enough to trust
 * it. There is no parsing here and no {@link com.fasterxml.jackson.databind.JsonNode}
 * anywhere in this file.
 *
 * <p>WARNING: {@code parser.parse(..)} is called <em>outside</em> the try block on
 * purpose. The parser throws the domain exceptions, and they must travel up
 * untouched. Widening the try to cover it — or adding a
 * {@code catch (RuntimeException)} — would swallow
 * {@code CountryNotFoundException} and reissue it as an
 * {@link ExternalApiException}, turning the 404 for an unknown code into a 502
 * and breaking the single most important case of the exercise.
 */
@Component
public class WorldBankCountryClient implements CountryProvider {

    private static final Logger log = LoggerFactory.getLogger(WorldBankCountryClient.class);

    private final RestClient restClient;
    private final WorldBankResponseParser parser;

    public WorldBankCountryClient(RestClient restClient, WorldBankResponseParser parser) {
        this.restClient = restClient;
        this.parser = parser;
    }

    @Override
    public WorldBankCountry fetchByCode(String code) {
        // Logs carry the code being requested and nothing else. The full response
        // body never reaches the log: it is unbounded in size, it would bury the
        // signal, and on a different endpoint of the same provider it could
        // contain data we have no business persisting in a log file. Same reason
        // the URL is not logged - a base URL is where credentials would live if
        // this API ever needed them.
        log.debug("Requesting country {} from the World Bank API", code);

        String body;
        try {
            // URI variable, never string concatenation: the value is escaped by
            // the client, so a code like "../countries" cannot rewrite the path.
            body = restClient.get()
                    .uri("/country/{code}?format=json", code)
                    .retrieve()
                    .body(String.class);
        } catch (ResourceAccessException e) {
            // The I/O failures: connect and read timeouts, DNS failure, connection
            // refused. This is the "external API is down" case, and it has to be
            // caught before its siblings - see the note below.
            log.warn("World Bank API unreachable while requesting {}: {}", code, e.getMessage());
            throw new ExternalApiException("World Bank API is unreachable", e);
        } catch (RestClientResponseException e) {
            // Reached and answered, but with a 4xx/5xx status.
            log.warn("World Bank API answered HTTP {} for {}", e.getStatusCode().value(), code);
            throw new ExternalApiException(
                    "World Bank API answered HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            // WARNING: this catch order is load-bearing. RestClientException is the
            // parent of both branches above, so it must come last - with it first
            // the compiler rejects the unreachable catches, which is the good case.
            // The dangerous mistake is subtler: RestClientResponseException before
            // ResourceAccessException still compiles, and every timeout would then
            // be reported as "answered HTTP ...", which is the opposite of true.
            log.warn("World Bank API call failed for {}: {}", code, e.getMessage());
            throw new ExternalApiException("World Bank API call failed", e);
        }

        return parser.parse(body, code);
    }
}
