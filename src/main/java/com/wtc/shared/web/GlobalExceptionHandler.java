package com.wtc.shared.web;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import com.wtc.shared.error.RateLimitedException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Convertit toute exception en {@code application/problem+json} (RFC 9457) avec un {@code code}
 * stable. Aucune trace technique n'est jamais renvoyée au client ; les erreurs inattendues sont
 * journalisées avec l'identifiant de requête.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Problems problems;

    public GlobalExceptionHandler(Problems problems) {
        this.problems = problems;
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<Object> handleRateLimited(RateLimitedException ex) {
        ProblemDetail body = problems.of(ex.code(), ex.getMessage());
        return ResponseEntity.status(body.getStatus())
                .header("Retry-After", String.valueOf(ex.retryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<Object> handleDomain(DomainException ex) {
        ProblemDetail body = problems.of(ex.code(), ex.getMessage(), ex.violations());
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex) {
        ProblemDetail body =
                problems.of(ErrorCode.UNAUTHENTICATED, "Authentification requise ou invalide.");
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail body =
                problems.of(ErrorCode.ACCESS_DENIED, "Droit insuffisant pour cette opération.");
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        LOG.error("Erreur inattendue (requestId={})", RequestIdFilter.current(), ex);
        ProblemDetail body =
                problems.of(ErrorCode.INTERNAL_ERROR, "Une erreur interne est survenue.");
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<FieldViolation> violations =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(
                                e ->
                                        new FieldViolation(
                                                e.getField(), e.getCode(), e.getDefaultMessage()))
                        .toList();
        ProblemDetail body =
                problems.of(
                        ErrorCode.VALIDATION_FAILED, "Certains champs sont invalides.", violations);
        return ResponseEntity.status(body.getStatus()).headers(headers).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<FieldViolation> violations =
                ex.getParameterValidationResults().stream()
                        .flatMap(
                                result -> {
                                    String field =
                                            Objects.requireNonNullElse(
                                                    result.getMethodParameter().getParameterName(),
                                                    "parameter");
                                    return result.getResolvableErrors().stream()
                                            .map(
                                                    error ->
                                                            new FieldViolation(
                                                                    field,
                                                                    firstCode(error),
                                                                    error.getDefaultMessage()));
                                })
                        .toList();
        ProblemDetail body =
                problems.of(
                        ErrorCode.VALIDATION_FAILED,
                        "Certains paramètres sont invalides.",
                        violations);
        return ResponseEntity.status(body.getStatus()).headers(headers).body(body);
    }

    /**
     * Toutes les erreurs MVC standard (404, 405, 415, corps illisible…) gardent leur statut mais
     * reçoivent le code applicatif.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        ErrorCode code = codeFor(statusCode);
        String detail =
                body instanceof ProblemDetail pd && pd.getDetail() != null
                        ? pd.getDetail()
                        : code.title();
        ProblemDetail problem = problems.of(code, detail);
        return ResponseEntity.status(statusCode).headers(headers).body(problem);
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.MALFORMED_REQUEST;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 422 -> ErrorCode.VALIDATION_FAILED;
            default ->
                    status.is5xxServerError()
                            ? ErrorCode.INTERNAL_ERROR
                            : ErrorCode.MALFORMED_REQUEST;
        };
    }

    private static String firstCode(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return codes == null || codes.length == 0 ? "invalid" : codes[0];
    }
}
