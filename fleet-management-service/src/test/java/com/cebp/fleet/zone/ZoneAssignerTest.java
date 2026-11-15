package com.cebp.fleet.zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.cebp.fleet.common.Geo;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZoneAssignerTest {

    private static final Zone CITY_CENTER = new Zone("zone_001", "City Center", 48.2100, 16.3600, 800);
    private static final Zone SOUTH = new Zone("zone_002", "South Station", 48.1858, 16.3758, 800);

    @Test
    void pointInsideAZoneGetsThatZone() {
        assertThat(ZoneAssigner.nearestContaining(List.of(CITY_CENTER, SOUTH), 48.2102, 16.3598))
                .isEqualTo("zone_001");
        assertThat(ZoneAssigner.nearestContaining(List.of(CITY_CENTER, SOUTH), 48.1860, 16.3760))
                .isEqualTo("zone_002");
    }

    @Test
    void pointOutsideEveryZoneGetsNull() {
        assertThat(ZoneAssigner.nearestContaining(List.of(CITY_CENTER, SOUTH), 48.30, 16.50)).isNull();
    }

    @Test
    void noZonesMeansNull() {
        assertThat(ZoneAssigner.nearestContaining(List.of(), 48.2102, 16.3598)).isNull();
    }

    @Test
    void overlappingZonesPickTheNearestCenter() {
        // Centers about 372 m apart, each with a 1000 m radius, so they overlap heavily.
        Zone a = new Zone("zone_a", "A", 48.0, 16.000, 1000);
        Zone b = new Zone("zone_b", "B", 48.0, 16.005, 1000);

        assertThat(ZoneAssigner.nearestContaining(List.of(a, b), 48.0, 16.001)).isEqualTo("zone_a");
        assertThat(ZoneAssigner.nearestContaining(List.of(a, b), 48.0, 16.004)).isEqualTo("zone_b");
        // Same answer whatever the order of the list.
        assertThat(ZoneAssigner.nearestContaining(List.of(b, a), 48.0, 16.001)).isEqualTo("zone_a");
    }

    @Test
    void haversineDistanceIsSane() {
        // 0.001 degrees of latitude is about 111 m everywhere.
        assertThat(Geo.distanceMeters(48.0, 16.0, 48.001, 16.0)).isCloseTo(111.2, within(1.0));
        assertThat(Geo.distanceMeters(48.0, 16.0, 48.0, 16.0)).isZero();
    }
}
