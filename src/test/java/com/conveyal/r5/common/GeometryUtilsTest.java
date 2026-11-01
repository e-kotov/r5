package com.conveyal.r5.common;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;

import static com.conveyal.r5.common.GeometryUtils.turfDistance;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class GeometryUtilsTest {

    /// Check the results of turfDistance against hard-coded values produced by Turf itself in JS.
    /// The pairs cover travel along both axes, near the equator and at mid-latitudes.
    @Test
    public void turfReferenceValues () {
        assertEquals(111190.846158, turfDistance(0.5, -79.0, 0.5, -78.0), 1e-5,
                "east-west near the equator");
        assertEquals(111195.080234, turfDistance(0.5, -79.0, 1.5, -79.0), 1e-5,
                "north-south near the equator");
        assertEquals(13429.643884, turfDistance(37.7749, -122.4194, 37.8044, -122.2712), 1e-5,
                "mid-latitude");
        assertEquals(823.491991, turfDistance(48.8566, 2.3522, 48.8600, 2.3622), 1e-5,
                "short hop of typical inter-stop length");
    }

    /// Raised bounding box area limit (100 million km2). Envelopes are min/max lon, min/max lat.
    @Test
    public void boundingBoxAreaLimit () {
        // EPSG:3035 Europe area of use held to 80 N (about 34 million km2) and EPSG:4490 China (23 million km2).
        assertDoesNotThrow(() -> GeometryUtils.checkWgsEnvelopeSize(new Envelope(-35.58, 44.83, 24.6, 80.0), "Europe"));
        assertDoesNotThrow(() -> GeometryUtils.checkWgsEnvelopeSize(new Envelope(73.62, 134.77, 16.7, 53.56), "China"));
        // A near-global envelope (about 672 million km2) is still rejected.
        assertThrows(IllegalArgumentException.class,
                () -> GeometryUtils.checkWgsEnvelopeSize(new Envelope(-170, 170, -80, 80), "world"));
    }

}
