package com.cebp.fleet.common;

import org.springframework.http.HttpStatus;

/** 409: every business-rule conflict uses this status; the reason is in the code. */
public class ConflictException extends ApiException {

    public ConflictException(ErrorCode code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
