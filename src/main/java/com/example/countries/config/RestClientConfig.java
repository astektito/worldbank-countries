package com.example.countries.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the one {@link RestClient} used to talk to the World Bank.
 *
 * <p>Why this bean exists: the requirement is that the external URL and the
 * timeouts are configurable and never hardcoded in a business class. This is
 * where that is enforced. The base URL and both timeouts are read from
 * {@link WorldBankProperties} once, at startup, and baked into a ready-to-use
 * client — so {@code WorldBankCountryClient} receives something it can only call,
 * with no opportunity to know an address or invent a timeout.
 *
 * <p>Why the timeouts matter more than they look: a {@code RestClient} built
 * without them inherits the JDK default, which is <em>no timeout at all</em>. One
 * unresponsive external host would then pin a Tomcat worker thread forever, and
 * enough of those take the whole service down while every local endpoint is
 * still perfectly healthy.
 *
 * <p>WARNING: do not "fix" the two {@code org.springframework.boot.http.client}
 * imports below. Classes with the very same names exist in
 * {@code org.springframework.boot.web.client} and are
 * {@code @Deprecated(since = "3.4.0")}; an IDE auto-import picks them just as
 * happily, the project still compiles, and the deprecation is only noticed later.
 *
 * <p>Note for the reader: with no {@code httpclient5} on the classpath,
 * {@link ClientHttpRequestFactoryBuilder#detect()} resolves to the JDK
 * {@code HttpClient} builder, which honours both the connect and the read
 * timeout. So both settings are effective with zero extra dependencies.
 */
@Configuration
public class RestClientConfig {

    @Bean
    RestClient worldBankRestClient(RestClient.Builder builder, WorldBankProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());
        ClientHttpRequestFactory requestFactory =
                ClientHttpRequestFactoryBuilder.detect().build(settings);
        return builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }
}
