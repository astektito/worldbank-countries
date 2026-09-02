package com.example.countries.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed binding of the {@code worldbank:} block of {@code application.yaml}.
 *
 * <p>Why it exists: no business class is allowed to know the address of the
 * external API or how long it is willing to wait for it. Those are deployment
 * facts — they change per environment and per network — while the import logic
 * is a business fact that must not be recompiled when a URL moves. Every
 * consumer receives this record injected and reads it; a literal
 * {@code "https://api.worldbank.org/v2"} anywhere else in the codebase is a bug.
 *
 * <p>Why a {@code record}: the configuration is a value, and it is read-only
 * after startup. Constructor binding also makes the object impossible to
 * half-populate, unlike setter binding on a mutable class.
 *
 * <p>Why the timeouts have {@link DefaultValue}s but {@code baseUrl} does not: a
 * missing timeout has a sane answer, a missing base URL does not. WARNING: leaving
 * it out of the annotations is not enough to make that a startup failure — an
 * unset property simply binds to {@code null}, and the service would come up
 * healthy and fail on the first request with a confusing message about a relative
 * URI. The {@code @Validated} on this type plus {@code @NotBlank} below are what
 * actually make it fail fast, at startup, naming the property.
 *
 * <p>The constraint message is written out in English on purpose: Bean Validation
 * resolves its defaults against the platform locale, which is {@code es_EC} on the
 * build machine.
 *
 * <p>KNOWN TRAP: this record only becomes a bean because
 * {@code CountriesApplication} is annotated with
 * {@code @ConfigurationPropertiesScan}. {@code @ConfigurationProperties} alone
 * registers nothing, and the symptom is a
 * {@code NoSuchBeanDefinitionException} at the injection point, which reads like
 * a missing class rather than a missing annotation.
 */
@ConfigurationProperties(prefix = "worldbank")
@Validated
public record WorldBankProperties(
        @NotBlank(message = "worldbank.base-url must be configured") String baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {
}
