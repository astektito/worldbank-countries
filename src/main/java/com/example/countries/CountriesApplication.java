package com.example.countries;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Single entry point of the microservice.
 *
 * <p>Why this class exists: it anchors the Spring component scan at
 * {@code com.example.countries}. That is the decision that lets every layer
 * (model, repository, config, client, service, controller) be discovered by
 * package convention instead of by hand-written wiring or
 * {@code @ComponentScan} overrides. Moving this class out of the base package
 * would silently stop half the beans from being found.
 *
 * <p>KNOWN TRAP: {@code @ConfigurationPropertiesScan} is not decoration. A
 * {@code record} annotated with {@code @ConfigurationProperties} — such as
 * {@link com.example.countries.config.WorldBankProperties} — is <em>not</em> a
 * bean by virtue of that annotation alone; something has to register it, either
 * an explicit {@code @EnableConfigurationProperties} or this scan. Without it
 * the failure appears far from its cause, as a
 * {@code NoSuchBeanDefinitionException} wherever the properties are injected.
 *
 * <p>It deliberately holds no logic of its own, so there is nothing here worth
 * unit testing: the only assertion that matters about it is "the application
 * context loads", which belongs in an integration test.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CountriesApplication {

    public static void main(String[] args) {
        SpringApplication.run(CountriesApplication.class, args);
    }
}
