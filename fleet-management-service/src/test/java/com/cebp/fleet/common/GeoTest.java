package com.cebp.fleet.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class GeoTest {

    @Test
    void longitudeBoxIsWiderThanLatitudeBoxAwayFromTheEquator() {
        double latitudeDelta = Geo.latitudeDelta(1000);
        assertThat(Geo.longitudeDelta(0, 1000)).isCloseTo(latitudeDelta, within(1e-4));
        assertThat(Geo.longitudeDelta(60, 1000)).isCloseTo(latitudeDelta * 2, within(1e-3));
        assertThat(Geo.longitudeDelta(48.21, 1000)).isGreaterThan(latitudeDelta * 1.4);
    }

    @Test
    void boxAlwaysContainsEveryPointInsideTheCircle() {
        for (double lat : new double[] {0, 30, 48.21, 70, 85}) {
            double delta = Geo.longitudeDelta(lat, 5000);
            // A point exactly at the edge of the box must not be closer than the radius if outside it.
            assertThat(Geo.distanceMeters(lat, 10, lat, 10 + delta)).isGreaterThanOrEqualTo(5000 - 1);
        }
    }

    @Test
    void circleReachingAPoleHasNoLongitudeBound() {
        assertThat(Geo.longitudeDelta(89.99, 5000)).isEqualTo(180);
        assertThat(Geo.longitudeDelta(-90, 1)).isEqualTo(180);
    }
}
