package com.cebp.fleet.common;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into the single ApiError format.
 * Spring's own errors (bad JSON, wrong method, unknown URL...) are caught by extending
 * ResponseEntityExceptionHandler; anything unexpected becomes 500 INTERNAL_ERROR.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException ex) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getDetails(), HttpHeaders.EMPTY);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred.", null, HttpHeaders.EMPTY);
    }

    /** Bean validation failures on request bodies: list each bad field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<ApiError.Detail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.Detail(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Request validation failed.", details, headers);
    }

    /** All other Spring MVC errors (malformed JSON, bad query value, unknown URL, wrong method...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        ErrorCode code;
        if (status == HttpStatus.NOT_FOUND) {
            code = ErrorCode.NOT_FOUND;
        } else if (status.is4xxClientError()) {
            code = ErrorCode.VALIDATION_ERROR;
        } else {
            code = ErrorCode.INTERNAL_ERROR;
        }

        String message;
        List<ApiError.Detail> details = null;
        if (ex instanceof HttpMessageNotReadableException) {
            message = "Request body is missing or malformed.";
        } else if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            message = "Request validation failed.";
            details = List.of(new ApiError.Detail(mismatch.getName(), "invalid value"));
        } else if (ex instanceof TypeMismatchException) {
            message = "Request validation failed.";
        } else if (status == HttpStatus.NOT_FOUND) {
            message = "Resource not found.";
        } else {
            message = status.getReasonPhrase() + ".";
        }
        return build(status, code, message, details, headers);
    }

    private ResponseEntity<Object> build(HttpStatus status, ErrorCode code, String message,
                                         List<ApiError.Detail> details, HttpHeaders headers) {
        ApiError error = new ApiError(code.name(), message, clock.instant(),
                MDC.get(CorrelationIdFilter.MDC_KEY), details);
        return new ResponseEntity<>(error, headers, status);
    }
}
