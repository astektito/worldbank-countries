package com.example.countries.exception;

import com.example.countries.dto.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The one place in this project where HTTP status codes are decided.
 *
 * <p>Why centralise it. The alternative is {@code @ResponseStatus} on each
 * exception or a try/catch in each controller method, and both spread the answer
 * to "what does this service return when X happens?" across as many files as
 * there are failures. Here the whole error contract is one table that can be read
 * top to bottom — which is also why the domain exceptions are free of HTTP: they
 * describe what went wrong, this class decides how to say it over the wire.
 *
 * <p>The mapping, and the reasoning behind each row:
 * <ul>
 *   <li>invalid or missing request data — 400, the caller can fix it;</li>
 *   <li>{@link CountryNotFoundException} — 404, a correct answer about an absent
 *       thing, whether the World Bank rejected the code or our own table has no
 *       such id;</li>
 *   <li>{@link CountryConflictException} — 409, the write was refused by our own
 *       database; the request and the provider were both fine;</li>
 *   <li>{@link ExternalApiException} — 502 and not 500, because the request was
 *       fine and <em>we</em> failed to fulfil it on behalf of an upstream
 *       dependency. A 500 would claim the bug is ours; 502 says the gateway
 *       behind us misbehaved, which is what an operator needs to know;</li>
 *   <li>anything else — 500 with a fixed sentence, so that no failure escapes this
 *       format and no internal detail escapes with it.</li>
 * </ul>
 *
 * <p>WARNING on log levels: 4xx are logged at {@code debug} and 5xx at
 * {@code warn}. A client typing a bad country code is not an incident, and logging
 * it at warn level lets anyone fill the log by looping over invalid input — the
 * real 502s then drown in noise. Response bodies and payloads are never logged,
 * only the exception's own message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * The only thing a client is ever told about an unexpected failure. Fixed on
     * purpose: see {@link #handleUnexpected}.
     */
    private static final String UNEXPECTED_ERROR_DETAIL = "An unexpected internal error occurred";

    /**
     * Bean Validation failures on controller parameters, as raised when the
     * controller is annotated {@code @Validated}.
     *
     * <p>The violations are sorted before being joined. Their iteration order is
     * unspecified — it comes out of a {@code Set} — so without sorting the same
     * invalid request could produce two different sentences on two runs, which
     * makes the response impossible to assert on and confusing to read.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .sorted()
                .collect(Collectors.joining("; "));
        log.debug("Rejected invalid request: {}", detail);
        return ResponseEntity.badRequest().body(new ErrorResponse(detail));
    }

    /**
     * The same class of failure, reported by Spring's own method validation.
     *
     * <p>WARNING: this is a safety net, not a duplicate. Since Spring Framework
     * 6.1 a controller <em>without</em> {@code @Validated} still validates its
     * parameters and raises this exception instead of
     * {@link ConstraintViolationException}. Without this handler, removing that
     * one annotation would silently change every 400 body into Spring's default
     * error format — a regression no compiler catches and no test notices unless
     * it asserts the body.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException e) {
        String detail = e.getAllErrors().stream()
                .map(error -> error.getDefaultMessage() == null ? "invalid request" : error.getDefaultMessage())
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining("; "));
        log.debug("Rejected invalid request: {}", detail);
        return ResponseEntity.badRequest().body(new ErrorResponse(detail));
    }

    /** A required query parameter was left out entirely, e.g. {@code POST /import} with no {@code ?code=}. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
        String detail = e.getParameterName() + " is required";
        log.debug("Rejected request with missing parameter: {}", detail);
        return ResponseEntity.badRequest().body(new ErrorResponse(detail));
    }

    /**
     * A path variable or parameter that could not be converted, e.g.
     * {@code GET /api/countries/abc} where a {@code Long} is expected.
     *
     * <p>The message names the parameter and the expectation, and deliberately does
     * not echo the offending value back into the response body.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String detail = e.getName() + " must be a number";
        log.debug("Rejected request with unconvertible parameter: {}", detail);
        return ResponseEntity.badRequest().body(new ErrorResponse(detail));
    }

    /**
     * The write was refused by our own database — 409, not 502 and not 500.
     *
     * <p>409 is the honest status: the request was valid and the provider answered,
     * but the state we already hold is incompatible with storing the result. A 502
     * would blame an upstream that did nothing wrong, and a 500 would claim the
     * server is broken when it is in fact enforcing an invariant correctly.
     *
     * <p>Logged at {@code warn} with the exception, because the cause chain names
     * the violated constraint — which is what separates a harmless race between two
     * imports from a mapping defect that will keep happening.
     */
    @ExceptionHandler(CountryConflictException.class)
    public ResponseEntity<ErrorResponse> handleCountryConflict(CountryConflictException e) {
        log.warn("Could not store country: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
    }

    /** The country does not exist — upstream does not know the code, or we have no such row. */
    @ExceptionHandler(CountryNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCountryNotFound(CountryNotFoundException e) {
        log.debug("Country not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
    }

    /**
     * The upstream provider could not be reached or could not be understood.
     *
     * <p>Logged at {@code warn} <em>with the exception</em> so the cause chain — the
     * connect timeout, the malformed JSON — reaches the log. That chain is the
     * only thing that makes a 502 diagnosable after the fact, and it is exactly
     * what is lost when an exception is logged as a bare message.
     */
    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<ErrorResponse> handleExternalApi(ExternalApiException e) {
        log.warn("External provider failure: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(e.getMessage()));
    }

    /**
     * Last resort for anything not named above.
     *
     * <p>Why it is needed: without it, every unforeseen failure — a
     * {@code NullPointerException}, an unavailable database, a request to a route
     * that does not exist — is rendered by Boot's default error page instead of by
     * this advice. The API would then have two error formats, one documented and
     * one that appears exactly when a client is least able to cope with a surprise.
     * {@link ErrorResponse} claims to be the shape of every error, and this handler
     * is what makes that claim true.
     *
     * <p>WARNING: the 500 body is a fixed sentence and never {@code e.getMessage()}.
     * An unexpected exception's message is written for developers and routinely
     * carries internals — a SQL fragment, a file path, a class name, sometimes the
     * data being processed. The diagnosis belongs in the log, at {@code error} with
     * the full stack trace; the client gets told only that it was our fault.
     *
     * <p>WARNING: catching {@link Exception} here also intercepts Spring's own MVC
     * failures, which resolve <em>before</em> the framework's default handler. Those
     * already carry a correct status (405 for a wrong method, 404 for an unknown
     * route, 415 for an unsupported type), so flattening them all to 500 would turn
     * four accurate client errors into a false server error. They are recognised by
     * the {@link org.springframework.web.ErrorResponse} interface, and their status
     * is preserved — only the body is rewritten into our shape.
     *
     * <p>Spring always prefers the most specific handler, so every mapping above
     * still wins over this one.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse framework) {
            ProblemDetail body = framework.getBody();
            String detail = body.getDetail() != null
                    ? body.getDetail()
                    : HttpStatus.valueOf(framework.getStatusCode().value()).getReasonPhrase();
            log.debug("Rejected request at the framework level: {}", detail);
            return ResponseEntity.status(framework.getStatusCode()).body(new ErrorResponse(detail));
        }
        log.error("Unhandled failure while serving a request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(UNEXPECTED_ERROR_DETAIL));
    }
}
