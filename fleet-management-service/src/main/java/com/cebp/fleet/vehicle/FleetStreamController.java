package com.cebp.fleet.vehicle;

import com.cebp.fleet.config.FleetProperties;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class FleetStreamController {
    private final AtomicLong sequence = new AtomicLong();
    private final FleetProperties properties;

    FleetStreamController(FleetProperties properties) {
        this.properties = properties;
    }

    @GetMapping(value = "/fleet/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() throws IOException {
        SseEmitter emitter = new SseEmitter();
        emitter.send(SseEmitter.event()
                .id(Long.toString(sequence.incrementAndGet()))
                .name("heartbeat")
                .reconnectTime(properties.sseHeartbeatSeconds() * 1_000L)
                .data(Map.of(), MediaType.APPLICATION_JSON));
        // The baseline proves the SSE transport with a finite heartbeat response.
        emitter.complete();
        return emitter;
    }
}
