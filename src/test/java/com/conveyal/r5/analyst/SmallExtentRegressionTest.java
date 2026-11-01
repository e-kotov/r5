package com.conveyal.r5.analyst;

import com.conveyal.r5.common.GeometryUtils;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Regression test: raising and re-implementing the size guards must not change the answers for small regions.
/// The expected values were produced by running the same calculations on the unmodified v7.6.1-ek.1 code
/// (commit 6a80947), for each zoom level and each of the three WGS84 to Web Mercator conversions.
class SmallExtentRegressionTest {

    /// @param expected rows of {zoom, plain west, north, width, height, buffered west, north, width, height,
    ///                 trimmed west, north, width, height}
    private static void check (Envelope env, double area, int[][] expected, int gridCells,
                               double lat0, double lon0, double latLast, double lonLast) {
        assertDoesNotThrow(() -> GeometryUtils.checkWgsEnvelopeSize(env, "test"));
        assertEquals(area, GeometryUtils.roughWgsEnvelopeArea(env), 1e-6);
        for (int[] row : expected) {
            int zoom = row[0];
            assertExtents(WebMercatorExtents.forWgsEnvelope(env, zoom), zoom, row, 1);
            assertExtents(WebMercatorExtents.forBufferedWgsEnvelope(env, zoom), zoom, row, 5);
            assertExtents(WebMercatorExtents.forTrimmedWgsEnvelope(env, zoom), zoom, row, 9);
        }
        WebMercatorGridPointSet grid = new WebMercatorGridPointSet(env);
        assertEquals(gridCells, grid.featureCount());
        assertEquals(lat0, grid.getLat(0), 1e-12);
        assertEquals(lon0, grid.getLon(0), 1e-12);
        assertEquals(latLast, grid.getLat(gridCells - 1), 1e-12);
        assertEquals(lonLast, grid.getLon(gridCells - 1), 1e-12);
    }

    private static void assertExtents (WebMercatorExtents extents, int zoom, int[] row, int offset) {
        assertEquals(new WebMercatorExtents(row[offset], row[offset + 1], row[offset + 2], row[offset + 3], zoom),
                extents);
        assertEquals(row[offset + 2] * row[offset + 3], extents.nCells());
    }

    /// Porto Alegre, previously computed with the v7.6.1-ek.1 code.
    @Test
    void portoAlegre () {
        check(new Envelope(-51.3, -51.05, -30.25, -29.95), 801.381781, new int[][] {
            {9, 46858, 76973, 92, 128, 46858, 76973, 92, 128, 46858, 76973, 92, 128},
            {10, 93716, 153947, 183, 254, 93716, 153947, 183, 254, 93716, 153947, 183, 254},
            {11, 187432, 307895, 366, 506, 187432, 307895, 366, 506, 187432, 307895, 366, 506},
            {12, 374865, 615791, 730, 1011, 374865, 615791, 730, 1011, 374865, 615791, 730, 1011}
        }, 11776, -29.948985148600D, -51.299285888672D, -30.250763535949D, -51.049346923828D);
    }

    /// Berlin, previously computed with the v7.6.1-ek.1 code.
    @Test
    void berlin () {
        check(new Envelope(13.08, 13.77, 52.33, 52.68), 1815.519606, new int[][] {
            {9, 70298, 42889, 252, 211, 70298, 42889, 252, 211, 70298, 42889, 252, 211},
            {10, 140596, 85779, 504, 420, 140596, 85779, 504, 420, 140596, 85779, 504, 420},
            {11, 281193, 171559, 1006, 838, 281193, 171559, 1006, 838, 281193, 171559, 1006, 838},
            {12, 562386, 343118, 2011, 1676, 562386, 343118, 2011, 1676, 562386, 343118, 2011, 1676}
        }, 53172, 52.680545125763D, 13.080596923828D, 52.329464742089D, 13.769989013672D);
    }

    /// Porto, previously computed with the v7.6.1-ek.1 code.
    @Test
    void porto () {
        check(new Envelope(-8.7, -8.5, 41.1, 41.25), 278.888459, new int[][] {
            {9, 62368, 49021, 74, 74, 62368, 49021, 74, 74, 62368, 49021, 74, 74},
            {10, 124736, 98042, 147, 147, 124736, 98042, 147, 147, 124736, 98042, 147, 147},
            {11, 249473, 196085, 292, 292, 249473, 196085, 292, 292, 249473, 196085, 292, 292},
            {12, 498947, 392171, 583, 582, 498947, 392171, 583, 582, 498947, 392171, 583, 582}
        }, 5476, 41.249935026441D, -8.699798583984D, 41.099016761755D, -8.499298095703D);
    }

    /// Manhattan, previously computed with the v7.6.1-ek.1 code.
    @Test
    void manhattan () {
        check(new Envelope(-74.03, -73.9, 40.7, 40.88), 218.806587, new int[][] {
            {9, 38582, 49200, 48, 87, 38582, 49200, 48, 87, 38582, 49200, 48, 87},
            {10, 77164, 98400, 96, 174, 77164, 98400, 96, 174, 77165, 98400, 95, 174},
            {11, 154329, 196800, 191, 347, 154329, 196800, 191, 347, 154330, 196800, 190, 347},
            {12, 308659, 393601, 380, 693, 308659, 393601, 380, 693, 308660, 393601, 379, 693}
        }, 4176, 40.879256481431D, -74.029998779297D, 40.700422479272D, -73.900909423828D);
    }
}
