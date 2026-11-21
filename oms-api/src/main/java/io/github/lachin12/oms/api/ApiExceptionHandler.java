package io.github.lachin12.oms.api;

import io.github.lachin12.oms.application.ConcurrentOrderUpdateException;
import io.github.lachin12.oms.application.IdempotencyConflictException;
import io.github.lachin12.oms.application.InvalidOrderRequestException;
import io.github.lachin12.oms.application.NotFoundException;
import io.github.lachin12.oms.application.OrderConflictException;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Stable public failures; unexpected infrastructure errors never expose SQL or stack traces. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Object> notFound(NotFoundException ex) {
        return response(HttpStatus.NOT_FOUND, "not-found", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<Object> idempotencyConflict(IdempotencyConflictException ex) {
        return response(HttpStatus.CONFLICT, "idempotency-conflict", ex.getMessage());
    }

    @ExceptionHandler(ConcurrentOrderUpdateException.class)
    ResponseEntity<Object> concurrentUpdate(ConcurrentOrderUpdateException ex) {
        return response(HttpStatus.CONFLICT, "concurrent-update", ex.getMessage());
    }

    @ExceptionHandler(InvalidOrderRequestException.class)
    ResponseEntity<Object> invalidArgument(InvalidOrderRequestException ex) {
        return response(HttpStatus.BAD_REQUEST, "invalid-request", ex.getMessage());
    }

    @ExceptionHandler(OrderConflictException.class)
    ResponseEntity<Object> illegalState(OrderConflictException ex) {
        return response(HttpStatus.CONFLICT, "illegal-state", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex) {
        logger.error("Unexpected OMS request failure", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "The request could not be completed.");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        String code = status.value() == 400 ? "invalid-request" : "http-" + status.value();
        String detail = status.value() == 400
                ? "Request is missing required data or contains invalid field values."
                : "The HTTP request could not be processed.";
        return new ResponseEntity<>(problem(status, code, detail), headers, status);
    }

    private static ResponseEntity<Object> response(HttpStatus status, String code, String detail) {
        return ResponseEntity.status(status).body(problem(status, code, detail));
    }

    private static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:trading-oms:error:" + code));
        problem.setProperty("code", code);
        return problem;
    }
}
