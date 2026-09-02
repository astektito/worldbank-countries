package com.example.countries.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.countries.client.CountryProvider;
import com.example.countries.client.WorldBankCountry;
import com.example.countries.exception.CountryNotFoundException;
import com.example.countries.service.FakeCountryProvider;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract: status codes and error bodies, end to end through the real
 * Spring stack.
 *
 * <p>Why the full context and not a slice. What is being verified here is exactly
 * the part that only Spring can produce — that {@code @Validated} turns a bad code
 * into a {@code ConstraintViolationException}, that the advice maps it to 400, that
 * the service's 201-versus-200 decision survives serialisation. A test that stubbed
 * the framework would assert our belief about Spring rather than Spring's actual
 * behaviour, and every trap in this project so far has been in that gap.
 *
 * <p>Only the outermost dependency is replaced: {@link CountryProvider}, the one
 * thing that would otherwise open a socket. Everything below it — controller,
 * validation, service, transaction, JPA, H2 — is the real thing, so these tests
 * also cover the entity mapping and the unique constraint for free.
 *
 * <p>WARNING: the context is cached and shared across the methods of this class,
 * so the database is not reset between them. Each test therefore uses its own
 * country code. Reusing one code across two tests would make them pass or fail
 * depending on execution order, which is the classic way an integration suite
 * becomes untrustworthy.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CountryControllerTest {

    /**
     * Replaces the real World Bank client with the hand-written fake.
     *
     * <p>{@code @Primary} rather than a bean-name override: it is the mechanism that
     * does not depend on the production bean being absent, so the real client stays
     * in the context (proving it still wires) while nothing can call it. Combined
     * with the discard-port base URL in {@code src/test/resources/application.yaml},
     * a leaked real call cannot reach the network in any case.
     */
    @TestConfiguration
    static class FakeProviderConfiguration {

        @Bean
        @Primary
        FakeCountryProvider fakeCountryProvider() {
            return new FakeCountryProvider();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeCountryProvider provider;

    @BeforeEach
    void resetProvider() {
        provider.willReturn(country("PE", "PER", "Peru", "Lima"));
    }

    @Test
    @DisplayName("answers 201 on the first import and 200 on the second")
    void importsThenUpdates() throws Exception {
        provider.willReturn(country("BR", "BRA", "Brazil", "Brasilia"));

        mockMvc.perform(post("/api/countries/import").param("code", "BR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.iso2Code").value("BR"))
                .andExpect(jsonPath("$.name").value("Brazil"))
                .andExpect(jsonPath("$.id").isNumber());

        // Same code again: the row already exists, so the status drops to 200. This
        // is the idempotency of the endpoint expressed as a status code.
        mockMvc.perform(post("/api/countries/import").param("code", "BR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iso2Code").value("BR"));
    }

    @Test
    @DisplayName("answers 404 when the provider does not know the code")
    void answersNotFoundForUnknownCode() throws Exception {
        provider.failWith(CountryNotFoundException.forCode("ZZ"));

        // The provider reports this over HTTP 200 in real life; by the time it
        // reaches here it is a domain exception, and the advice owns the status.
        mockMvc.perform(post("/api/countries/import").param("code", "ZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Country ZZ not found"));
    }

    @Test
    @DisplayName("answers 400 with the exact English message for a malformed code")
    void answersBadRequestForMalformedCode() throws Exception {
        mockMvc.perform(post("/api/countries/import").param("code", "1"))
                .andExpect(status().isBadRequest())
                // Asserted verbatim: the body is part of the contract, and this is
                // the single sentence the joined-and-sorted violations must produce.
                .andExpect(jsonPath("$.detail").value("code must be 2 or 3 alphabetic characters"));
    }

    @Test
    @DisplayName("keeps validation messages in English even when the client asks for es-EC")
    void keepsValidationMessagesInEnglishUnderSpanishLocale() throws Exception {
        // The default locale of the build machine is es_EC, and Bean Validation
        // resolves its own messages against the request locale. Every constraint in
        // the controller therefore spells its message out, and this test is what
        // holds that: drop one message= and the body silently turns Spanish.
        mockMvc.perform(post("/api/countries/import")
                        .param("code", "1")
                        .locale(Locale.forLanguageTag("es-EC")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("code must be 2 or 3 alphabetic characters"));
    }

    @Test
    @DisplayName("answers 404 for an id that does not exist")
    void answersNotFoundForUnknownId() throws Exception {
        mockMvc.perform(get("/api/countries/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Country 9999 not found"));
    }

    @Test
    @DisplayName("answers 400 when the code parameter is missing entirely")
    void answersBadRequestWhenCodeIsMissing() throws Exception {
        mockMvc.perform(post("/api/countries/import"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("code is required"));
    }

    @Test
    @DisplayName("lists imported countries as a JSON array")
    void listsImportedCountries() throws Exception {
        provider.willReturn(country("JP", "JPN", "Japan", "Tokyo"));
        mockMvc.perform(post("/api/countries/import").param("code", "JP"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/countries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.iso2Code == 'JP')].name").value("Japan"));
    }

    private static WorldBankCountry country(String iso2, String iso3, String name, String capital) {
        return new WorldBankCountry(iso2, iso3, name, capital, "Region", "Income level", 1.0, 2.0);
    }
}
