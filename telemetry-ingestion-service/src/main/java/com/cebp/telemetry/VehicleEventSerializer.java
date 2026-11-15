package com.cebp.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
class VehicleEventSerializer {
    private final ObjectMapper objectMapper;

    VehicleEventSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String toJson(VehicleEvent event) throws JsonProcessingException {
        return objectMapper.writeValueAsString(event);
    }
}