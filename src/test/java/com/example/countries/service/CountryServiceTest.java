package com.example.countries.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.countries.client.WorldBankCountry;
import com.example.countries.exception.CountryNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The import rules, tested as pure logic.
 *
 * <p>Why there is no Spring here at all: every decision under test — is this
 * country new, under which code is it filed, what happens to a provider failure —
 * is plain Java, and the service reaches the outside world only through two
 * interfaces. Constructing it with two hand-written fakes is therefore enough, and
 * the suite runs in milliseconds with no context, no database and no network. That
 * is the return on injecting ports instead of a {@code JpaRepository} and a
 * {@code RestClient}.
 */
class CountryServiceTest {

    private static final WorldBankCountry COLOMBIA = new WorldBankCountry(
            "CO", "COL", "Colombia", "Bogota",
            "Latin America & Caribbean", "Upper middle income", 4.60987, -74.082);

    private final FakeCountryProvider provider = new FakeCountryProvider();
    private final FakeCountryStore store = new FakeCountryStore();
    private final CountryService service = new CountryService(provider, store);

    @Test
    @DisplayName("reports created on the first import of a country")
    void reportsCreatedOnFirstImport() {
        provider.willReturn(COLOMBIA);

        ImportResult result = service.importCountry("CO");

        assertThat(result.created()).isTrue();
        assertThat(result.country().iso2Code()).isEqualTo("CO");
        assertThat(result.country().name()).isEqualTo("Colombia");
        // The id comes from the store, so a non-null value here also proves the
        // response is built from the saved row and not from the fetched payload.
        assertThat(result.country().id()).isNotNull();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("reports updated and keeps a single row when the same country is imported twice")
    void reportsUpdatedOnReimport() {
        provider.willReturn(COLOMBIA);
        ImportResult first = service.importCountry("CO");

        ImportResult second = service.importCountry("CO");

        assertThat(second.created()).isFalse();
        // The whole point of the idempotent import: one country, one row, same id.
        assertThat(store.size()).isEqualTo(1);
        assertThat(second.country().id()).isEqualTo(first.country().id());
    }

    @Test
    @DisplayName("stores the code upper-cased so co and CO are the same country")
    void normalisesLowercaseCodeToUpperCase() {
        // The provider echoes a lower-case code, which is the shape that would
        // create a duplicate row if it reached the database unnormalised.
        provider.willReturn(new WorldBankCountry(
                "co", "COL", "Colombia", "Bogota",
                "Latin America & Caribbean", "Upper middle income", 4.60987, -74.082));

        ImportResult result = service.importCountry("co");

        assertThat(result.country().iso2Code()).isEqualTo("CO");
        // Filed under the normalised code, so the case-insensitive lookup finds it
        // again on the next import instead of inserting a twin.
        assertThat(store.findByIso2Code("CO")).isPresent();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("lets a not-found from the provider travel up untouched")
    void propagatesCountryNotFoundFromProvider() {
        provider.failWith(CountryNotFoundException.forCode("ZZ"));

        // The service must not translate, wrap or swallow this: the web layer maps
        // it to 404, and catching it here is what would turn an unknown code into
        // a 502 or an empty 200.
        assertThatThrownBy(() -> service.importCountry("ZZ"))
                .isInstanceOf(CountryNotFoundException.class)
                .hasMessageContaining("ZZ");
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("raises not found for an id that was never imported")
    void raisesNotFoundForUnknownId() {
        assertThatThrownBy(() -> service.findById(9999L))
                .isInstanceOf(CountryNotFoundException.class)
                .hasMessageContaining("9999");
    }

    @Test
    @DisplayName("lists nothing before the first import")
    void listsNothingWhenEmpty() {
        assertThat(service.findAll()).isEmpty();
    }
}
