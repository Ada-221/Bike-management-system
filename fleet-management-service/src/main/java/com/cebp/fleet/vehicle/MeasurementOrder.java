package com.cebp.fleet.vehicle;

import java.time.Instant;

/** Keeps the full Kafka timestamp precision even when the REST timestamp is stored at database precision. */
public record MeasurementOrder(Instant measuredAt, Instant publishedAt, String eventId) {

    public boolean wins(String stored, Instant baseline) {
        if (stored == null) return !measuredAt.isBefore(baseline);

        String[] previous = stored.split("\\|", 3);
        int measured = measuredAt.compareTo(Instant.parse(previous[0]));
        if (measured != 0) return measured > 0;
        int published = publishedAt.compareTo(Instant.parse(previous[1]));
        return published > 0 || (published == 0 && eventId.compareTo(previous[2]) > 0);
    }

    String encode() {
        return measuredAt + "|" + publishedAt + "|" + eventId;
    }
}
