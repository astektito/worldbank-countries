package com.example.countries.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.example.countries.exception.CountryNotFoundException;
import com.example.countries.exception.ExternalApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers every response shape the World Bank API is known to return.
 *
 * <p>Why these tests carry the weight of the integration: the parser is the only
 * part of the external call with rules in it, and it is a pure function, so each
 * of these cases is a literal string and an assertion. No Spring context, no
 * network, no HTTP stubbing — which is exactly what makes it affordable to cover
 * the payloads that are awkward to trigger for real, such as an error delivered
 * with status 200 or a proxy's HTML page.
 *
 * <p>The JSON below is verbatim from live {@code curl} calls, trailing space in
 * {@code "Latin America & Caribbean "} included. WARNING: do not tidy these
 * strings. Their oddities are the reason the tests exist, and an abbreviated
 * payload would assert that our idea of the API is self-consistent rather than
 * that we read the real one correctly.
 */
class WorldBankResponseParserTest {

    /** Real response for {@code GET /v2/country/CO?format=json}. */
    private static final String COLOMBIA_BODY = """
            [{"page":1,"pages":1,"per_page":"50","total":1},[{"id":"COL","iso2Code":"CO","name":"Colombia","region":{"id":"LCN","iso2code":"ZJ","value":"Latin America & Caribbean "},"adminregion":{"id":"LAC","iso2code":"XJ","value":"Latin America & Caribbean (excluding high income)"},"incomeLevel":{"id":"UMC","iso2code":"XT","value":"Upper middle income"},"lendingType":{"id":"IBD","iso2code":"XF","value":"IBRD"},"capitalCity":"Bogota","longitude":"-74.082","latitude":"4.60987"}]]
            """;

    /** Real response for {@code GET /v2/country/ZZ?format=json} — arrives with HTTP 200. */
    private static final String UNKNOWN_CODE_BODY = """
            [{"message":[{"id":"120","key":"Invalid value","value":"The provided parameter value is not valid"}]}]
            """;

    /** Real response for {@code GET /v2/country/EUU?format=json} — a regional aggregate. */
    private static final String EUROPEAN_UNION_BODY = """
            [{"page":1,"pages":1,"per_page":"50","total":1},[{"id":"EUU","iso2Code":"EU","name":"European Union","region":{"id":"NA","iso2code":"NA","value":"Aggregates"},"adminregion":{"id":"","iso2code":"","value":""},"incomeLevel":{"id":"NA","iso2code":"NA","value":"Aggregates"},"lendingType":{"id":"","iso2code":"","value":"Aggregates"},"capitalCity":"","longitude":"","latitude":""}]]
            """;

    private final WorldBankResponseParser parser = new WorldBankResponseParser(new ObjectMapper());

    @Test
    @DisplayName("maps every business field of a valid country response")
    void mapsAllFieldsOfValidCountry() {
        WorldBankCountry country = parser.parse(COLOMBIA_BODY, "CO");

        assertThat(country.iso2Code()).isEqualTo("CO");
        // The alpha-3 code comes from the field the API calls "id".
        assertThat(country.iso3Code()).isEqualTo("COL");
        assertThat(country.name()).isEqualTo("Colombia");
        assertThat(country.capitalCity()).isEqualTo("Bogota");
        assertThat(country.incomeLevel()).isEqualTo("Upper middle income");
        assertThat(country.latitude()).isCloseTo(4.60987, within(1e-9));
        assertThat(country.longitude()).isCloseTo(-74.082, within(1e-9));
    }

    @Test
    @DisplayName("reads region from the nested object and strips its trailing space")
    void readsRegionFromNestedObjectTrimmed() {
        WorldBankCountry country = parser.parse(COLOMBIA_BODY, "CO");

        // Two traps in one assertion. The value lives in region.value, one level
        // down - region.asText() would yield "" without any error. And the API
        // really sends "Latin America & Caribbean " with a trailing space, so the
        // expected string here deliberately has none.
        assertThat(country.region()).isEqualTo("Latin America & Caribbean");
        assertThat(country.region()).doesNotEndWith(" ");
    }

    @Test
    @DisplayName("treats an error body served with HTTP 200 as an unknown country")
    void treatsErrorBodyAsCountryNotFound() {
        // THE case of this exercise: the provider reports an invalid code with
        // status 200, so only the shape of the body reveals the failure. The
        // requested code must appear in the message because the error body itself
        // never contains it.
        assertThatThrownBy(() -> parser.parse(UNKNOWN_CODE_BODY, "ZZ"))
                .isInstanceOf(CountryNotFoundException.class)
                .hasMessageContaining("ZZ");
    }

    @Test
    @DisplayName("treats an empty country array as an unknown country")
    void treatsEmptyCountryArrayAsCountryNotFound() {
        String body = """
                [{"page":1,"pages":1,"per_page":"50","total":0},[]]
                """;

        assertThatThrownBy(() -> parser.parse(body, "XX"))
                .isInstanceOf(CountryNotFoundException.class)
                .hasMessageContaining("XX");
    }

    @Test
    @DisplayName("rejects a well-formed envelope that carries no country array")
    void rejectsEnvelopeWithoutCountryArray() {
        // Valid JSON, wrong contract: there is no element at index 1. This must not
        // be reported as "not found" - we did not learn that the country is
        // missing, we failed to understand the answer.
        assertThatThrownBy(() -> parser.parse("[{\"page\":1}]", "CO"))
                .isInstanceOf(ExternalApiException.class);
    }

    @Test
    @DisplayName("wraps a non-JSON body preserving the original cause")
    void wrapsNonJsonBodyKeepingCause() {
        // What a proxy or a gateway in front of the API actually returns when it
        // is unhappy.
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> parser.parse("<html>503 Service Unavailable</html>", "CO"));

        assertThat(thrown).isInstanceOf(ExternalApiException.class);
        // The cause is the whole point: without it the 502 says nothing about why.
        assertThat(thrown.getCause()).isNotNull();
    }

    @Test
    @DisplayName("rejects a country whose code is blank instead of returning it")
    void rejectsCountryWithBlankCode() {
        // Some aggregate rows come back with an empty iso2Code. Blank becomes null
        // in the mapping, and null is not storable - the column is nullable = false.
        // Caught here it is an interpretable failure; passed through it would be an
        // NPE in the service, reported as a bare 500 with no explanation.
        String body = """
                [{"page":1,"pages":1,"per_page":"50","total":1},[{"id":"XKX","iso2Code":"","name":"Kosovo","region":{"id":"ECS","iso2code":"Z7","value":"Europe & Central Asia"},"incomeLevel":{"id":"UMC","iso2code":"XT","value":"Upper middle income"},"capitalCity":"Pristina","longitude":"20.926","latitude":"42.5652"}]]
                """;

        assertThatThrownBy(() -> parser.parse(body, "XK"))
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("country code");
    }

    @Test
    @DisplayName("normalises the blank fields of a regional aggregate to null")
    void normalisesBlankAggregateFieldsToNull() {
        WorldBankCountry country = parser.parse(EUROPEAN_UNION_BODY, "EUU");

        assertThat(country.iso2Code()).isEqualTo("EU");
        assertThat(country.name()).isEqualTo("European Union");
        // Aggregates send "" and not null. An empty string is not an absent value,
        // and 0.0 is a real location, so both have to become null.
        assertThat(country.capitalCity()).isNull();
        assertThat(country.latitude()).isNull();
        assertThat(country.longitude()).isNull();
    }
}
