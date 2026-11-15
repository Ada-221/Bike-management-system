package com.cebp.fleet.common;

import java.util.List;
import org.springframework.http.HttpStatus;

/** A business or validation failure that should become an ApiError response. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;
    private final List<ApiError.Detail> details;

    public ApiException(HttpStatus status, ErrorCode code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, ErrorCode code, String message, List<ApiError.Detail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorCode getCode() {
        return code;
    }

    public List<ApiError.Detail> getDetails() {
        return details;
    }
}
