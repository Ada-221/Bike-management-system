package com.cebp.fleet.common;

import java.util.List;
import org.springframework.http.HttpStatus;

/** 400 VALIDATION_ERROR for a single bad field or parameter. */
public class BadRequestException extends ApiException {

    public BadRequestException(String field, String issue) {
        super(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Request validation failed.", List.of(new ApiError.Detail(field, issue)));
    }

    public BadRequestException(ErrorCode code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message, null);
    }
}
