package com.cebp.fleet.common;

import org.springframework.http.HttpStatus;

/** 404 NOT_FOUND. */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, message);
    }
}
