package com.example.countries.controller;

import com.example.countries.dto.CountryResponse;
import com.example.countries.service.CountryService;
import com.example.countries.service.ImportResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP edge of the service: the only class that knows this application is
 * reachable over a network.
 *
 * <p>Why it is this thin. Every method here does the same three things — read the
 * request, call {@link CountryService}, choose a status code — and nothing else.
 * No validation logic written by hand, no try/catch, no database, no knowledge of
 * the World Bank. That is what keeps the rules testable without a web context and
 * the status codes readable in one sitting.
 *
 * <p>WARNING: {@code @Validated} on the class is not decoration. It is what
 * activates method-level validation for the constraints on the parameters below,
 * so a bad {@code code} is rejected before the service is ever called, and the
 * failure arrives as a {@code ConstraintViolationException} the handler maps to
 * 400. Remove it and the annotations become silent comments — the endpoint would
 * happily forward {@code code=1} to the provider and answer 404 or 502 instead of
 * 400. The handler covers that regression with a second mapping, but the correct
 * behaviour depends on this annotation being here.
 */
@RestController
@RequestMapping("/api/countries")
@Validated
public class CountryController {

    private final CountryService service;

    public CountryController(CountryService service) {
        this.service = service;
    }

    /**
     * Imports a country from the World Bank, or refreshes the one already stored.
     *
     * <p>201 when the country did not exist, 200 when it did. The status is the
     * only difference between the two outcomes, which is why the service reports
     * {@link ImportResult#created()} instead of the controller guessing.
     *
     * <p>WARNING: every {@code message} is written out in English on purpose. The
     * default Bean Validation messages are resolved against the platform locale,
     * which on this machine is {@code es_EC}, so leaving them out would produce a
     * Spanish error body in an otherwise English API.
     */
    @PostMapping("/import")
    public ResponseEntity<CountryResponse> importCountry(
            @RequestParam
            @NotBlank(message = "code is required")
            @Pattern(regexp = "^[A-Za-z]{2,3}$",
                    message = "code must be 2 or 3 alphabetic characters")
            String code) {
        ImportResult result = service.importCountry(code);
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.country());
    }

    /**
     * Lists every stored country.
     *
     * <p>Returns the list itself rather than wrapping it: an empty database is not
     * an error, and serialising an empty list already yields {@code []} with a 200,
     * which is what a client iterating over the result expects. A 404 here would
     * force every caller to special-case "no countries yet".
     */
    @GetMapping
    public List<CountryResponse> findAll() {
        return service.findAll();
    }

    /**
     * Returns one country by its numeric id, or 404.
     *
     * <p>The 404 is raised by the service as a domain exception, not decided here.
     * A non-numeric id never reaches the service at all — Spring fails to bind it
     * and the handler turns that into a 400.
     */
    @GetMapping("/{id}")
    public CountryResponse findById(@PathVariable Long id) {
        return service.findById(id);
    }
}
