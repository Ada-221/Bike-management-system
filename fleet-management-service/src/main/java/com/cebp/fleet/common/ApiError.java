package com.cebp.fleet.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** The one error body used by every endpoint: { code, message, timestamp, correlationId, details }. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        Instant timestamp,
        String correlationId,
        List<Detail> details) {

    /** One field-level problem (used by VALIDATION_ERROR). */
    public record Detail(String field, String issue) {
    }
}
