package com.example.countries.client;

import com.example.countries.exception.CountryNotFoundException;
import com.example.countries.exception.ExternalApiException;

/**
 * The port through which this service obtains country data from the outside
 * world.
 *
 * <p>Why the service depends on this interface instead of on
 * {@link WorldBankCountryClient}: two independent reasons.
 *
 * <p>Testability. This project uses no mocking framework by decision, so
 * collaborators are faked by hand — and a fake of this port is three lines,
 * because there is exactly one method to implement. The service's tests then run
 * with no network, no Spring context and no HTTP stubbing, which is what makes it
 * viable to test the import rules (create vs. update, normalisation, error
 * translation) as pure logic.
 *
 * <p>Dependency inversion. The service states what it needs — "give me a country
 * for this code" — and the World Bank adapter conforms to it. Swapping the data
 * provider, or adding a cached one in front, is a new implementation of this
 * interface and not one line changed in {@code CountryService}. One method, per
 * Interface Segregation: the port is as narrow as the need.
 *
 * <p>Note that nothing in this signature mentions HTTP, JSON, status codes or
 * Jackson. That absence is the point — it is what keeps the transport an
 * implementation detail.
 */
public interface CountryProvider {

    /**
     * Returns the country the provider knows under the given ISO code, accepting
     * either the alpha-2 or the alpha-3 form in any case.
     *
     * <p>This is the failure contract the web layer maps to status codes, and
     * implementations must respect it:
     * <ul>
     *   <li>{@link CountryNotFoundException} — the provider was reached and
     *       answered clearly that this code is not a country it knows;</li>
     *   <li>{@link ExternalApiException} — the provider could not be reached, or
     *       answered something that cannot be interpreted as a country.</li>
     * </ul>
     * WARNING: the distinction is load-bearing. Reporting an unknown code as an
     * {@code ExternalApiException} turns a client mistake into a 502 and makes
     * the service look broken when it is working correctly.
     *
     * @param code ISO 3166-1 alpha-2 or alpha-3 country code
     * @return the country as the provider described it, never {@code null}
     */
    WorldBankCountry fetchByCode(String code);
}
